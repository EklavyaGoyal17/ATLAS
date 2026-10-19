package dev.atlas.payments;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentModels {

    private PaymentModels() {}

    public record Authorization(
        @NotNull @Pattern(regexp = "merchant_demo") String merchantId,
        @Min(1) @Max(1_000_000_000_000L) long amountMinor,
        @NotNull @Pattern(regexp = "USD|EUR|INR") String currency
    ) {}

    public record Refund(@Min(1) @Max(1_000_000_000_000L) long amountMinor) {}

    public record Payment(
        UUID id,
        String merchantId,
        long amountMinor,
        String currency,
        String status,
        long capturedMinor,
        long refundedMinor,
        long version,
        Instant createdAt
    ) {}

    public record Event(
        long sequence,
        String type,
        String detail,
        UUID correlationId,
        Instant createdAt
    ) {}

    public record Posting(
        UUID id,
        String kind,
        String debitAccount,
        String creditAccount,
        long amountMinor,
        String currency,
        Instant createdAt
    ) {}

    public record Detail(
        Payment payment,
        List<Event> events,
        List<Posting> postings
    ) {}

    public record Page(List<Payment> items, int page, int size, long total) {}

    public record Result(Payment payment, boolean replayed) {}

    public record Reconciliation(
        String currency,
        long debitsMinor,
        long creditsMinor,
        long unbalancedPayments,
        long postingCount,
        boolean balanced
    ) {}
}
