package dev.atlas.eventing;

import static dev.atlas.eventing.DeliveryModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.atlas.AtlasApplication;
import dev.atlas.payments.PaymentModels.Authorization;
import dev.atlas.payments.PaymentService;
import dev.atlas.platform.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryIntegrationTest {

    static final String TOKEN = "atlas-event-test-token-at-least-32-characters";
    static final String URL = System.getenv().getOrDefault(
        "ATLAS_TEST_DB_URL",
        "jdbc:postgresql://localhost:5433/atlas_test"
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        if (
            !URL.matches(
                "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/atlas_test"
            )
        ) throw new IllegalStateException(
            "Disposable local atlas_test database required"
        );
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.datasource.username", () -> "atlas");
        properties.add("spring.datasource.password", () -> "atlas-local-only");
        properties.add("atlas.api-token", () -> TOKEN);
        properties.add("atlas.worker.scheduler-enabled", () -> false);
    }

    @Autowired
    DeliveryService delivery;

    @Autowired
    DeliveryStore store;

    @Autowired
    ExperimentService experiments;

    @Autowired
    PaymentService payments;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JsonMapper json;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @BeforeEach
    void clean() {
        jdbc.execute(
            "TRUNCATE idempotency_record,audit_event,ledger_posting,payment RESTART IDENTITY CASCADE"
        );
        jdbc.update(
            "UPDATE worker_control SET running=false,current_tick=0 WHERE id=1"
        );
    }

    Experiment prepare(Fault fault) {
        return experiments.create(
            UUID.randomUUID().toString(),
            new ExperimentRequest(fault)
        );
    }

    StepResult step(UUID id, int advance) {
        return delivery.step(new Step(id, advance), false);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    } // Fixed test identifiers only.

    void invariants() {
        assertThat(payments.reconcile()).allSatisfy(row ->
            assertThat(row.balanced()).isTrue()
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM consumer_inbox i LEFT JOIN notification n USING(event_id) WHERE n.event_id IS NULL",
                Long.class
            )
        ).isZero();
    }

    @Test
    void outboxAuditPaymentAndResponseShareOneCommit() {
        var auth = payments.authorize(
            "test-event-key",
            new Authorization("merchant_demo", 12500, "USD")
        );
        payments.authorize(
            "test-event-key",
            new Authorization("merchant_demo", 12500, "USD")
        );
        assertThat(count("outbox_event")).isEqualTo(1);
        var event = delivery.events(0, 20).items().getFirst();
        assertThat(event.paymentId()).isEqualTo(auth.payment().id());
        assertThat(event.payload().paymentVersion()).isZero();
        assertThat(event.payload().status()).isEqualTo("AUTHORIZED");
        payments.capture(auth.payment().id(), "test-event-capture");
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(
            delivery.detail(event.eventId()).event().payload().status()
        ).isEqualTo("AUTHORIZED");
        assertThat(
            delivery.events(0, 20).items().getFirst().payload().status()
        ).isEqualTo("CAPTURED");
    }

    @Test
    void outboxInsertFailureRollsBackAllPaymentEffects() {
        // Temporary rejecting constraint only in the guarded disposable DB; finally always restores it.
        jdbc.execute(
            "ALTER TABLE outbox_event ADD CONSTRAINT reject_test_event CHECK (event_type<>'AUTHORIZED') NOT VALID"
        );
        try {
            assertThatThrownBy(() ->
                payments.authorize(
                    "test-rollback-key",
                    new Authorization("merchant_demo", 12500, "USD")
                )
            ).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class
            );
            assertThat(count("payment")).isZero();
            assertThat(count("audit_event")).isZero();
            assertThat(count("outbox_event")).isZero();
            assertThat(count("idempotency_record")).isZero();
        } finally {
            jdbc.execute(
                "ALTER TABLE outbox_event DROP CONSTRAINT reject_test_event"
            );
        }
    }

    @Test
    void publisherRequiresAnExistingBusinessTransaction() {
        assertThatThrownBy(() ->
            publisher.enqueue(
                UUID.randomUUID(),
                new Payload(
                    1,
                    UUID.randomUUID(),
                    "AUTHORIZED",
                    "AUTHORIZED",
                    "USD",
                    100,
                    0,
                    0,
                    0,
                    UUID.randomUUID()
                )
            )
        ).isInstanceOf(
            org.springframework.transaction.IllegalTransactionStateException.class
        );
    }

    @Test
    void crashBeforeCommitRollsBackThenRecovers() {
        UUID id = prepare(Fault.CRASH_BEFORE_COMMIT_ONCE).eventId();
        assertThat(step(id, 1).outcome()).isEqualTo("CRASH_BEFORE_COMMIT");
        assertThat(count("consumer_inbox")).isZero();
        assertThat(count("notification")).isZero();
        assertThat(delivery.detail(id).event().status()).isEqualTo(
            "PROCESSING"
        );
        assertThat(step(id, 1).outcome()).isEqualTo("IDLE");
        assertThat(step(id, 1).outcome()).isEqualTo("DELIVERED");
        assertThat(delivery.detail(id).event().effects()).isEqualTo(1);
        assertThat(delivery.detail(id).event().attempts()).isEqualTo(2);
        assertThat(delivery.detail(id).timeline())
            .extracting(Timeline::kind)
            .contains(
                "CRASH_BEFORE_COMMIT",
                "LEASE_EXPIRED",
                "EFFECT_COMMITTED",
                "ACKNOWLEDGED"
            );
        invariants();
    }

    @Test
    void crashAfterCommitKeepsOneEffectAndSuppressesDuplicate() {
        UUID id = prepare(Fault.CRASH_AFTER_COMMIT_ONCE).eventId();
        assertThat(step(id, 1).outcome()).isEqualTo("CRASH_AFTER_COMMIT");
        assertThat(count("consumer_inbox")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(delivery.detail(id).event().status()).isEqualTo(
            "PROCESSING"
        );
        assertThat(step(id, 2).outcome()).isEqualTo("DELIVERED");
        Event event = delivery.detail(id).event();
        assertThat(event.effects()).isEqualTo(1);
        assertThat(event.duplicatesSuppressed()).isEqualTo(1);
        assertThat(delivery.detail(id).timeline())
            .extracting(Timeline::kind)
            .containsSubsequence(
                "EFFECT_COMMITTED",
                "CRASH_AFTER_COMMIT",
                "LEASE_EXPIRED",
                "CLAIMED",
                "DUPLICATE_SUPPRESSED",
                "ACKNOWLEDGED"
            );
        invariants();
    }

    @Test
    void retriesRespectExponentialDueTicksAndExhaust() {
        UUID id = prepare(Fault.ALWAYS_FAIL).eventId();
        assertThat(step(id, 1).outcome()).isEqualTo("RETRY_SCHEDULED");
        assertThat(delivery.detail(id).event().nextTick()).isEqualTo(2);
        assertThat(step(id, 1).outcome()).isEqualTo("RETRY_SCHEDULED");
        assertThat(delivery.detail(id).event().nextTick()).isEqualTo(4);
        assertThat(step(id, 1).outcome()).isEqualTo("IDLE");
        assertThat(step(id, 1).outcome()).isEqualTo("DEAD_LETTERED");
        assertThat(delivery.detail(id).event().status()).isEqualTo("DEAD");
        assertThat(delivery.detail(id).event().attempts()).isEqualTo(3);
        assertThat(count("notification")).isZero();
        delivery.redeliver(id);
        assertThat(step(id, 1).outcome()).isEqualTo("DELIVERED");
        assertThat(count("notification")).isEqualTo(1);
        invariants();
    }

    @Test
    void redeliveryRetainsOriginalEnvelopeAndInbox() {
        UUID id = prepare(Fault.NONE).eventId();
        step(id, 1);
        Payload original = delivery.detail(id).event().payload();
        delivery.redeliver(id);
        step(id, 1);
        Event event = delivery.detail(id).event();
        assertThat(event.payload()).isEqualTo(original);
        assertThat(event.effects()).isEqualTo(1);
        assertThat(event.duplicatesSuppressed()).isEqualTo(1);
        assertThatThrownBy(() -> {
            delivery.redeliver(id);
            delivery.redeliver(id);
        }).isInstanceOf(ApiException.class);
    }

    @Test
    void staleWorkerCannotApplyOrAcknowledgeAfterLeaseReclaimed() {
        UUID id = prepare(Fault.NONE).eventId();
        var tx = new TransactionTemplate(manager);
        Claim stale = tx.execute(s -> store.claim(id, 1, false).orElseThrow());
        Claim current = tx.execute(s ->
            store.claim(id, 2, false).orElseThrow()
        );
        assertThat(
            Boolean.TRUE.equals(tx.execute(s -> store.apply(stale)))
        ).isFalse();
        assertThat(
            Boolean.TRUE.equals(tx.execute(s -> store.acknowledge(stale)))
        ).isFalse();
        assertThat(
            Boolean.TRUE.equals(tx.execute(s -> store.fail(stale)))
        ).isFalse();
        assertThat(
            Boolean.TRUE.equals(tx.execute(s -> store.apply(current)))
        ).isTrue();
        assertThat(
            Boolean.TRUE.equals(tx.execute(s -> store.acknowledge(current)))
        ).isTrue();
        assertThat(count("notification")).isEqualTo(1);
        invariants();
    }

    @Test
    void abandonedThirdLeaseIsDeadLettered() {
        UUID id = prepare(Fault.NONE).eventId();
        var tx = new TransactionTemplate(manager);
        tx.execute(s -> store.claim(id, 1, false));
        tx.execute(s -> store.claim(id, 2, false));
        tx.execute(s -> store.claim(id, 2, false));
        assertThat(step(id, 2).outcome()).isEqualTo("IDLE");
        assertThat(delivery.detail(id).event().status()).isEqualTo("DEAD");
        assertThat(count("notification")).isZero();
    }

    @Test
    void concurrentStepsNeverProduceDuplicateEffects() throws Exception {
        UUID id = prepare(Fault.NONE).eventId();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<
                java.util.concurrent.Future<StepResult>
            >();
            for (int n = 0; n < 8; n++) futures.add(
                pool.submit(() -> {
                    start.await();
                    return step(id, 1);
                })
            );
            start.countDown();
            for (var future : futures) future.get();
        }
        Event event = delivery.detail(id).event();
        for (
            int remaining = 0;
            remaining < 4 &&
            !List.of("DELIVERED", "DEAD").contains(event.status());
            remaining++
        ) {
            step(id, 2);
            event = delivery.detail(id).event();
        }
        if (event.status().equals("DEAD")) {
            delivery.redeliver(id);
            step(id, 1);
        }
        assertThat(delivery.detail(id).event().effects()).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        invariants();
    }

    @Test
    void experimentSetupIsIdempotentAndConflictingScenariosFail() {
        String key = UUID.randomUUID().toString();
        var first = experiments.create(key, new ExperimentRequest(Fault.NONE));
        var replay = experiments.create(key, new ExperimentRequest(Fault.NONE));
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(replay.replayed()).isTrue();
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThatThrownBy(() ->
            experiments.create(key, new ExperimentRequest(Fault.ALWAYS_FAIL))
        ).isInstanceOf(ApiException.class);
        delivery.running(true);
        assertThat(
            experiments
                .create(key, new ExperimentRequest(Fault.NONE))
                .replayed()
        ).isTrue();
        assertThatThrownBy(() -> prepare(Fault.NONE)).isInstanceOf(
            ApiException.class
        );
        assertThatThrownBy(() -> step(first.eventId(), 1)).isInstanceOf(
            ApiException.class
        );
        delivery.running(false);
    }

    @Test
    void freshApplicationRecoversUnacknowledgedCommittedEffect() {
        UUID id = prepare(Fault.CRASH_AFTER_COMMIT_ONCE).eventId();
        step(id, 1);
        try (
            var context = new SpringApplicationBuilder(
                AtlasApplication.class
            ).run(
                "--server.port=0",
                "--spring.datasource.url=" + URL,
                "--spring.datasource.username=atlas",
                "--spring.datasource.password=atlas-local-only",
                "--atlas.api-token=" + TOKEN,
                "--atlas.worker.scheduler-enabled=false"
            )
        ) {
            var recovered = context.getBean(DeliveryService.class);
            assertThat(
                recovered.step(new Step(id, 2), false).outcome()
            ).isEqualTo("DELIVERED");
            assertThat(recovered.detail(id).event().effects()).isEqualTo(1);
            assertThat(
                recovered.detail(id).event().duplicatesSuppressed()
            ).isEqualTo(1);
        }
    }

    @Test
    void backgroundSchedulerProcessesOnlyAfterExplicitStart() throws Exception {
        UUID id = prepare(Fault.NONE).eventId();
        try (
            var context = new SpringApplicationBuilder(
                AtlasApplication.class
            ).run(
                "--server.port=0",
                "--spring.datasource.url=" + URL,
                "--spring.datasource.username=atlas",
                "--spring.datasource.password=atlas-local-only",
                "--atlas.api-token=" + TOKEN,
                "--atlas.worker.scheduler-enabled=true",
                "--atlas.worker.interval-ms=30"
            )
        ) {
            var worker = context.getBean(DeliveryService.class);
            assertThat(worker.detail(id).event().status()).isEqualTo("PENDING");
            worker.running(true);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (
                    !worker.detail(id).event().status().equals("DELIVERED") &&
                    System.nanoTime() < deadline
                )
                Thread.sleep(20);
            worker.running(false);
            assertThat(worker.detail(id).event().status()).isEqualTo(
                "DELIVERED"
            );
            assertThat(worker.detail(id).event().effects()).isEqualTo(1);
        }
    }

    @Test
    void deliveryApiAuthorizationValidationAndNotFound() throws Exception {
        var unauth = http.send(
            HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/events")
            ).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(unauth.statusCode()).isEqualTo(401);
        assertThat(
            request("GET", "/api/events?size=101", null).statusCode()
        ).isEqualTo(400);
        assertThat(
            request(
                "GET",
                "/api/events/" + UUID.randomUUID(),
                null
            ).statusCode()
        ).isEqualTo(404);
        assertThat(
            request(
                "POST",
                "/api/worker/step",
                "{\"advanceTicks\":0}"
            ).statusCode()
        ).isEqualTo(400);
        assertThat(
            request(
                "POST",
                "/api/worker/step",
                "{\"advanceTicks\":17}"
            ).statusCode()
        ).isEqualTo(400);
        assertThat(
            request(
                "POST",
                "/api/experiments",
                "{\"scenario\":\"UNKNOWN\"}"
            ).statusCode()
        ).isEqualTo(400);
        assertThat(request("GET", "/api/worker", null).statusCode()).isEqualTo(
            200
        );
        assertThat(request("PUT", "/api/worker", "{}").statusCode()).isEqualTo(
            400
        );
        assertThat(
            request(
                "PUT",
                "/api/worker",
                " ".repeat(17000) + "{\"running\":false}"
            ).statusCode()
        ).isEqualTo(413);
    }

    HttpResponse<String> request(String method, String path, String body)
        throws Exception {
        return http.send(
            HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)
            )
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
