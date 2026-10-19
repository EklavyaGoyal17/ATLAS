package dev.atlas.eventing;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationUpgradeTest {

    @Test
    void versionTwoPreservesLegacyRowsWithoutInventingHistoricalSnapshots()
        throws Exception {
        String url = System.getenv().getOrDefault(
            "ATLAS_TEST_DB_URL",
            "jdbc:postgresql://localhost:5433/atlas_test"
        );
        if (
            !url.matches(
                "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/atlas_test"
            )
        ) throw new IllegalStateException(
            "Disposable local atlas_test database required"
        );
        String schema =
            "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        if (
            !schema.matches("upgrade_[0-9a-f]{32}")
        ) throw new IllegalStateException("Invalid isolated test schema");
        try (
            var connection = DriverManager.getConnection(
                url,
                "atlas",
                "atlas-local-only"
            )
        ) {
            try {
                Flyway.configure()
                    .dataSource(url, "atlas", "atlas-local-only")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("1")
                    .load()
                    .migrate();
                UUID payment = UUID.randomUUID();
                try (
                    var insert = connection.prepareStatement(
                        "INSERT INTO " +
                            schema +
                            ".payment(id,merchant_id,amount_minor,currency,status) VALUES (?,'merchant_demo',123,'USD','AUTHORIZED')"
                    )
                ) {
                    insert.setObject(1, payment);
                    insert.executeUpdate();
                }
                try (
                    var insert = connection.prepareStatement(
                        "INSERT INTO " +
                            schema +
                            ".audit_event(id,payment_id,type,detail,correlation_id) VALUES (?,?,'AUTHORIZED','Legacy event',?)"
                    )
                ) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, payment);
                    insert.setObject(3, UUID.randomUUID());
                    insert.executeUpdate();
                }
                var result = Flyway.configure()
                    .dataSource(url, "atlas", "atlas-local-only")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("2")
                    .load()
                    .migrate();
                assertThat(result.migrationsExecuted).isEqualTo(1);
                try (
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                        "SELECT (SELECT count(*) FROM " +
                            schema +
                            ".payment) payments,(SELECT count(*) FROM " +
                            schema +
                            ".audit_event) audit,(SELECT count(*) FROM " +
                            schema +
                            ".outbox_event) events"
                    )
                ) {
                    rows.next();
                    assertThat(rows.getInt("payments")).isEqualTo(1);
                    assertThat(rows.getInt("audit")).isEqualTo(1);
                    assertThat(rows.getInt("events")).isZero();
                }
                var workspace = Flyway.configure()
                    .dataSource(url, "atlas", "atlas-local-only")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
                assertThat(workspace.migrationsExecuted).isEqualTo(2);
                try (
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                        "SELECT count(*) FROM " + schema + ".workspace_document"
                    )
                ) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            } finally {
                // Remove only this method's validated, freshly-created schema in the disposable database.
                try (var statement = connection.createStatement()) {
                    statement.execute(
                        "DROP SCHEMA IF EXISTS " + schema + " CASCADE"
                    );
                }
            }
        }
    }
}
