package dev.atlas.payments;

import static org.assertj.core.api.Assertions.*;

import dev.atlas.AtlasApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIntegrationTest {

    static final String TOKEN =
        "atlas-integration-token-at-least-32-characters";
    static final String URL = System.getenv().getOrDefault(
        "ATLAS_TEST_DB_URL",
        "jdbc:postgresql://localhost:5433/atlas_test"
    );

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (
            !URL.matches(
                "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/atlas_test"
            )
        ) throw new IllegalStateException(
            "Tests require a disposable local database named atlas_test"
        );
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.datasource.username", () -> "atlas");
        properties.add("spring.datasource.password", () -> "atlas-local-only");
        properties.add("atlas.api-token", () -> TOKEN);
        properties.add("atlas.worker.scheduler-enabled", () -> false);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    record Reply(int status, JsonNode body) {}

    @BeforeEach
    void clean() {
        jdbc.execute(
            "TRUNCATE idempotency_record,audit_event,ledger_posting,payment RESTART IDENTITY CASCADE"
        );
    }

    Reply send(
        String method,
        String path,
        String key,
        String body,
        String token
    ) throws Exception {
        var request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + path)
        ).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    Reply post(String path, String key, String body) throws Exception {
        return send("POST", path, key, body, TOKEN);
    }

    Reply get(String path) throws Exception {
        return send("GET", path, null, null, TOKEN);
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    String payload(long amount, String currency) {
        return (
            "{\"merchantId\":\"merchant_demo\",\"amountMinor\":" +
            amount +
            ",\"currency\":\"" +
            currency +
            "\"}"
        );
    }

    String authorize(long amount) throws Exception {
        Reply r = post("/api/payments", key(), payload(amount, "USD"));
        assertThat(r.status()).isEqualTo(201);
        return r.body().path("payment").path("id").asText();
    }

    List<Reply> race(List<Callable<Reply>> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = actions
                .stream()
                .map(action ->
                    executor.submit(() -> {
                        start.await();
                        return action.call();
                    })
                )
                .toList();
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (var future : futures) replies.add(future.get());
            return replies;
        }
    }

    void balanced() throws Exception {
        Reply r = get("/api/ledger/reconciliation");
        assertThat(r.status()).isEqualTo(200);
        for (JsonNode row : r.body()) {
            assertThat(row.path("balanced").asBoolean()).isTrue();
            assertThat(row.path("debitsMinor").asLong()).isEqualTo(
                row.path("creditsMinor").asLong()
            );
            assertThat(row.path("unbalancedPayments").asLong()).isZero();
        }
    }

    @Test
    void simultaneousDuplicateAuthorizationsHaveOneEffect() throws Exception {
        String key = key();
        var calls = new ArrayList<Callable<Reply>>();
        for (int n = 0; n < 25; n++) calls.add(() ->
            post("/api/payments", key, payload(12500, "USD"))
        );
        var replies = race(calls);
        assertThat(replies)
            .filteredOn(r -> r.status() == 201)
            .hasSize(1);
        assertThat(replies).allSatisfy(r ->
            assertThat(r.status()).isIn(200, 201)
        );
        assertThat(
            replies
                .stream()
                .map(r -> r.body().path("payment").path("id").asText())
                .distinct()
        ).hasSize(1);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record",
                Long.class
            )
        ).isEqualTo(1);
    }

    @Test
    void replayReturnsOriginalSnapshotAndChangedPayloadConflicts()
        throws Exception {
        String key = key();
        Reply original = post("/api/payments", key, payload(1000, "USD"));
        String id = original.body().path("payment").path("id").asText();
        assertThat(
            post("/api/payments/" + id + "/capture", key(), null).status()
        ).isEqualTo(200);
        Reply replay = post("/api/payments", key, payload(1000, "USD"));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("replayed").asBoolean()).isTrue();
        assertThat(replay.body().path("payment")).isEqualTo(
            original.body().path("payment")
        );
        assertThat(
            post("/api/payments", key, payload(1001, "USD")).status()
        ).isEqualTo(409);
        assertThat(
            post("/api/payments/" + id + "/reverse", key, null).status()
        ).isEqualTo(409);
    }

    @Test
    void differentKeysCannotCaptureTwice() throws Exception {
        String id = authorize(5000);
        var replies = race(
            List.of(
                () -> post("/api/payments/" + id + "/capture", key(), null),
                () -> post("/api/payments/" + id + "/capture", key(), null)
            )
        );
        assertThat(
            replies.stream().map(Reply::status).sorted()
        ).containsExactly(200, 409);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_posting",
                Long.class
            )
        ).isEqualTo(1);
        balanced();
    }

    @Test
    void concurrentRefundsNeverExceedCapture() throws Exception {
        String id = authorize(10000);
        post("/api/payments/" + id + "/capture", key(), null);
        var replies = race(
            List.of(
                () ->
                    post(
                        "/api/payments/" + id + "/refund",
                        key(),
                        "{\"amountMinor\":7000}"
                    ),
                () ->
                    post(
                        "/api/payments/" + id + "/refund",
                        key(),
                        "{\"amountMinor\":7000}"
                    )
            )
        );
        assertThat(
            replies.stream().map(Reply::status).sorted()
        ).containsExactly(200, 409);
        assertThat(
            get("/api/payments/" + id)
                .body()
                .path("payment")
                .path("refundedMinor")
                .asLong()
        ).isEqualTo(7000);
        balanced();
    }

    @Test
    void partialRepeatedAndExcessRefunds() throws Exception {
        String id = authorize(10000);
        post("/api/payments/" + id + "/capture", key(), null);
        String key = key();
        Reply first = post(
            "/api/payments/" + id + "/refund",
            key,
            "{\"amountMinor\":3000}"
        );
        Reply replay = post(
            "/api/payments/" + id + "/refund",
            key,
            "{\"amountMinor\":3000}"
        );
        assertThat(replay.body().path("payment")).isEqualTo(
            first.body().path("payment")
        );
        assertThat(
            post(
                "/api/payments/" + id + "/refund",
                key(),
                "{\"amountMinor\":7001}"
            ).status()
        ).isEqualTo(409);
        assertThat(
            post(
                "/api/payments/" + id + "/refund",
                key(),
                "{\"amountMinor\":7000}"
            )
                .body()
                .path("payment")
                .path("status")
                .asText()
        ).isEqualTo("REFUNDED");
        assertThat(
            post(
                "/api/payments/" + id + "/refund",
                key(),
                "{\"amountMinor\":1}"
            ).status()
        ).isEqualTo(409);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_posting",
                Long.class
            )
        ).isEqualTo(3);
        balanced();
    }

    @Test
    void invalidTransitionsDoNotProduceAuditOrLedgerEffects() throws Exception {
        String id = authorize(100);
        assertThat(
            post(
                "/api/payments/" + id + "/refund",
                key(),
                "{\"amountMinor\":1}"
            ).status()
        ).isEqualTo(409);
        assertThat(
            post("/api/payments/" + id + "/reverse", key(), null).status()
        ).isEqualTo(200);
        assertThat(
            post("/api/payments/" + id + "/capture", key(), null).status()
        ).isEqualTo(409);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)
        ).isEqualTo(2);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_posting",
                Long.class
            )
        ).isZero();
    }

    @Test
    void deterministicRiskBoundary() throws Exception {
        assertThat(
            post("/api/payments", key(), payload(50_000_000, "USD"))
                .body()
                .path("payment")
                .path("status")
                .asText()
        ).isEqualTo("AUTHORIZED");
        Reply declined = post(
            "/api/payments",
            key(),
            payload(50_000_001, "USD")
        );
        assertThat(
            declined.body().path("payment").path("status").asText()
        ).isEqualTo("DECLINED");
        String id = declined.body().path("payment").path("id").asText();
        assertThat(
            post("/api/payments/" + id + "/capture", key(), null).status()
        ).isEqualTo(409);
    }

    @Test
    void trustBoundaryValidationAndAuthorization() throws Exception {
        assertThat(
            send("GET", "/api/payments", null, null, null).status()
        ).isEqualTo(401);
        assertThat(
            send("GET", "/api/payments", null, null, "incorrect").status()
        ).isEqualTo(401);
        assertThat(
            send("GET", "/actuator/health", null, null, null).status()
        ).isEqualTo(200);
        for (String body : List.of(
            payload(0, "USD"),
            payload(-1, "USD"),
            payload(100, "BTC"),
            payload(1_000_000_000_001L, "USD"),
            "{\"merchantId\":\"merchant_demo\",\"amountMinor\":1.5,\"currency\":\"USD\"}",
            "{\"merchantId\":\"merchant_demo\",\"amountMinor\":100,\"currency\":\"USD\",\"cardNumber\":\"forbidden\"}",
            "{\"merchantId\":\"other\",\"amountMinor\":100,\"currency\":\"USD\"}",
            "{}"
        )) {
            assertThat(post("/api/payments", key(), body).status())
                .as(body)
                .isEqualTo(400);
        }
        assertThat(
            post("/api/payments", "bad", payload(100, "USD")).status()
        ).isEqualTo(400);
        assertThat(
            post("/api/payments", null, payload(100, "USD")).status()
        ).isEqualTo(400);
        assertThat(get("/api/payments?page=-1").status()).isEqualTo(400);
        assertThat(get("/api/payments?size=1000").status()).isEqualTo(400);
        assertThat(get("/api/payments/not-a-uuid").status()).isEqualTo(400);
        assertThat(
            get("/api/payments/" + UUID.randomUUID()).status()
        ).isEqualTo(404);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)
        ).isZero();
    }

    @Test
    void bodyLimitAppliesToFixedAndStreamingBodies() throws Exception {
        String oversized = " ".repeat(17000) + payload(100, "USD");
        assertThat(post("/api/payments", key(), oversized).status()).isEqualTo(
            413
        );
        var request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/api/payments")
        )
            .header("Authorization", "Bearer " + TOKEN)
            .header("Idempotency-Key", key())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(() ->
                    new java.io.ByteArrayInputStream(
                        oversized.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8
                        )
                    )
                )
            )
            .build();
        assertThat(
            http
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode()
        ).isEqualTo(413);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)
        ).isZero();
    }

    @Test
    void migrationAndDatabaseConstraintsProtectDurableState() throws Exception {
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success",
                Long.class
            )
        ).isGreaterThanOrEqualTo(1);
        String id = authorize(100);
        assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE payment SET refunded_minor=101 WHERE id=?",
                UUID.fromString(id)
            )
        ).isInstanceOf(
            org.springframework.dao.DataIntegrityViolationException.class
        );
        assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency) VALUES (?,?, 'CAPTURE','same','same',1,'USD')",
                UUID.randomUUID(),
                UUID.fromString(id)
            )
        ).isInstanceOf(
            org.springframework.dao.DataIntegrityViolationException.class
        );
    }

    @Test
    void postingFailureRollsBackStateAuditAndIdempotency() throws Exception {
        String id = authorize(100);
        // Intentionally corrupt only the disposable test DB to trigger the unique capture constraint mid-command.
        jdbc.update(
            "INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency) VALUES (?,?, 'CAPTURE','a','b',100,'USD')",
            UUID.randomUUID(),
            UUID.fromString(id)
        );
        String key = key();
        assertThat(
            post("/api/payments/" + id + "/capture", key, null).status()
        ).isEqualTo(500);
        assertThat(
            get("/api/payments/" + id)
                .body()
                .path("payment")
                .path("status")
                .asText()
        ).isEqualTo("AUTHORIZED");
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE request_key=?",
                Long.class,
                key
            )
        ).isZero();
    }

    @Test
    void reconciliationDetectsBalancedButInconsistentRecords()
        throws Exception {
        String id = authorize(500);
        post("/api/payments/" + id + "/capture", key(), null);
        balanced();
        jdbc.update(
            "UPDATE ledger_posting SET amount_minor=499 WHERE payment_id=?",
            UUID.fromString(id)
        );
        JsonNode usd = get("/api/ledger/reconciliation").body().get(0);
        assertThat(usd.path("debitsMinor").asLong()).isEqualTo(
            usd.path("creditsMinor").asLong()
        );
        assertThat(usd.path("balanced").asBoolean()).isFalse();
        assertThat(usd.path("unbalancedPayments").asLong()).isEqualTo(1);
        jdbc.update(
            "UPDATE ledger_posting SET amount_minor=500,currency='EUR' WHERE payment_id=?",
            UUID.fromString(id)
        );
        assertThat(
            get("/api/ledger/reconciliation")
                .body()
                .get(0)
                .path("balanced")
                .asBoolean()
        ).isFalse();
    }

    @Test
    void fixedSeedRefundSequencesPreserveAccountingInvariants()
        throws Exception {
        Random random = new Random(20261019);
        for (int trial = 0; trial < 12; trial++) {
            long amount = 10 + random.nextInt(2000);
            String id = authorize(amount);
            post("/api/payments/" + id + "/capture", key(), null);
            long remaining = amount;
            while (remaining > 0) {
                long part = Math.min(
                    remaining,
                    1 + random.nextInt((int) amount)
                );
                Reply refund = post(
                    "/api/payments/" + id + "/refund",
                    key(),
                    "{\"amountMinor\":" + part + "}"
                );
                assertThat(refund.status()).isEqualTo(200);
                remaining -= part;
                assertThat(
                    refund.body().path("payment").path("refundedMinor").asLong()
                ).isEqualTo(amount - remaining);
                balanced();
            }
        }
    }

    @Test
    void secondApplicationContextRecoversCommittedResponseAndHistory()
        throws Exception {
        String key = key();
        Reply original = post("/api/payments", key, payload(999, "INR"));
        // A fresh application instance has no process-local state; all business and response data must survive.
        try (
            ConfigurableApplicationContext second =
                new SpringApplicationBuilder(AtlasApplication.class).run(
                    "--server.port=0",
                    "--spring.datasource.url=" + URL,
                    "--spring.datasource.username=atlas",
                    "--spring.datasource.password=atlas-local-only",
                    "--atlas.api-token=" + TOKEN,
                    "--atlas.worker.scheduler-enabled=false"
                )
        ) {
            var service = second.getBean(PaymentService.class);
            var replay = service.authorize(
                key,
                new PaymentModels.Authorization("merchant_demo", 999, "INR")
            );
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.payment().id().toString()).isEqualTo(
                original.body().path("payment").path("id").asText()
            );
            assertThat(service.history(0, 10).total()).isEqualTo(1);
            assertThat(service.detail(replay.payment().id()).events()).hasSize(
                1
            );
        }
    }
}
