package dev.atlas.eventing;

import static dev.atlas.eventing.DeliveryModels.*;

import dev.atlas.platform.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DeliveryStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public DeliveryStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String EVENT_SELECT = """
    SELECT o.*, (SELECT count(*) FROM notification n WHERE n.event_id=o.event_id) AS effects,
      (SELECT count(*) FROM delivery_log l WHERE l.event_id=o.event_id AND l.kind='DUPLICATE_SUPPRESSED') AS duplicates
    FROM outbox_event o
    """;

    private RowMapper<Event> mapper() {
        return (rs, n) ->
            new Event(
                rs.getObject("event_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("event_type"),
                json.readValue(rs.getString("payload_json"), Payload.class),
                rs.getString("status"),
                Fault.valueOf(rs.getString("fault")),
                rs.getInt("attempts"),
                rs.getLong("next_tick"),
                rs.getObject("lease_until_tick", Long.class),
                rs.getLong("effects"),
                rs.getLong("duplicates"),
                rs.getTimestamp("created_at").toInstant()
            );
    }

    public Event event(UUID id) {
        return jdbc
            .query(EVENT_SELECT + " WHERE o.event_id=?", mapper(), id)
            .stream()
            .findFirst()
            .orElseThrow(() ->
                new ApiException(
                    HttpStatus.NOT_FOUND,
                    "EVENT_NOT_FOUND",
                    "Outbox event does not exist."
                )
            );
    }

    public Page events(int page, int size) {
        if (
            page < 0 || page > 100000 || size < 1 || size > 100
        ) throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_PAGE",
            "Use page 0–100000 and size 1–100."
        );
        return new Page(
            jdbc.query(
                EVENT_SELECT + " ORDER BY o.sequence DESC LIMIT ? OFFSET ?",
                mapper(),
                size,
                (long) page * size
            ),
            page,
            size,
            jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class)
        );
    }

    public Detail detail(UUID id) {
        return new Detail(
            event(id),
            jdbc
                .query(
                    "SELECT * FROM delivery_log WHERE event_id=? ORDER BY sequence DESC LIMIT 100",
                    (rs, n) ->
                        new Timeline(
                            rs.getLong("sequence"),
                            rs.getLong("tick"),
                            rs.getInt("attempt"),
                            rs.getString("kind"),
                            rs.getString("detail"),
                            rs.getTimestamp("created_at").toInstant()
                        ),
                    id
                )
                .reversed()
        );
    }

    public Control control() {
        return jdbc.queryForObject(
            """
            SELECT current_tick,running,
              (SELECT count(*) FROM outbox_event WHERE status='PENDING') pending,
              (SELECT count(*) FROM outbox_event WHERE status='PROCESSING') processing,
              (SELECT count(*) FROM outbox_event WHERE status='RETRY') retry,
              (SELECT count(*) FROM outbox_event WHERE status='DELIVERED') delivered,
              (SELECT count(*) FROM outbox_event WHERE status='DEAD') dead,
              (SELECT count(*) FROM notification) effects FROM worker_control WHERE id=1
            """,
            (rs, n) ->
                new Control(
                    rs.getLong("current_tick"),
                    rs.getBoolean("running"),
                    rs.getLong("pending"),
                    rs.getLong("processing"),
                    rs.getLong("retry"),
                    rs.getLong("delivered"),
                    rs.getLong("dead"),
                    rs.getLong("effects")
                )
        );
    }

    public void requirePaused() {
        boolean running = lockControl();
        if (running) throw new ApiException(
            HttpStatus.CONFLICT,
            "WORKER_RUNNING",
            "Pause the worker before manual steps or preparing experiments."
        );
    }

    public boolean lockControl() {
        return jdbc.queryForObject(
            "SELECT running FROM worker_control WHERE id=1 FOR UPDATE",
            Boolean.class
        );
    }

    public void running(boolean running) {
        jdbc.update("UPDATE worker_control SET running=? WHERE id=1", running);
    }

    public Optional<Claim> claim(
        UUID selected,
        int advance,
        boolean scheduled
    ) {
        // Lock order is always control then event. The short claim transaction commits before consumer work.
        boolean running = jdbc.queryForObject(
            "SELECT running FROM worker_control WHERE id=1 FOR UPDATE",
            Boolean.class
        );
        if (scheduled && !running) return Optional.empty();
        if (!scheduled && running) throw new ApiException(
            HttpStatus.CONFLICT,
            "WORKER_RUNNING",
            "Pause the worker before manual steps."
        );
        long tick = jdbc.queryForObject(
            "UPDATE worker_control SET current_tick=current_tick+? WHERE id=1 RETURNING current_tick",
            Long.class,
            advance
        );
        var candidates = jdbc.query(
            """
            SELECT event_id,attempts,fault,status FROM outbox_event
            WHERE ((status IN ('PENDING','RETRY') AND next_tick<=?) OR (status='PROCESSING' AND lease_until_tick<=?))
            """ +
                (selected == null ? "" : " AND event_id=?") +
                " ORDER BY sequence FOR UPDATE SKIP LOCKED LIMIT 1",
            (rs, n) ->
                new Candidate(
                    rs.getObject("event_id", UUID.class),
                    rs.getInt("attempts"),
                    Fault.valueOf(rs.getString("fault")),
                    rs.getString("status")
                ),
            selected == null
                ? new Object[] { tick, tick }
                : new Object[] { tick, tick, selected }
        );
        if (candidates.isEmpty()) return Optional.empty();
        Candidate item = candidates.getFirst();
        if (item.status().equals("PROCESSING")) log(
            item.id(),
            tick,
            item.attempt(),
            "LEASE_EXPIRED",
            "Previous worker did not acknowledge before its lease expired."
        );
        if (item.attempt() >= 3) {
            jdbc.update(
                "UPDATE outbox_event SET status='DEAD',lease_token=NULL,lease_until_tick=NULL WHERE event_id=?",
                item.id()
            );
            log(
                item.id(),
                tick,
                item.attempt(),
                "DEAD_LETTERED",
                "Attempt limit reached after an abandoned lease."
            );
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        int attempt = item.attempt() + 1;
        jdbc.update(
            "UPDATE outbox_event SET status='PROCESSING',attempts=?,lease_token=?,lease_until_tick=? WHERE event_id=?",
            attempt,
            token,
            tick + 2,
            item.id()
        );
        log(
            item.id(),
            tick,
            attempt,
            "CLAIMED",
            "Delivery claimed with a new fencing token; lease lasts two logical ticks."
        );
        return Optional.of(
            new Claim(item.id(), token, attempt, item.fault(), tick)
        );
    }

    private record Candidate(
        UUID id,
        int attempt,
        Fault fault,
        String status
    ) {}

    public boolean owns(Claim claim) {
        var tokens = jdbc.query(
            "SELECT lease_token FROM outbox_event WHERE event_id=? AND status='PROCESSING' AND lease_until_tick>(SELECT current_tick FROM worker_control WHERE id=1) FOR UPDATE",
            (rs, n) -> rs.getObject(1, UUID.class),
            claim.eventId()
        );
        return (
            !tokens.isEmpty() && claim.leaseToken().equals(tokens.getFirst())
        );
    }

    public boolean apply(Claim claim) {
        if (!owns(claim)) return false;
        int inserted = jdbc.update(
            "INSERT INTO consumer_inbox(event_id,first_attempt,processed_tick) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            claim.eventId(),
            claim.attempt(),
            claim.tick()
        );
        if (inserted == 1) {
            jdbc.update(
                "INSERT INTO notification(event_id,payment_id,payload_json) SELECT event_id,payment_id,payload_json FROM outbox_event WHERE event_id=?",
                claim.eventId()
            );
            log(
                claim.eventId(),
                claim.tick(),
                claim.attempt(),
                "EFFECT_COMMITTED",
                "Inbox receipt and synthetic notification committed together."
            );
        } else log(
            claim.eventId(),
            claim.tick(),
            claim.attempt(),
            "DUPLICATE_SUPPRESSED",
            "Inbox already contains this event; no second notification was created."
        );
        return true;
    }

    public boolean acknowledge(Claim claim) {
        if (!owns(claim)) return false;
        jdbc.update(
            "UPDATE outbox_event SET status='DELIVERED',lease_token=NULL,lease_until_tick=NULL WHERE event_id=?",
            claim.eventId()
        );
        log(
            claim.eventId(),
            claim.tick(),
            claim.attempt(),
            "ACKNOWLEDGED",
            "Delivery acknowledged after the consumer transaction committed."
        );
        return true;
    }

    public boolean fail(Claim claim) {
        if (!owns(claim)) return false;
        boolean dead = claim.attempt() >= 3;
        long delay = RetryPolicy.delay(claim.attempt());
        jdbc.update(
            "UPDATE outbox_event SET status=?,next_tick=?,lease_token=NULL,lease_until_tick=NULL WHERE event_id=?",
            dead ? "DEAD" : "RETRY",
            claim.tick() + delay,
            claim.eventId()
        );
        log(
            claim.eventId(),
            claim.tick(),
            claim.attempt(),
            dead ? "DEAD_LETTERED" : "RETRY_SCHEDULED",
            dead
                ? "Three attempts exhausted. Manual review and redelivery are required."
                : "Consumer transaction failed; next attempt is due in " +
                      delay +
                      " logical tick(s)."
        );
        return true;
    }

    public void log(
        UUID id,
        long tick,
        int attempt,
        String kind,
        String detail
    ) {
        jdbc.update(
            "INSERT INTO delivery_log(event_id,tick,attempt,kind,detail) VALUES (?,?,?,?,?)",
            id,
            tick,
            attempt,
            kind,
            detail
        );
    }

    public void configure(UUID id, Fault fault) {
        requirePaused();
        int changed = jdbc.update(
            "UPDATE outbox_event SET fault=? WHERE event_id=? AND status='PENDING' AND attempts=0",
            fault.name(),
            id
        );
        if (changed != 1) throw new ApiException(
            HttpStatus.CONFLICT,
            "EVENT_ALREADY_STARTED",
            "Configure faults only on fresh pending events."
        );
    }

    public void redeliver(UUID id) {
        requirePaused();
        event(id);
        int changed = jdbc.update(
            "UPDATE outbox_event SET status='PENDING',attempts=0,next_tick=0,fault='NONE',lease_token=NULL,lease_until_tick=NULL WHERE event_id=? AND status IN ('DELIVERED','DEAD')",
            id
        );
        if (changed != 1) throw new ApiException(
            HttpStatus.CONFLICT,
            "EVENT_NOT_TERMINAL",
            "Only delivered or dead-lettered events can be redelivered."
        );
        log(
            id,
            control().tick(),
            0,
            "REDELIVERY_REQUESTED",
            "Delivery reset; the original envelope and inbox receipt are retained."
        );
    }

    public Optional<Experiment> experiment(String key) {
        return jdbc
            .query(
                "SELECT * FROM experiment WHERE request_key=?",
                (rs, n) ->
                    new Experiment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        Fault.valueOf(rs.getString("scenario")),
                        true
                    ),
                key
            )
            .stream()
            .findFirst();
    }

    public UUID eventForPayment(UUID payment) {
        return jdbc.queryForObject(
            "SELECT event_id FROM outbox_event WHERE payment_id=? ORDER BY sequence LIMIT 1",
            UUID.class,
            payment
        );
    }

    public void experiment(UUID id, String key, UUID event, Fault fault) {
        jdbc.update(
            "INSERT INTO experiment(id,request_key,event_id,scenario) VALUES (?,?,?,?)",
            id,
            key,
            event,
            fault.name()
        );
    }
}
