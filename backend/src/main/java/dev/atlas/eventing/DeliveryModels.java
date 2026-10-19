package dev.atlas.eventing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryModels {

    private DeliveryModels() {}

    public enum Fault {
        NONE,
        CRASH_BEFORE_COMMIT_ONCE,
        CRASH_AFTER_COMMIT_ONCE,
        ALWAYS_FAIL,
    }

    public record Payload(
        int schemaVersion,
        UUID paymentId,
        String eventType,
        String status,
        String currency,
        long amountMinor,
        long capturedMinor,
        long refundedMinor,
        long paymentVersion,
        UUID correlationId
    ) {}

    public record Event(
        UUID eventId,
        UUID paymentId,
        String eventType,
        Payload payload,
        String status,
        Fault fault,
        int attempts,
        long nextTick,
        Long leaseUntilTick,
        long effects,
        long duplicatesSuppressed,
        Instant createdAt
    ) {}

    public record Timeline(
        long sequence,
        long tick,
        int attempt,
        String kind,
        String detail,
        Instant createdAt
    ) {}

    public record Detail(Event event, List<Timeline> timeline) {}

    public record Page(List<Event> items, int page, int size, long total) {}

    public record Control(
        long tick,
        boolean running,
        long pending,
        long processing,
        long retry,
        long delivered,
        long dead,
        long effects
    ) {}

    public record Step(UUID eventId, @Min(1) @Max(16) int advanceTicks) {}

    public record Run(@NotNull Boolean running) {}

    public record StepResult(
        long tick,
        UUID eventId,
        String outcome,
        String detail
    ) {}

    public record ExperimentRequest(@NotNull Fault scenario) {}

    public record Experiment(
        UUID id,
        UUID eventId,
        Fault scenario,
        boolean replayed
    ) {}

    public record Claim(
        UUID eventId,
        UUID leaseToken,
        int attempt,
        Fault fault,
        long tick
    ) {}
}
