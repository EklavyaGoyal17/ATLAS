# ADR 007 — Bounded local telemetry and controlled incidents

Context: the personal lab needs genuine request/query timings and inspectable failure evidence without requiring a separate monitoring cluster.

Decision: use OpenTelemetry Java SDK 1.67.0 with a local exporter retaining 1000 spans, 500 HTTP latency samples, lifetime request/error counters and W3C propagation on signed loopback webhook requests. JDBC wrappers record acquisition and query spans without SQL or values. Investigations save three baseline and three affected observations as versioned documents.

Alternatives: automatic agent instrumentation and an OTLP collector would provide broader instrumentation but add runtime setup and expose more data by default. Prometheus/Grafana are deferred because this single-user console can read its bounded local exporter directly.

Tradeoffs: traces reset on JVM restart. Saved investigations persist sample IDs and durations but do not preserve every child span. Incident query delays and advisory-lock waits execute PostgreSQL; dependency deadlines and queued workloads are explicitly synthetic local boundaries. Connection exhaustion now opens an independent Hikari pool with one read-only PostgreSQL connection and a 250 ms acquisition timeout. Holding its lease forces a real second acquisition to fail; releasing it and running SELECT 1 verifies recovery. The pool closes after the investigation, including error paths, and uses one additional connection at most. It does not exhaust the application's ten-slot pool.

Consequences: no external telemetry transmission. No claim of production monitoring, real service fleet tracing, application-pool exhaustion or a verified production SLO. Integration tests verify pool timeout/error and recovery spans, successful application queries and probe connection cleanup. Failure injection does not modify payment state.

Review date: 2027-01-19, or when adding another deployed service.

Sources: [OTel Java SDK](https://opentelemetry.io/docs/languages/java/sdk/), [SDK release and artifacts](https://github.com/open-telemetry/opentelemetry-java).
