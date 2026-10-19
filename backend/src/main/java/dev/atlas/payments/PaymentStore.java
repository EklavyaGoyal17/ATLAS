package dev.atlas.payments;

import static dev.atlas.payments.PaymentModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentStore {

    private final JdbcTemplate jdbc;

    public PaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<Payment> PAYMENT = (rs, n) -> readPayment(rs);

    static Payment readPayment(ResultSet rs) throws SQLException {
        return new Payment(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getLong("captured_minor"),
            rs.getLong("refunded_minor"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    public Payment find(UUID id, boolean lock) {
        List<Payment> rows = jdbc.query(
            "SELECT * FROM payment WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            PAYMENT,
            id
        );
        if (rows.isEmpty()) throw new dev.atlas.platform.ApiException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "PAYMENT_NOT_FOUND",
            "Payment does not exist."
        );
        return rows.getFirst();
    }

    public Page history(int page, int size) {
        if (
            page < 0 || page > 100000 || size < 1 || size > 100
        ) throw new dev.atlas.platform.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "INVALID_PAGE",
            "Use page 0–100000 and size 1–100."
        );
        return new Page(
            jdbc.query(
                "SELECT * FROM payment ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                PAYMENT,
                size,
                (long) page * size
            ),
            page,
            size,
            jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)
        );
    }

    public Detail detail(UUID id) {
        Payment payment = find(id, false);
        var events = jdbc.query(
            "SELECT * FROM audit_event WHERE payment_id = ? ORDER BY sequence",
            (rs, n) ->
                new Event(
                    rs.getLong("sequence"),
                    rs.getString("type"),
                    rs.getString("detail"),
                    rs.getObject("correlation_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
                ),
            id
        );
        var postings = jdbc.query(
            "SELECT * FROM ledger_posting WHERE payment_id = ? ORDER BY created_at,id",
            (rs, n) ->
                new Posting(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("debit_account"),
                    rs.getString("credit_account"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency"),
                    rs.getTimestamp("created_at").toInstant()
                ),
            id
        );
        return new Detail(payment, events, postings);
    }

    public void lockKey(String key) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            rs -> {},
            "merchant_demo|" + key
        );
    }

    public record SavedResponse(String fingerprint, String response) {}

    public java.util.Optional<SavedResponse> savedResponse(String key) {
        return jdbc
            .query(
                "SELECT fingerprint,response_json FROM idempotency_record WHERE merchant_id=? AND request_key=?",
                (rs, n) ->
                    new SavedResponse(
                        rs.getString("fingerprint"),
                        rs.getString("response_json")
                    ),
                "merchant_demo",
                key
            )
            .stream()
            .findFirst();
    }

    public void saveResponse(String key, String fingerprint, String response) {
        jdbc.update(
            "INSERT INTO idempotency_record(merchant_id,request_key,fingerprint,response_json) VALUES (?,?,?,?)",
            "merchant_demo",
            key,
            fingerprint,
            response
        );
    }

    public void insert(UUID id, Authorization request, String status) {
        jdbc.update(
            "INSERT INTO payment(id,merchant_id,amount_minor,currency,status) VALUES (?,?,?,?,?)",
            id,
            request.merchantId(),
            request.amountMinor(),
            request.currency(),
            status
        );
    }

    public void capture(UUID id) {
        jdbc.update(
            "UPDATE payment SET status='CAPTURED',captured_minor=amount_minor,version=version+1 WHERE id=?",
            id
        );
    }

    public void refund(UUID id, long refunded, String status) {
        jdbc.update(
            "UPDATE payment SET refunded_minor=?,status=?,version=version+1 WHERE id=?",
            refunded,
            status,
            id
        );
    }

    public void reverse(UUID id) {
        jdbc.update(
            "UPDATE payment SET status='REVERSED',version=version+1 WHERE id=?",
            id
        );
    }

    public UUID event(UUID id, String type, String detail, UUID correlationId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO audit_event(id,payment_id,type,detail,correlation_id) VALUES (?,?,?,?,?)",
            eventId,
            id,
            type,
            detail,
            correlationId
        );
        return eventId;
    }

    public void post(
        Payment payment,
        String kind,
        String debit,
        String credit,
        long amount
    ) {
        jdbc.update(
            "INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            payment.id(),
            kind,
            debit,
            credit,
            amount,
            payment.currency()
        );
    }

    public List<Reconciliation> reconcile() {
        return List.of("USD", "EUR", "INR")
            .stream()
            .map(currency -> {
                long debits = jdbc.queryForObject(
                    "SELECT COALESCE(sum(debit_minor),0) FROM ledger_entry WHERE currency=?",
                    Long.class,
                    currency
                );
                long credits = jdbc.queryForObject(
                    "SELECT COALESCE(sum(credit_minor),0) FROM ledger_entry WHERE currency=?",
                    Long.class,
                    currency
                );
                long count = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_posting WHERE currency=?",
                    Long.class,
                    currency
                );
                long mismatches = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM payment p WHERE p.currency=? AND (
                      p.captured_minor <> COALESCE((SELECT sum(amount_minor) FROM ledger_posting l WHERE l.payment_id=p.id AND l.kind='CAPTURE'),0)
                      OR p.refunded_minor <> COALESCE((SELECT sum(amount_minor) FROM ledger_posting l WHERE l.payment_id=p.id AND l.kind='REFUND'),0)
                      OR EXISTS (SELECT 1 FROM ledger_posting l WHERE l.payment_id=p.id AND l.currency<>p.currency))
                    """,
                    Long.class,
                    currency
                );
                return new Reconciliation(
                    currency,
                    debits,
                    credits,
                    mismatches,
                    count,
                    debits == credits && mismatches == 0
                );
            })
            .toList();
    }
}
