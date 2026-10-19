package dev.atlas.observability;

import dev.atlas.platform.ApiException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IncidentService {

    public enum Scenario {
        SLOW_QUERY,
        LOCK_CONTENTION,
        DOWNSTREAM_TIMEOUT,
        CONNECTION_EXHAUSTION,
        EXCESSIVE_RETRIES,
        CACHE_MISSES,
        QUEUE_BACKLOG,
        MISCONFIGURED_TIMEOUT,
    }

    public record Sample(String traceId, double durationMs, String outcome) {}

    public record Investigation(
        UUID id,
        Scenario scenario,
        List<Sample> baseline,
        List<Sample> affected,
        String hypothesis,
        String validation,
        String scope
    ) {}

    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final Telemetry telemetry;
    private final IncidentArchive archive;
    private final Semaphore gate = new Semaphore(1);

    public IncidentService(
        JdbcTemplate jdbc,
        DataSource source,
        Telemetry telemetry,
        IncidentArchive archive
    ) {
        this.jdbc = jdbc;
        this.source = source;
        this.telemetry = telemetry;
        this.archive = archive;
    }

    public Investigation run(Scenario scenario) {
        if (!gate.tryAcquire()) throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS,
            "INCIDENT_BUSY",
            "One bounded incident investigation can run at a time."
        );
        try (
            var probe = scenario == Scenario.CONNECTION_EXHAUSTION
                ? new ConnectionPoolProbe(source, telemetry)
                : null
        ) {
            var baseline = new ArrayList<Sample>();
            var affected = new ArrayList<Sample>();
            var captured = new ArrayList<Telemetry.Trace>();
            for (int i = 0; i < 3; i++) {
                var measured = sample(scenario, false, probe);
                baseline.add(measured);
                capture(measured, captured);
            }
            for (int i = 0; i < 3; i++) {
                var measured = sample(scenario, true, probe);
                affected.add(measured);
                capture(measured, captured);
            }
            String cause = switch (scenario) {
                case SLOW_QUERY -> "The controlled PostgreSQL query waits 80 ms using pg_sleep.";
                case LOCK_CONTENTION -> "A second PostgreSQL transaction waits on a held advisory lock until its 100 ms statement deadline.";
                case DOWNSTREAM_TIMEOUT -> "A local synthetic dependency takes 80 ms; the caller deadline is 20 ms.";
                case CONNECTION_EXHAUSTION -> "A separate one-connection Hikari/PostgreSQL pool holds its only connection; a second acquire times out after 250 ms. Releasing the lease allows a successful SELECT 1, proving recovery. The application's pool remains available.";
                case EXCESSIVE_RETRIES -> "Three failed local dependency attempts add work and backoff to one request.";
                case CACHE_MISSES -> "Invalidating the local cache repeats the controlled 40 ms PostgreSQL lookup.";
                case QUEUE_BACKLOG -> "Six local queued jobs require sequential 15 ms dependency calls before the queue drains.";
                case MISCONFIGURED_TIMEOUT -> "A 5 ms caller deadline rejects a healthy 25 ms local synthetic dependency.";
            };
            var result = new Investigation(
                UUID.randomUUID(),
                scenario,
                baseline,
                affected,
                cause,
                "Compare the three baseline samples with the three affected samples; inspect child spans and their ERROR statuses. Re-run without injection to validate recovery.",
                "Controlled synthetic workload, measured wall-clock durations; isolated from payment state. Local dependency delays are simulated; db.query spans execute PostgreSQL. Not production telemetry."
            );
            archive.save(result, captured);
            return result;
        } catch (SQLException ex) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "INCIDENT_SETUP_FAILED",
                "Could not initialize the isolated PostgreSQL probe."
            );
        } finally {
            gate.release();
        }
    }

    private Sample sample(
        Scenario scenario,
        boolean fault,
        ConnectionPoolProbe probe
    ) {
        var root = telemetry.startRoot(
            "incident." +
                scenario.name().toLowerCase() +
                (fault ? ".affected" : ".baseline")
        );
        long start = System.nanoTime();
        String outcome = "OK";
        try (var scope = root.makeCurrent()) {
            if (probe != null) {
                outcome = probe.sample(fault);
                if (fault) root.setStatus(
                    io.opentelemetry.api.trace.StatusCode.ERROR
                );
            } else if (!fault) {
                jdbc.queryForObject("SELECT 1", Integer.class);
            } else switch (scenario) {
                case SLOW_QUERY -> jdbc.execute("SELECT pg_sleep(0.08)");
                case CACHE_MISSES -> {
                    var cache = new HashMap<String, Integer>();
                    if (!cache.containsKey("merchant")) {
                        jdbc.execute("SELECT pg_sleep(0.04)");
                        cache.put("merchant", 1);
                    }
                }
                case LOCK_CONTENTION -> contention();
                case DOWNSTREAM_TIMEOUT -> deadline(80, 20);
                case MISCONFIGURED_TIMEOUT -> deadline(25, 5);
                case CONNECTION_EXHAUSTION -> throw new IllegalStateException(
                    "Missing isolated pool"
                );
                case EXCESSIVE_RETRIES -> {
                    for (int i = 0; i < 3; i++) {
                        try {
                            deadline(25, 10);
                        } catch (RuntimeException ignored) {
                            pause(5L << i);
                        }
                    }
                    throw new IllegalStateException("Exhausted retries");
                }
                case QUEUE_BACKLOG -> {
                    var queue = new ArrayDeque<Integer>(
                        List.of(1, 2, 3, 4, 5, 6)
                    );
                    while (!queue.isEmpty()) {
                        queue.remove();
                        telemetry.measure("queue.consume", () -> {
                            pause(15);
                            return 1;
                        });
                    }
                }
            }
        } catch (Exception ex) {
            root.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            outcome = "CONTROLLED_FAILURE";
        } finally {
            root.end();
        }
        return new Sample(
            root.getSpanContext().getTraceId(),
            (System.nanoTime() - start) / 1e6,
            outcome
        );
    }

    private void capture(Sample measured, List<Telemetry.Trace> captured) {
        telemetry
            .traces()
            .stream()
            .filter(s -> s.traceId().equals(measured.traceId()))
            .forEach(captured::add);
    }

    private void contention() throws SQLException {
        try (
            Connection first = source.getConnection();
            Connection second = source.getConnection()
        ) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                first
                    .createStatement()
                    .execute("SELECT pg_advisory_xact_lock(818104)");
                second
                    .createStatement()
                    .execute("SET LOCAL statement_timeout=100");
                second
                    .createStatement()
                    .execute("SELECT pg_advisory_xact_lock(818104)");
            } finally {
                first.rollback();
                second.rollback();
            }
        }
    }

    private void deadline(int workMs, int deadlineMs) {
        telemetry.measure("dependency.synthetic.deadline", () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> future = executor.submit(() -> pause(workMs));
                try {
                    future.get(deadlineMs, TimeUnit.MILLISECONDS);
                    return 1;
                } catch (Exception e) {
                    future.cancel(true);
                    throw new IllegalStateException("Controlled deadline");
                }
            }
        });
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
