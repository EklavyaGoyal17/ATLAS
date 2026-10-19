package dev.atlas.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.*;
import javax.sql.DataSource;

/** One independent PostgreSQL connection, closed after each investigation. */
final class ConnectionPoolProbe implements AutoCloseable {

    private final HikariDataSource pool;
    private final Telemetry telemetry;

    ConnectionPoolProbe(DataSource application, Telemetry telemetry)
        throws SQLException {
        var original = application.unwrap(HikariDataSource.class);
        var config = new HikariConfig();
        config.setJdbcUrl(original.getJdbcUrl());
        config.setUsername(original.getUsername());
        config.setPassword(original.getPassword());
        var properties = new java.util.Properties();
        properties.putAll(original.getDataSourceProperties());
        properties.setProperty("ApplicationName", "atlas-incident-probe");
        config.setDataSourceProperties(properties);
        config.setPoolName("atlas-incident-probe");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(250);
        config.setValidationTimeout(250);
        config.setReadOnly(true);
        this.pool = new HikariDataSource(config);
        this.telemetry = telemetry;
    }

    String sample(boolean fault) throws SQLException {
        if (!fault) {
            query("db.pool.baseline");
            return "OK";
        }
        boolean timedOut = false;
        try (Connection held = pool.getConnection()) {
            var span = telemetry.start("db.pool.acquire");
            try (var scope = span.makeCurrent()) {
                try (Connection unexpected = pool.getConnection()) {
                    throw new IllegalStateException(
                        "Expected the one-slot pool to be exhausted"
                    );
                } catch (SQLTransientConnectionException expected) {
                    span.setStatus(StatusCode.ERROR);
                    timedOut = true;
                }
            } finally {
                span.end();
            }
        }
        if (!timedOut) throw new IllegalStateException("Missing pool timeout");
        // A successful query after releasing the held lease proves recovery.
        query("db.pool.recovered");
        return "POOL_TIMEOUT_RECOVERED";
    }

    private void query(String name) throws SQLException {
        var span = telemetry.start(name);
        try (
            var scope = span.makeCurrent();
            Connection connection = pool.getConnection();
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("SELECT 1")
        ) {
            if (!result.next() || result.getInt(1) != 1) throw new SQLException(
                "Probe failed"
            );
        } catch (SQLException ex) {
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
