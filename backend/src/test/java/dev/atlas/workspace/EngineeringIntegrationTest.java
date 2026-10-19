package dev.atlas.workspace;

import static org.assertj.core.api.Assertions.*;

import dev.atlas.observability.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineeringIntegrationTest {

    static final String TOKEN = "atlas-engineering-test-at-least-32-characters";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry p) {
        String url = System.getenv().getOrDefault(
            "ATLAS_TEST_DB_URL",
            "jdbc:postgresql://localhost:5433/atlas_test"
        );
        if (
            !url.matches(
                "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/atlas_test"
            )
        ) throw new IllegalStateException(
            "Disposable local atlas_test required"
        );
        p.add("spring.datasource.url", () -> url);
        p.add("spring.datasource.username", () -> "atlas");
        p.add("spring.datasource.password", () -> "atlas-local-only");
        p.add("atlas.api-token", () -> TOKEN);
        p.add("atlas.worker.scheduler-enabled", () -> false);
    }

    @Autowired
    Documents documents;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IncidentService incidents;

    @Autowired
    Telemetry telemetry;

    @Autowired
    dev.atlas.eventing.ExperimentService experiments;

    @LocalServerPort
    int port;

    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workspace_document,workspace_audit CASCADE");
    }

    HttpResponse<String> request(String method, String path, String body)
        throws Exception {
        return client.send(
            HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api" + path)
            )
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    void documentsPersistOptimisticVersionsRevisionsAndDeletion()
        throws Exception {
        UUID id = UUID.randomUUID();
        var write = new Documents.Write(
            Documents.Kind.ADR,
            "Decision",
            json.readTree(
                "{\"Context\":\"Synthetic architecture\",\"Decision\":\"Use a monolith\"}"
            ),
            0
        );
        assertThat(
            request(
                "PUT",
                "/workspace/documents/" + id,
                json.writeValueAsString(write)
            ).statusCode()
        ).isEqualTo(200);
        assertThat(
            request(
                "PUT",
                "/workspace/documents/" + id,
                json.writeValueAsString(write)
            ).statusCode()
        ).isEqualTo(409);
        documents.save(
            id,
            new Documents.Write(
                write.kind(),
                "Revised decision",
                write.body(),
                1
            )
        );
        assertThat(documents.revisions(id)).hasSize(2);
        assertThat(documents.list().getFirst().version()).isEqualTo(2);
        assertThat(
            request("DELETE", "/workspace/documents/" + id, "").statusCode()
        ).isEqualTo(200);
        assertThat(documents.list()).isEmpty();
        assertThat(documents.revisions(id)).isEmpty();
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workspace_audit WHERE action='DOCUMENT_DELETED'",
                Long.class
            )
        ).isEqualTo(1);
    }

    @Test
    void concurrentStaleDocumentWritesCannotOverwriteOneAnother()
        throws Exception {
        UUID id = UUID.randomUUID();
        var body = json.readTree("{}");
        documents.save(
            id,
            new Documents.Write(Documents.Kind.JOURNAL, "Initial", body, 0)
        );
        try (
            var executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        ) {
            var results = executor.invokeAll(
                java.util.stream.IntStream.range(0, 2)
                    .<java.util.concurrent.Callable<Integer>>mapToObj(
                        i -> () ->
                            request(
                                "PUT",
                                "/workspace/documents/" + id,
                                json.writeValueAsString(
                                    new Documents.Write(
                                        Documents.Kind.JOURNAL,
                                        "Change " + i,
                                        body,
                                        1
                                    )
                                )
                            ).statusCode()
                    )
                    .toList()
            );
            assertThat(
                List.of(results.get(0).get(), results.get(1).get())
            ).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void rejectsInvalidDiagramsAndOversizedDocuments() throws Exception {
        assertThat(
            request(
                "PUT",
                "/workspace/documents/" + UUID.randomUUID(),
                "{\"kind\":\"DIAGRAM\",\"title\":\"Invalid\",\"expectedVersion\":0,\"body\":{}}"
            ).statusCode()
        ).isEqualTo(400);
        assertThatThrownBy(() ->
            documents.save(
                UUID.randomUUID(),
                new Documents.Write(
                    Documents.Kind.JOURNAL,
                    "Large",
                    json.valueToTree(Map.of("content", "x".repeat(12001))),
                    0
                )
            )
        ).hasMessageContaining("12 KiB");
    }

    @Test
    void boundedDocumentPagesAndKindFiltersRemainReachable() throws Exception {
        jdbc.update(
            "INSERT INTO workspace_document(id,kind,title,version,body_json) SELECT md5('page-'||i)::uuid,'JOURNAL','Page fixture '||i,1,'{}' FROM generate_series(1,201) i"
        );
        assertThat(documents.list(0, null)).hasSize(200);
        assertThat(documents.list(1, null)).hasSize(1);
        assertThat(documents.list(0, Documents.Kind.ADR)).isEmpty();
        assertThat(
            request("GET", "/workspace/documents?page=5", "").statusCode()
        ).isEqualTo(400);
    }

    @Test
    void newModulesRequireAuthenticationAndPreviewAcceptsItsMinimalContract()
        throws Exception {
        for (String path : List.of(
            "/repository/configuration",
            "/assistant/configuration",
            "/workspace/documents",
            "/observability/traces"
        )) {
            var response = client.send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api" + path)
                )
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(401);
        }
        // No index in this test context: 409 proves the minimal question DTO reached the domain boundary.
        assertThat(
            request(
                "POST",
                "/assistant/preview",
                "{\"question\":\"authorize\"}"
            ).statusCode()
        ).isEqualTo(409);
    }

    @Test
    void boundsRevisionHistoryWithoutChangingTheCurrentVersion() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 23; i++) documents.save(
            id,
            new Documents.Write(
                Documents.Kind.LEARNING,
                "Practice " + i,
                json.valueToTree(Map.of("Evidence", "test")),
                i
            )
        );
        assertThat(documents.revisions(id)).hasSize(20);
        assertThat(documents.list().getFirst().version()).isEqualTo(23);
    }

    @Test
    void signedLoopbackWebhooksDeduplicateThreeRealHttpDeliveriesAndRejectBadSignature()
        throws Exception {
        jdbc.update("UPDATE worker_control SET running=false WHERE id=1");
        var event = experiments.create(
            UUID.randomUUID().toString(),
            new dev.atlas.eventing.DeliveryModels.ExperimentRequest(
                dev.atlas.eventing.DeliveryModels.Fault.NONE
            )
        );
        var delivered = request(
            "POST",
            "/webhooks/demo/" + event.eventId(),
            ""
        );
        assertThat(delivered.statusCode()).isEqualTo(200);
        var body = json.readTree(delivered.body());
        assertThat(body.get("effects").asInt()).isEqualTo(1);
        assertThat(body.get("deliveries").size()).isEqualTo(3);
        assertThat(
            body.get("deliveries").get(0).get("duplicate").asBoolean()
        ).isFalse();
        assertThat(
            body.get("deliveries").get(2).get("duplicate").asBoolean()
        ).isTrue();
        var bad = client.send(
            HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" +
                        port +
                        "/api/webhooks/receive/" +
                        event.eventId()
                )
            )
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header(
                    "X-Atlas-Timestamp",
                    Long.toString(java.time.Instant.now().getEpochSecond())
                )
                .header("X-Atlas-Signature", "0".repeat(64))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(bad.statusCode()).isEqualTo(401);
    }

    @Test
    void actualHttpAndJdbcSpansShareTraceAndLogCorrelation() throws Exception {
        var response = request("GET", "/payments?page=0&size=1", "");
        assertThat(response.statusCode()).isEqualTo(200);
        String trace = response
            .headers()
            .firstValue("X-Trace-ID")
            .orElseThrow();
        var spans = telemetry
            .traces()
            .stream()
            .filter(s -> s.traceId().equals(trace))
            .toList();
        assertThat(spans).anyMatch(s ->
            s.name().equals("HTTP GET /api/payments")
        );
        assertThat(spans).anyMatch(s -> s.name().equals("db.query"));
        assertThat(spans).allSatisfy(s ->
            assertThat(s.correlationId()).isEqualTo(
                response.headers().firstValue("X-Correlation-ID").orElseThrow()
            )
        );
    }

    @ParameterizedTest
    @EnumSource(IncidentService.Scenario.class)
    void incidentRecipesMeasureBaselineAndAffectedWorkAndPersistEvidence(
        IncidentService.Scenario scenario
    ) {
        var result = incidents.run(scenario);
        assertThat(result.baseline()).hasSize(3);
        assertThat(result.affected()).hasSize(3);
        assertThat(result.affected()).allSatisfy(s ->
            assertThat(s.durationMs()).isGreaterThan(3)
        );
        assertThat(documents.list()).anyMatch(
            d ->
                d.id().equals(result.id()) &&
                d.kind() == Documents.Kind.INCIDENT
        );
        if (
            scenario == IncidentService.Scenario.LOCK_CONTENTION ||
            scenario == IncidentService.Scenario.DOWNSTREAM_TIMEOUT
        ) assertThat(result.affected()).allSatisfy(s ->
            assertThat(s.outcome()).isEqualTo("CONTROLLED_FAILURE")
        );
    }
}
