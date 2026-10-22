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
    static final int ENDPOINT_SAMPLES = 100;

    private record EndpointSample(double durationMs, Instant startedAt) {}

    public record Endpoint(
        String operation,
        long requests,
        long errors,
        double errorRate,
        int latencySamples,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        Instant windowStart,
        Instant windowEnd
    ) {}

    private static final class EndpointWindow {

        long requests, errors;
        final ArrayDeque<EndpointSample> samples = new ArrayDeque<>();
    }

    private final Map<String, EndpointWindow> endpoints = new TreeMap<>();
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

    public Span startRoot(String name) {
        return tracer
            .spanBuilder(name)
            .setNoParent()
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
                String operation = HttpOperations.knownLabel(s.getName());
                var window = endpoints.computeIfAbsent(operation, key ->
                    new EndpointWindow()
                );
                window.requests++;
                if (
                    s.getStatus().getStatusCode() == StatusCode.ERROR
                ) window.errors++;
                if (
                    window.samples.size() == ENDPOINT_SAMPLES
                ) window.samples.removeFirst();
                window.samples.addLast(
                    new EndpointSample(
                        (s.getEndEpochNanos() - s.getStartEpochNanos()) /
                            1_000_000d,
                        Instant.ofEpochSecond(0, s.getStartEpochNanos())
                    )
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
        var summary = new LinkedHashMap<String, Object>(
            Map.of(
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
            "Last 1000 spans and 500 admitted authenticated HTTP latency samples in this JVM; throughput and error rate use lifetime measured HTTP counters. Health, telemetry polling, unauthorized requests and early body/rate admission rejections are excluded."
            )
        );
        summary.put(
            "endpoints",
            endpoints
                .entrySet()
                .stream()
                .map(entry -> {
                    var window = entry.getValue();
                    var latencies = window.samples
                        .stream()
                        .mapToDouble(EndpointSample::durationMs)
                        .sorted()
                        .toArray();
                    return new Endpoint(
                        entry.getKey(),
                        window.requests,
                        window.errors,
                        window.errors / (double) window.requests,
                        window.samples.size(),
                        percentile(latencies, .5),
                        percentile(latencies, .95),
                        percentile(latencies, .99),
                        window.samples
                            .stream()
                            .map(EndpointSample::startedAt)
                            .min(Instant::compareTo)
                            .orElseThrow(),
                        window.samples
                            .stream()
                            .map(EndpointSample::startedAt)
                            .max(Instant::compareTo)
                            .orElseThrow()
                    );
                })
                .toList()
        );
        summary.put(
            "endpointLimits",
            Map.of(
                "operations",
                HttpOperations.LABELS.size(),
                "samplesPerOperation",
                ENDPOINT_SAMPLES,
                "totalSamples",
                HttpOperations.LABELS.size() * ENDPOINT_SAMPLES
            )
        );
        summary.put(
            "endpointScope",
            "Fixed HTTP operation templates only; payment authorization, detail, capture, refund and reversal are separate. Other operations use route families. Each operation retains the last 100 completed samples; window bounds are retained sample start times. Counts/error rates use this JVM lifetime since the global since field. Health, observability polling, unauthorized requests and early body/rate admission rejections remain excluded. No capacity or production SLO claim."
        );
        return summary;
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
