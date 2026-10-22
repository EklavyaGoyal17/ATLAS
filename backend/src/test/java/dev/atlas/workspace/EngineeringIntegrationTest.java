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
    IncidentArchive archive;

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
                "{\"Context\":\"Synthetic architecture\",\"Decision\":\"Use a monolith\",\"Alternatives\":\"Separate services\",\"Tradeoffs\":\"One deployment boundary\",\"Consequences\":\"Shared database\",\"Review date\":\"2026-12-01\"}"
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
    void incidentCopilotSummarizesSavedEvidenceWithAuthenticationAndDeletion()
        throws Exception {
        var investigation = incidents.run(IncidentService.Scenario.SLOW_QUERY);
        String path = "/assistant/incidents/" + investigation.id() + "/summary";
        var unauthenticated = client.send(
            HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api" + path)
            )
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        var response = request("GET", path, "");
        assertThat(response.statusCode()).isEqualTo(200);
        var summary = json.readTree(response.body());
        assertThat(summary.get("id").asString()).isEqualTo(
            investigation.id().toString()
        );
        assertThat(summary.get("mode").asString()).isEqualTo(
            "DETERMINISTIC_SAVED_EVIDENCE"
        );
        assertThat(summary.get("baseline").get("samples").asInt()).isEqualTo(3);
        assertThat(summary.get("affected").get("samples").asInt()).isEqualTo(3);
        assertThat(summary.get("retainedSpans").asInt()).isEqualTo(
            archive.get(investigation.id()).spans().size()
        );
        assertThat(
            summary.get("slowestChild").get("name").asString()
        ).isEqualTo("db.query");
        assertThat(response.body()).doesNotContain(investigation.hypothesis());
        request("DELETE", "/workspace/documents/" + investigation.id(), "");
        assertThat(request("GET", path, "").statusCode()).isEqualTo(404);
        assertThat(
            request(
                "GET",
                "/assistant/incidents/not-a-uuid/summary",
                ""
            ).statusCode()
        ).isEqualTo(400);
    }

    @Test
    void incompleteLegacyAdrsRemainReadableButNewWritesMustBeComplete()
        throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO workspace_document(id,kind,title,version,body_json) VALUES(?,'ADR','Legacy decision',1,'{}'::jsonb)",
            id
        );
        assertThat(documents.list(0, Documents.Kind.ADR))
            .singleElement()
            .satisfies(doc -> {
                assertThat(doc.id()).isEqualTo(id);
                assertThat(doc.body().size()).isZero();
            });
        for (String body : List.of("{}", "{\"Context\":\"Only one field\"}")) {
            var response = request(
                "PUT",
                "/workspace/documents/" + id,
                json.writeValueAsString(
                    new Documents.Write(
                        Documents.Kind.ADR,
                        "Incomplete edit",
                        json.readTree(body),
                        1
                    )
                )
            );
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(
                json.readTree(response.body()).get("code").asString()
            ).isEqualTo("INVALID_ADR");
        }
        var preserved = documents.list(0, Documents.Kind.ADR).getFirst();
        assertThat(preserved.title()).isEqualTo("Legacy decision");
        assertThat(preserved.version()).isEqualTo(1);
        assertThat(preserved.body().size()).isZero();
        assertThat(documents.revisions(id)).isEmpty();
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workspace_audit",
                Long.class
            )
        ).isZero();
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
            "/repository/architecture",
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
            request("GET", "/repository/architecture", "").statusCode()
        ).isEqualTo(409);
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
        var evidence = archive.get(result.id());
        assertThat(evidence.available()).isTrue();
        assertThat(evidence.truncated()).isFalse();
        assertThat(evidence.investigation()).isEqualTo(result);
        assertThat(evidence.spans()).isNotEmpty();
        assertThat(
            evidence.spans().stream().map(Telemetry.Trace::traceId).distinct()
        ).hasSize(6);
        if (
            scenario == IncidentService.Scenario.LOCK_CONTENTION ||
            scenario == IncidentService.Scenario.DOWNSTREAM_TIMEOUT
        ) assertThat(result.affected()).allSatisfy(s ->
            assertThat(s.outcome()).isEqualTo("CONTROLLED_FAILURE")
        );
        if (scenario == IncidentService.Scenario.CONNECTION_EXHAUSTION) {
            assertThat(result.baseline()).allSatisfy(s ->
                assertThat(s.outcome()).isEqualTo("OK")
            );
            assertThat(result.affected()).allSatisfy(s -> {
                assertThat(s.outcome()).isEqualTo("POOL_TIMEOUT_RECOVERED");
                assertThat(s.durationMs()).isGreaterThanOrEqualTo(240);
                var spans = telemetry
                    .traces()
                    .stream()
                    .filter(t -> t.traceId().equals(s.traceId()))
                    .toList();
                assertThat(spans).anyMatch(
                    t ->
                        t.name().equals("db.pool.acquire") &&
                        t.status().equals("ERROR")
                );
                assertThat(spans).anyMatch(
                    t ->
                        t.name().equals("db.pool.recovered") &&
                        !t.status().equals("ERROR")
                );
            });
            assertThat(
                jdbc.queryForObject("SELECT 1", Integer.class)
            ).isEqualTo(1);
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'atlas-incident-probe'",
                    Integer.class
                )
            ).isZero();
        }
    }

    @Test
    void savedIncidentSpansSurviveLiveEvictionAndDeleteWithTheirDocument()
        throws Exception {
        var response = request(
            "POST",
            "/observability/incidents",
            "{\"scenario\":\"SLOW_QUERY\"}"
        );
        assertThat(response.statusCode()).isEqualTo(200);
        var investigation = json.readValue(
            response.body(),
            IncidentService.Investigation.class
        );
        String path =
            "/observability/incidents/" + investigation.id() + "/evidence";
        var first = request("GET", path, "");
        assertThat(first.statusCode()).isEqualTo(200);
        var saved = json.readValue(
            first.body(),
            IncidentArchive.Evidence.class
        );
        assertThat(
            saved.spans().stream().map(Telemetry.Trace::traceId).distinct()
        ).hasSize(6);
        assertThat(saved.spans()).allSatisfy(s ->
            assertThat(s.correlationId()).isEqualTo(
                response.headers().firstValue("X-Correlation-ID").orElseThrow()
            )
        );
        for (int i = 0; i < 1100; i++) telemetry.start("evict.fixture").end();
        assertThat(telemetry.traces()).noneMatch(s ->
            saved.spans().contains(s)
        );
        assertThat(
            json.readValue(
                request("GET", path, "").body(),
                IncidentArchive.Evidence.class
            )
        ).isEqualTo(saved);
        assertThat(
            request(
                "DELETE",
                "/workspace/documents/" + investigation.id(),
                ""
            ).statusCode()
        ).isEqualTo(200);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM incident_evidence WHERE incident_id=?",
                Integer.class,
                investigation.id()
            )
        ).isZero();
        assertThat(request("GET", path, "").statusCode()).isEqualTo(404);
    }

    @Test
    void legacyEvidenceIsExplicitAndArchiveReadsRequireAuthorization()
        throws Exception {
        var result = fixtureInvestigation(UUID.randomUUID());
        documents.save(
            result.id(),
            new Documents.Write(
                Documents.Kind.INCIDENT,
                "Legacy investigation",
                json.valueToTree(result),
                0
            )
        );
        String path = "/observability/incidents/" + result.id() + "/evidence";
        var legacy = json.readValue(
            request("GET", path, "").body(),
            IncidentArchive.Evidence.class
        );
        assertThat(legacy.available()).isFalse();
        assertThat(legacy.spans()).isEmpty();
        assertThat(legacy.scope()).contains("cannot be reconstructed");
        assertThat(
            client
                .send(
                    HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api" + path)
                    )
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                .statusCode()
        ).isEqualTo(401);
        assertThat(
            request(
                "GET",
                "/observability/incidents/" + UUID.randomUUID() + "/evidence",
                ""
            ).statusCode()
        ).isEqualTo(404);
        assertThat(
            request(
                "GET",
                "/observability/incidents/not-a-uuid/evidence",
                ""
            ).statusCode()
        ).isEqualTo(400);
        assertThat(
            request(
                "PUT",
                "/workspace/documents/" + result.id(),
                json.writeValueAsString(
                    new Documents.Write(
                        Documents.Kind.INCIDENT,
                        "Attempted edit",
                        json.valueToTree(result),
                        1
                    )
                )
            ).statusCode()
        ).isEqualTo(400);
    }

    @Test
    void archivedSpansRespectCountAndByteBudgetsAndExcludeOtherTraces() {
        var result = fixtureInvestigation(UUID.randomUUID());
        var spans = new ArrayList<Telemetry.Trace>();
        for (int i = 0; i < 140; i++) spans.add(
            fixtureSpan("a".repeat(32), String.format("%016x", i), "db.query")
        );
        spans.add(fixtureSpan("b".repeat(32), "other", "unrelated"));
        archive.save(result, spans);
        var evidence = archive.get(result.id());
        assertThat(evidence.spans())
            .hasSize(128)
            .noneMatch(s -> s.name().equals("unrelated"));
        assertThat(evidence.truncated()).isTrue();
        var large = fixtureInvestigation(UUID.randomUUID());
        archive.save(
            large,
            List.of(fixtureSpan("a".repeat(32), "oversize", "x".repeat(140000)))
        );
        assertThat(archive.get(large.id()).spans()).isEmpty();
        assertThat(archive.get(large.id()).truncated()).isTrue();
        assertThat(
            jdbc.queryForObject(
                "SELECT max(octet_length(evidence_json)) FROM incident_evidence",
                Integer.class
            )
        ).isLessThanOrEqualTo(128000);
        assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE incident_evidence SET evidence_json=repeat('x',128001) WHERE incident_id=?",
                result.id()
            )
        ).isInstanceOf(
            org.springframework.dao.DataIntegrityViolationException.class
        );
    }

    @Test
    void evidenceInsertFailureRollsBackInvestigationRevisionsAndAudit() {
        var result = fixtureInvestigation(UUID.randomUUID());
        jdbc.execute(
            "ALTER TABLE incident_evidence ADD CONSTRAINT atlas_test_reject_evidence CHECK (incident_id <> '" +
                result.id() +
                "'::uuid) NOT VALID"
        );
        try {
            assertThatThrownBy(() ->
                archive.save(result, List.of())
            ).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class
            );
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_document WHERE id=?",
                    Integer.class,
                    result.id()
                )
            ).isZero();
            assertThat(documents.revisions(result.id())).isEmpty();
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_audit WHERE target=?",
                    Integer.class,
                    result.id().toString()
                )
            ).isZero();
        } finally {
            jdbc.execute(
                "ALTER TABLE incident_evidence DROP CONSTRAINT atlas_test_reject_evidence"
            );
        }
    }

    private IncidentService.Investigation fixtureInvestigation(UUID id) {
        return new IncidentService.Investigation(
            id,
            IncidentService.Scenario.SLOW_QUERY,
            List.of(new IncidentService.Sample("a".repeat(32), 1, "OK")),
            List.of(),
            "Synthetic fixture",
            "Not measured",
            "Test only"
        );
    }

    private Telemetry.Trace fixtureSpan(
        String trace,
        String span,
        String name
    ) {
        return new Telemetry.Trace(
            trace,
            span,
            "0".repeat(16),
            name,
            1,
            "UNSET",
            "background",
            java.time.Instant.now()
        );
    }
}
