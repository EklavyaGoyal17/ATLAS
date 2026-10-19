package dev.atlas.eventing;

import dev.atlas.platform.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Real signed loopback HTTP delivery to a local receiver; no user-controlled destination URLs. */
@RestController
@RequestMapping("/api/webhooks")
public class LocalWebhooks {

    private final DeliveryStore store;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String token;
    private final TransactionTemplate tx;
    private final dev.atlas.observability.Telemetry telemetry;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public LocalWebhooks(
        DeliveryStore store,
        JdbcTemplate jdbc,
        JsonMapper json,
        @Value("${atlas.api-token}") String token,
        PlatformTransactionManager manager,
        dev.atlas.observability.Telemetry telemetry
    ) {
        this.store = store;
        this.jdbc = jdbc;
        this.json = json;
        this.token = token;
        this.telemetry = telemetry;
        tx = new TransactionTemplate(manager);
    }

    @PostMapping("/demo/{eventId}")
    public Map<String, Object> demo(
        @PathVariable UUID eventId,
        HttpServletRequest request
    ) throws Exception {
        var event = store.event(eventId);
        String body = json.writeValueAsString(event.payload());
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(timestamp, eventId, body);
        var deliveries = new ArrayList<Object>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var builder = HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" +
                        request.getLocalPort() +
                        "/api/webhooks/receive/" +
                        eventId
                )
            )
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Atlas-Timestamp", timestamp)
                .header("X-Atlas-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body));
            telemetry.inject(builder);
            var response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                "LOCAL_WEBHOOK_FAILED",
                "Loopback receiver rejected delivery."
            );
            deliveries.add(json.readTree(response.body()));
        }
        return Map.of(
            "eventId",
            eventId,
            "deliveries",
            deliveries,
            "effects",
            jdbc.queryForObject(
                "SELECT count(*) FROM webhook_receipt WHERE event_id=?",
                Long.class,
                eventId
            ),
            "scope",
            "Three real signed HTTP deliveries to this API's loopback receiver; no external network destination or financial side effect."
        );
    }

    @PostMapping("/receive/{eventId}")
    public Map<String, Object> receive(
        @PathVariable UUID eventId,
        @RequestHeader("X-Atlas-Timestamp") String timestamp,
        @RequestHeader("X-Atlas-Signature") String signature,
        @RequestBody String body
    ) {
        long supplied;
        try {
            supplied = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw rejected();
        }
        if (
            supplied < Instant.now().getEpochSecond() - 300 ||
            supplied > Instant.now().getEpochSecond() + 30 ||
            signature.length() != 64 ||
            !MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.US_ASCII),
                sign(timestamp, eventId, body).getBytes(
                    StandardCharsets.US_ASCII
                )
            )
        ) throw rejected();
        var event = store.event(eventId);
        if (
            !json
                .readValue(body, DeliveryModels.Payload.class)
                .equals(event.payload())
        ) throw new ApiException(
            HttpStatus.CONFLICT,
            "ENVELOPE_MISMATCH",
            "Receiver accepts only the immutable local outbox envelope."
        );
        return tx.execute(status -> {
            String hash = hash(body);
            int inserted = jdbc.update(
                "INSERT INTO webhook_receipt(event_id,payload_sha256) VALUES(?,?) ON CONFLICT(event_id) DO NOTHING",
                eventId,
                hash
            );
            if (
                !hash.equals(
                    jdbc.queryForObject(
                        "SELECT payload_sha256 FROM webhook_receipt WHERE event_id=?",
                        String.class,
                        eventId
                    )
                )
            ) throw new ApiException(
                HttpStatus.CONFLICT,
                "ENVELOPE_MISMATCH",
                "Event ID already used with different payload bytes."
            );
            return Map.of(
                "eventId",
                eventId,
                "duplicate",
                inserted == 0,
                "effectCount",
                1
            );
        });
    }

    public String sign(String timestamp, UUID eventId, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                new SecretKeySpec(
                    token.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
                )
            );
            return HexFormat.of().formatHex(
                mac.doFinal(
                    (timestamp + "." + eventId + "." + body).getBytes(
                        StandardCharsets.UTF_8
                    )
                )
            );
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hash(String body) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    body.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiException rejected() {
        return new ApiException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_SIGNATURE",
            "Webhook signature is invalid or expired."
        );
    }
}
