package dev.atlas.eventing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void retryScheduleIsBoundedExponentialBackoff() {
        assertThat(RetryPolicy.delay(1)).isEqualTo(1);
        assertThat(RetryPolicy.delay(2)).isEqualTo(2);
        assertThat(RetryPolicy.delay(3)).isEqualTo(4);
        assertThatThrownBy(() -> RetryPolicy.delay(0)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> RetryPolicy.delay(4)).isInstanceOf(
            IllegalArgumentException.class
        );
    }
}
