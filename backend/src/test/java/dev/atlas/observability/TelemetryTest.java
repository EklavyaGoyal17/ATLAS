package dev.atlas.observability;

import static org.assertj.core.api.Assertions.*;

import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;

class TelemetryTest {

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
