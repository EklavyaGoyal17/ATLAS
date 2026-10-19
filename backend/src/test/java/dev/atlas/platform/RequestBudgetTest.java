package dev.atlas.platform;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RequestBudgetTest {

    @Test
    void boundsBurstAndRefillsWithElapsedTime() {
        var bucket = new RequestBudget();
        long now = System.nanoTime();
        for (int i = 0; i < 120; i++) assertThat(bucket.allow(now)).isTrue();
        assertThat(bucket.allow(now)).isFalse();
        assertThat(bucket.allow(now + 1_000_000_000L)).isTrue();
    }
}
