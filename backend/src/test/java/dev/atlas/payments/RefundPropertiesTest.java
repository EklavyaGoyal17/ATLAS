package dev.atlas.payments;

import static org.assertj.core.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RefundPropertiesTest {

    @Test
    void generatedPartialRefundSequencesNeverExceedCaptureAndRejectEveryExcess() {
        var random = new Random(629831);
        for (int sample = 0; sample < 5000; sample++) {
            long captured = 1 + random.nextLong(1_000_000_000_000L),
                total = 0;
            for (int step = 0; step < 20 && total < captured; step++) {
                long amount = 1 + random.nextLong(captured - total);
                long previous = total;
                total = PaymentRules.refundTotal(captured, total, amount);
                assertThat(total).isBetween(previous + 1, captured);
                assertThat(total - previous).isEqualTo(amount);
            }
            long refunded = total;
            assertThatThrownBy(() ->
                PaymentRules.refundTotal(
                    captured,
                    refunded,
                    captured - refunded + 1
                )
            ).hasMessageContaining("exceeds");
        }
    }
}
