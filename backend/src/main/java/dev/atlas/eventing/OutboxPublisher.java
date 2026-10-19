package dev.atlas.eventing;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxPublisher(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID eventId, DeliveryModels.Payload payload) {
        jdbc.update(
            "INSERT INTO outbox_event(event_id,payment_id,event_type,payload_json) VALUES (?,?,?,?)",
            eventId,
            payload.paymentId(),
            payload.eventType(),
            json.writeValueAsString(payload)
        );
    }
}
