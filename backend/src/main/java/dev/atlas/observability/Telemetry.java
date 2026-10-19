package dev.atlas.observability;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Local OTel SDK exporter. No external transport; bounded process-lifetime evidence. */
@Component
public class Telemetry implements SpanExporter {

    public record Trace(
        String traceId,
        String spanId,
        String parentId,
        String name,
        double durationMs,
        String status,
        String correlationId,
        Instant startedAt
    ) {}

    private final ArrayDeque<Trace> recent = new ArrayDeque<>();
    private final ArrayDeque<Double> httpLatencies = new ArrayDeque<>();
    private long httpRequests, httpErrors;
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(this))
        .build();
    private final Tracer tracer = OpenTelemetrySdk.builder()
        .setTracerProvider(provider)
        .build()
        .getTracer("atlas", "1.0");
    private final Instant since = Instant.now();

    public Span start(String name) {
        return tracer
            .spanBuilder(name)
            .startSpan()
            .setAttribute(
                "atlas.correlation_id",
                Optional.ofNullable(org.slf4j.MDC.get("correlationId")).orElse(
                    "background"
                )
            );
    }

    public Span incoming(
        String name,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        var context =
            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance().extract(
                io.opentelemetry.context.Context.current(),
                request,
                new io.opentelemetry.context.propagation.TextMapGetter<jakarta.servlet.http.HttpServletRequest>() {
                    public Iterable<String> keys(
                        jakarta.servlet.http.HttpServletRequest carrier
                    ) {
                        return List.of("traceparent", "tracestate");
                    }

                    public String get(
                        jakarta.servlet.http.HttpServletRequest carrier,
                        String key
                    ) {
                        return carrier.getHeader(key);
                    }
                }
            );
        return tracer
            .spanBuilder(name)
            .setParent(context)
            .startSpan()
            .setAttribute(
                "atlas.correlation_id",
                Optional.ofNullable(org.slf4j.MDC.get("correlationId")).orElse(
                    "background"
                )
            );
    }

    public void inject(java.net.http.HttpRequest.Builder builder) {
        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance().inject(
            io.opentelemetry.context.Context.current(),
            builder,
            (carrier, key, value) -> carrier.header(key, value)
        );
    }

    public <T> T measure(String name, Supplier<T> work) {
        Span span = start(name);
        try (Scope ignored = span.makeCurrent()) {
            return work.get();
        } catch (RuntimeException ex) {
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }

    @Override
    public synchronized CompletableResultCode export(
        Collection<SpanData> spans
    ) {
        for (SpanData s : spans) {
            if (s.getName().startsWith("HTTP ")) {
                httpRequests++;
                if (
                    s.getStatus().getStatusCode() == StatusCode.ERROR
                ) httpErrors++;
                if (httpLatencies.size() == 500) httpLatencies.removeFirst();
                httpLatencies.addLast(
                    (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000d
                );
            }
            if (recent.size() == 1000) recent.removeFirst();
            recent.addLast(
                new Trace(
                    s.getTraceId(),
                    s.getSpanId(),
                    s.getParentSpanId(),
                    s.getName(),
                    (s.getEndEpochNanos() - s.getStartEpochNanos()) /
                        1_000_000d,
                    s.getStatus().getStatusCode().name(),
                    s
                        .getAttributes()
                        .get(
                            io.opentelemetry.api.common.AttributeKey.stringKey(
                                "atlas.correlation_id"
                            )
                        ),
                    Instant.ofEpochSecond(0, s.getStartEpochNanos())
                )
            );
        }
        return CompletableResultCode.ofSuccess();
    }

    public synchronized List<Trace> traces() {
        return recent.reversed().stream().toList();
    }

    public synchronized Map<String, Object> summary() {
        var values = httpLatencies
            .stream()
            .mapToDouble(Double::doubleValue)
            .sorted()
            .toArray();
        return Map.of(
            "since",
            since,
            "retainedSpans",
            recent.size(),
            "requests",
            httpRequests,
            "latencySamples",
            httpLatencies.size(),
            "requestsPerSecond",
            httpRequests /
                Math.max(
                    1d,
                    java.time.Duration.between(
                        since,
                        Instant.now()
                    ).toMillis() / 1000d
                ),
            "errorRate",
            httpRequests == 0 ? 0d : httpErrors / (double) httpRequests,
            "p50Ms",
            percentile(values, .5),
            "p95Ms",
            percentile(values, .95),
            "p99Ms",
            percentile(values, .99),
            "scope",
            "Last 1000 spans and 500 authenticated HTTP latency samples in this JVM; throughput and error rate use lifetime HTTP counters. Health, telemetry polling and unauthorized requests are excluded."
        );
    }

    private double percentile(double[] a, double p) {
        return a.length == 0
            ? 0
            : a[Math.min(a.length - 1, (int) Math.ceil(a.length * p) - 1)];
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @PreDestroy
    void destroyProvider() {
        provider.close();
    }
}
