package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RepositoryImpactIntegrationTest {

    static final String TOKEN = "atlas-impact-test-at-least-32-characters";

    @TempDir
    static Path root;

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
        p.add("atlas.repository-root", () -> root.toString());
    }

    @Autowired
    RepositoryIndex index;

    @Autowired
    JsonMapper json;

    @LocalServerPort
    int port;

    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void fixture() throws Exception {
        index.clear();
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(
            root.resolve("Store.java"),
            "package fixture; class Store {}\n"
        );
        Files.writeString(
            root.resolve("Service.java"),
            "package fixture; class Service {\n Store store;\n}\n"
        );
        Files.writeString(
            root.resolve("Endpoint.java"),
            "package fixture; class Endpoint { Service service; }\n"
        );
        Files.writeString(
            root.resolve("guide.md"),
            "# Searchable text, without analyzed Java declarations\n"
        );
    }

    HttpResponse<String> request(String path, Object body, boolean authorized)
        throws Exception {
        var builder = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/api" + path)
        )
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(body)
                )
            );
        if (authorized) builder.header("Authorization", "Bearer " + TOKEN);
        return client.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    void requiresAuthenticationAndAnApprovedSnapshot() throws Exception {
        var body = Map.of(
            "snapshotId",
            UUID.randomUUID(),
            "paths",
            List.of("Store.java")
        );
        assertThat(
            request("/repository/impact", body, false).statusCode()
        ).isEqualTo(401);
        var absent = request("/repository/impact", body, true);
        assertThat(absent.statusCode()).isEqualTo(409);
        assertThat(
            json.readTree(absent.body()).get("code").asString()
        ).isEqualTo("NO_INDEX");
    }

    @Test
    void bindsSourceEvidenceToSnapshotAndRejectsReimportAndDeletion()
        throws Exception {
        var imported = request(
            "/repository/import",
            Map.of("path", ".", "approved", true),
            true
        );
        assertThat(imported.statusCode()).isEqualTo(200);
        UUID snapshot = UUID.fromString(
            json.readTree(imported.body()).get("id").asString()
        );
        var body = Map.of(
            "snapshotId",
            snapshot,
            "paths",
            List.of("Store.java", "guide.md")
        );
        var response = request("/repository/impact", body, true);
        assertThat(response.statusCode()).isEqualTo(200);
        var report = json.readTree(response.body());
        assertThat(report.get("snapshotId").asString()).isEqualTo(
            snapshot.toString()
        );
        assertThat(report.get("totalAffected").asInt()).isEqualTo(2);
        assertThat(report.get("unrepresentedPaths").toString()).contains(
            "guide.md"
        );
        var direct = report.get("affected").get(0);
        assertThat(direct.get("type").get("name").asString()).isEqualTo(
            "fixture.Service"
        );
        assertThat(direct.get("distance").asInt()).isEqualTo(1);
        assertThat(direct.get("path").get(0).get("path").asString()).isEqualTo(
            "Service.java"
        );
        assertThat(direct.get("path").get(0).get("line").asInt()).isEqualTo(2);
        assertThat(
            report.get("affected").get(1).get("distance").asInt()
        ).isEqualTo(2);
        index.index(".", true);
        var stale = request("/repository/impact", body, true);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(
            json.readTree(stale.body()).get("code").asString()
        ).isEqualTo("STALE_SNAPSHOT");
        index.clear();
        assertThat(
            request("/repository/impact", body, true).statusCode()
        ).isEqualTo(409);
    }

    @Test
    void validatesRequestAndExactIndexedPathMembership() throws Exception {
        var snapshot = index.index(".", true);
        for (Object body : List.of(
            Map.of("paths", List.of("Store.java")),
            Map.of("snapshotId", "invalid", "paths", List.of("Store.java")),
            Map.of("snapshotId", snapshot.id(), "paths", List.of()),
            Map.of("snapshotId", snapshot.id(), "paths", List.of(" ")),
            Map.of(
                "snapshotId",
                snapshot.id(),
                "paths",
                Collections.nCopies(21, "Store.java")
            ),
            Map.of(
                "snapshotId",
                snapshot.id(),
                "paths",
                List.of("a".repeat(301))
            ),
            Map.of(
                "snapshotId",
                snapshot.id(),
                "paths",
                List.of("Store.java"),
                "unexpected",
                true
            )
        ))
            assertThat(
                request("/repository/impact", body, true).statusCode()
            ).isEqualTo(400);
        for (String path : List.of(
            "../Store.java",
            "./Store.java",
            "store.java",
            "/Store.java",
            "missing.java"
        )) {
            var response = request(
                "/repository/impact",
                Map.of("snapshotId", snapshot.id(), "paths", List.of(path)),
                true
            );
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(
                json.readTree(response.body()).get("code").asString()
            ).isEqualTo("INVALID_IMPACT_PATH");
        }
    }
}
