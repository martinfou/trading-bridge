package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarHoldoutSplitTest {

    @Test
    void split_reservesTrailingHoldoutWithImmutablePeriod() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            bars.add(new Bar(
                "EUR_USD",
                Instant.parse("2024-01-01T00:00:00Z").plusSeconds(3600L * i),
                1.10,
                1.11,
                1.09,
                1.105,
                1000));
        }

        BarHoldoutSplit split = BarHoldoutSplit.split(bars, 0.20, 10);

        assertEquals(80, split.inSample().size());
        assertEquals(20, split.holdout().size());
        assertEquals(bars.get(80).timestamp(), split.holdoutStart());
        assertEquals(bars.get(99).timestamp(), split.holdoutEnd());
        assertTrue(split.toMap().containsKey("holdoutStart"));
    }

    @Test
    void split_rejectsTooFewInSampleBars() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            bars.add(new Bar(
                "EUR_USD",
                Instant.parse("2024-01-01T00:00:00Z").plusSeconds(3600L * i),
                1.10,
                1.11,
                1.09,
                1.105,
                1000));
        }

        assertThrows(IllegalArgumentException.class, () -> BarHoldoutSplit.split(bars, 0.20, 50));
    }

    @Test
    void split_rejectsTooFewBars() {
        List<Bar> bars = List.of(new Bar(
            "EUR_USD",
            Instant.parse("2024-01-01T00:00:00Z"),
            1.10,
            1.11,
            1.09,
            1.105,
            1000));

        assertThrows(IllegalArgumentException.class, () -> BarHoldoutSplit.split(bars, 0.20, 50));
    }
}
