package dev.atlas.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TelemetryTest {

    @Test
    void fixedOperationSummariesHaveIndependentWindowsPercentilesAndLifetimeErrors() {
        var telemetry = new Telemetry();
        try {
            for (int i = 1; i <= 150; i++) telemetry.export(
                List.of(
                    fixture(
                        "HTTP POST /api/payments/{id}/capture",
                        i,
                        i,
                        i == 1
                    )
                )
            );
            telemetry.export(
                List.of(
                    fixture(
                        "HTTP POST /api/payments/{id}/refund",
                        300,
                        900,
                        true
                    )
                )
            );
            var results = endpoints(telemetry);
            var capture = results
                .stream()
                .filter(row -> row.operation().endsWith("/capture"))
                .findFirst()
                .orElseThrow();
            assertThat(capture.requests()).isEqualTo(150);
            assertThat(capture.errors()).isEqualTo(1);
            assertThat(capture.errorRate()).isEqualTo(1d / 150);
            assertThat(capture.latencySamples()).isEqualTo(100);
            assertThat(capture.p50Ms()).isEqualTo(100);
            assertThat(capture.p95Ms()).isEqualTo(145);
            assertThat(capture.p99Ms()).isEqualTo(149);
            assertThat(capture.windowStart()).isEqualTo(
                Instant.ofEpochSecond(51)
            );
            assertThat(capture.windowEnd()).isEqualTo(
                Instant.ofEpochSecond(150)
            );
            var refund = results
                .stream()
                .filter(row -> row.operation().endsWith("/refund"))
                .findFirst()
                .orElseThrow();
            assertThat(refund.latencySamples()).isEqualTo(1);
            assertThat(refund.p95Ms()).isEqualTo(900);
            assertThat(refund.errorRate()).isEqualTo(1);
            assertThat(telemetry.summary())
                .containsEntry("requests", 151L)
                .containsEntry("latencySamples", 151);
        } finally {
            telemetry.destroyProvider();
        }
    }

    @Test
    void endpointVocabularyAndPerOperationRetentionBoundAllSamplesAndUnknownLabels() {
        var telemetry = new Telemetry();
        try {
            assertThat(endpoints(telemetry)).isEmpty();
            for (String label : HttpOperations.LABELS) {
                for (int i = 0; i < 101; i++) telemetry.export(
                    List.of(fixture(label, i, 1, false))
                );
            }
            for (int i = 0; i < 100; i++) telemetry.export(
                List.of(
                    fixture("HTTP SECRET /api/private-" + i, 200 + i, 2, true)
                )
            );
            telemetry.export(List.of(fixture("db.query", 1, 5000, true)));
            var results = endpoints(telemetry);
            assertThat(results).hasSize(92);
            assertThat(results)
                .extracting(Telemetry.Endpoint::operation)
                .containsExactlyElementsOf(
                    new TreeSet<>(HttpOperations.LABELS)
                );
            assertThat(results).allSatisfy(row ->
                assertThat(row.latencySamples()).isEqualTo(100)
            );
            assertThat(
                results
                    .stream()
                    .mapToInt(Telemetry.Endpoint::latencySamples)
                    .sum()
            ).isEqualTo(9200);
            var unknown = results
                .stream()
                .filter(row -> row.operation().equals(HttpOperations.UNKNOWN))
                .findFirst()
                .orElseThrow();
            assertThat(unknown.requests()).isEqualTo(201);
            assertThat(unknown.errors()).isEqualTo(100);
            assertThat(unknown.p95Ms()).isEqualTo(2);
            assertThat(telemetry.summary().get("endpointLimits")).isEqualTo(
                Map.of(
                    "operations",
                    92,
                    "samplesPerOperation",
                    100,
                    "totalSamples",
                    9200
                )
            );
            assertThat(telemetry.summary())
                .containsEntry("requests", 9392L)
                .containsEntry("latencySamples", 500);
        } finally {
            telemetry.destroyProvider();
        }
    }

    @Test
    void retainedWindowUsesSampleStartTimesWhenConcurrentSpansCompleteOutOfOrder() {
        var telemetry = new Telemetry();
        try {
            telemetry.export(
                List.of(
                    fixture("HTTP POST /api/payments", 20, 3, false),
                    fixture("HTTP POST /api/payments", 10, 7, false)
                )
            );
            var row = endpoints(telemetry).getFirst();
            assertThat(row.windowStart()).isEqualTo(Instant.ofEpochSecond(10));
            assertThat(row.windowEnd()).isEqualTo(Instant.ofEpochSecond(20));
            assertThat(row.p50Ms()).isEqualTo(3);
            assertThat(row.p95Ms()).isEqualTo(7);
        } finally {
            telemetry.destroyProvider();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Telemetry.Endpoint> endpoints(Telemetry telemetry) {
        return (List<Telemetry.Endpoint>) telemetry.summary().get("endpoints");
    }

    private SpanData fixture(
        String name,
        long startSeconds,
        long milliseconds,
        boolean error
    ) {
        var span = mock(SpanData.class);
        when(span.getName()).thenReturn(name);
        when(span.getStartEpochNanos()).thenReturn(
            startSeconds * 1_000_000_000L
        );
        when(span.getEndEpochNanos()).thenReturn(
            startSeconds * 1_000_000_000L + milliseconds * 1_000_000L
        );
        when(span.getStatus()).thenReturn(
            error ? StatusData.error() : StatusData.unset()
        );
        when(span.getAttributes()).thenReturn(Attributes.empty());
        return span;
    }

    @Test
    void independentSampleRootsKeepChildrenTogetherWithoutInheritingCallerTrace() {
        var telemetry = new Telemetry();
        try {
            var caller = telemetry.start("caller");
            try (var parent = caller.makeCurrent()) {
                var sample = telemetry.startRoot("incident.sample");
                try (var scope = sample.makeCurrent()) {
                    var child = telemetry.start("db.query");
                    assertThat(child.getSpanContext().getTraceId()).isEqualTo(
                        sample.getSpanContext().getTraceId()
                    );
                    child.end();
                } finally {
                    sample.end();
                }
                assertThat(sample.getSpanContext().getTraceId()).isNotEqualTo(
                    caller.getSpanContext().getTraceId()
                );
            } finally {
                caller.end();
            }
        } finally {
            telemetry.destroyProvider();
        }
    }

    @Test
    void boundedSpanAndLatencyRetentionDoesNotLoseLifetimeCounters() {
        var telemetry = new Telemetry();
        try {
            for (int i = 0; i < 1100; i++) {
                var span = telemetry.start("HTTP GET /api/payments");
                if (i == 0) span.setStatus(StatusCode.ERROR);
                span.end();
            }
            assertThat(telemetry.traces()).hasSize(1000);
            assertThat(telemetry.summary())
                .containsEntry("requests", 1100L)
                .containsEntry("latencySamples", 500);
            assertThat((Double) telemetry.summary().get("errorRate")).isEqualTo(
                1d / 1100
            );
        } finally {
            telemetry.destroyProvider();
        }
    }
}
