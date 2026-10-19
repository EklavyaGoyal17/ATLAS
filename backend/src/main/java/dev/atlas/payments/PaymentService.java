package dev.atlas.payments;

import static dev.atlas.payments.PaymentModels.*;

import dev.atlas.platform.ApiException;
import dev.atlas.platform.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PaymentService {

    private final PaymentStore store;
    private final JsonMapper json;
    private final dev.atlas.eventing.OutboxPublisher outbox;

    public PaymentService(
        PaymentStore store,
        JsonMapper json,
        dev.atlas.eventing.OutboxPublisher outbox
    ) {
        this.store = store;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional
    public Result authorize(String key, Authorization request) {
        return idempotent(
            key,
            "AUTHORIZE|" +
                request.merchantId() +
                "|" +
                request.amountMinor() +
                "|" +
                request.currency(),
            () -> {
                UUID id = UUID.randomUUID();
                String status =
                    request.amountMinor() > 50_000_000
                        ? "DECLINED"
                        : "AUTHORIZED";
                store.insert(id, request, status);
                event(
                    id,
                    status,
                    status.equals("AUTHORIZED")
                        ? "Synthetic authorization accepted; no funds moved."
                        : "Synthetic risk rule: amount exceeds 50,000,000 minor units."
                );
                return store.find(id, false);
            }
        );
    }

    @Transactional
    public Result capture(UUID id, String key) {
        return idempotent(key, "CAPTURE|" + id, () -> {
            Payment p = store.find(id, true);
            PaymentRules.checkTransition(
                p.status(),
                PaymentRules.Command.CAPTURE
            );
            store.capture(id);
            post(
                p,
                "CAPTURE",
                "acquirer_receivable",
                "merchant_payable",
                p.amountMinor()
            );
            event(
                id,
                "CAPTURED",
                "Full capture committed atomically with balanced ledger posting."
            );
            return store.find(id, false);
        });
    }

    @Transactional
    public Result refund(UUID id, String key, Refund request) {
        return idempotent(
            key,
            "REFUND|" + id + "|" + request.amountMinor(),
            () -> {
                Payment p = store.find(id, true);
                PaymentRules.checkTransition(
                    p.status(),
                    PaymentRules.Command.REFUND
                );
                long refunded = PaymentRules.refundTotal(
                    p.capturedMinor(),
                    p.refundedMinor(),
                    request.amountMinor()
                );
                store.refund(
                    id,
                    refunded,
                    refunded == p.capturedMinor() ? "REFUNDED" : "CAPTURED"
                );
                post(
                    p,
                    "REFUND",
                    "merchant_payable",
                    "acquirer_receivable",
                    request.amountMinor()
                );
                event(
                    id,
                    "REFUNDED",
                    "Refund of " +
                        request.amountMinor() +
                        " minor units committed with ledger posting."
                );
                return store.find(id, false);
            }
        );
    }

    @Transactional
    public Result reverse(UUID id, String key) {
        return idempotent(key, "REVERSE|" + id, () -> {
            Payment p = store.find(id, true);
            PaymentRules.checkTransition(
                p.status(),
                PaymentRules.Command.REVERSE
            );
            store.reverse(id);
            event(
                id,
                "REVERSED",
                "Authorization released; no ledger movement."
            );
            return store.find(id, false);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(UUID id) {
        return store.detail(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page history(int page, int size) {
        return store.history(page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.List<Reconciliation> reconcile() {
        return store.reconcile();
    }

    private Result idempotent(
        String key,
        String canonical,
        Supplier<Payment> action
    ) {
        if (
            key == null || !key.matches("[A-Za-z0-9._:-]{8,100}")
        ) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_IDEMPOTENCY_KEY",
            "Use 8–100 ASCII letters, digits, dots, underscores, colons or hyphens."
        );
        // Transaction-scoped PostgreSQL lock serializes duplicate keys across threads AND app instances.
        // A hash collision only serializes unrelated keys; the unique record still compares full keys.
        store.lockKey(key);
        String fingerprint = hash(canonical);
        var cached = store.savedResponse(key);
        if (cached.isPresent()) {
            var row = cached.get();
            if (!fingerprint.equals(row.fingerprint())) throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "This key already belongs to a different request. Use a new key."
            );
            return new Result(
                json.readValue(row.response(), Payment.class),
                true
            );
        }
        Payment payment = action.get();
        store.saveResponse(key, fingerprint, json.writeValueAsString(payment));
        return new Result(payment, false);
    }

    private void event(UUID id, String type, String detail) {
        UUID correlation = RequestContext.correlationId();
        UUID eventId = store.event(id, type, detail, correlation);
        Payment payment = store.find(id, false);
        outbox.enqueue(
            eventId,
            new dev.atlas.eventing.DeliveryModels.Payload(
                1,
                id,
                type,
                payment.status(),
                payment.currency(),
                payment.amountMinor(),
                payment.capturedMinor(),
                payment.refundedMinor(),
                payment.version(),
                correlation
            )
        );
    }

    private void post(
        Payment p,
        String kind,
        String debit,
        String credit,
        long amount
    ) {
        store.post(p, kind, debit, credit, amount);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
