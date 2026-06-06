package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BarAggregatorTest {

    @Test
    void testHourlyAggregation() {
        BarAggregator aggregator = new BarAggregator("EUR_USD", "H1");

        // 10:00:00 M1 bar
        Bar b1 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:00:00Z"), 1.0800, 1.0810, 1.0790, 1.0805, 100);
        // 10:30:00 M1 bar
        Bar b2 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:30:00Z"), 1.0805, 1.0830, 1.0780, 1.0795, 200);
        // 10:59:00 M1 bar
        Bar b3 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:59:00Z"), 1.0795, 1.0800, 1.0790, 1.0799, 150);
        // 11:00:00 M1 bar (new hour period starts)
        Bar b4 = new Bar("EUR_USD", Instant.parse("2026-05-20T11:00:00Z"), 1.0800, 1.0820, 1.0795, 1.0815, 300);

        assertTrue(aggregator.isNewPeriod(b1));
        aggregator.add(b1);
        assertFalse(aggregator.isNewPeriod(b2));
        aggregator.add(b2);
        assertFalse(aggregator.isNewPeriod(b3));
        aggregator.add(b3);

        // Current in progress H1 bar checks
        Bar inProgress = aggregator.getInProgressBar();
        assertNotNull(inProgress);
        assertEquals(Instant.parse("2026-05-20T10:00:00Z"), inProgress.timestamp());
        assertEquals(1.0800, inProgress.open());
        assertEquals(1.0830, inProgress.high());
        assertEquals(1.0780, inProgress.low());
        assertEquals(1.0799, inProgress.close());
        assertEquals(450, inProgress.volume());

        // Triggering the next hour starts a new period
        assertTrue(aggregator.isNewPeriod(b4));
        aggregator.add(b4);

        // Previous period should be completed
        Bar completed = aggregator.getLastCompletedBar();
        assertNotNull(completed);
        assertEquals(Instant.parse("2026-05-20T10:00:00Z"), completed.timestamp());
        assertEquals(1.0830, completed.high());
        assertEquals(1.0780, completed.low());
        assertEquals(1.0799, completed.close());
        assertEquals(450, completed.volume());

        // In progress now contains the 11:00:00 bar
        Bar nextInProgress = aggregator.getInProgressBar();
        assertNotNull(nextInProgress);
        assertEquals(Instant.parse("2026-05-20T11:00:00Z"), nextInProgress.timestamp());
        assertEquals(1.0800, nextInProgress.open());
        assertEquals(300, nextInProgress.volume());
    }

    @Test
    void test30MinAggregation() {
        BarAggregator aggregator = new BarAggregator("EUR_USD", "M30");

        Bar b1 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:15:00Z"), 1.00, 1.05, 0.95, 1.01, 10);
        Bar b2 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:29:00Z"), 1.01, 1.03, 1.00, 1.02, 10);
        Bar b3 = new Bar("EUR_USD", Instant.parse("2026-05-20T10:30:00Z"), 1.02, 1.04, 1.01, 1.03, 10);

        aggregator.add(b1);
        aggregator.add(b2);
        assertTrue(aggregator.isNewPeriod(b3));
        
        Bar inProgress = aggregator.getInProgressBar();
        assertEquals(Instant.parse("2026-05-20T10:00:00Z"), inProgress.timestamp());
        assertEquals(1.00, inProgress.open());
        assertEquals(1.02, inProgress.close());
        assertEquals(20, inProgress.volume());

        aggregator.add(b3);
        Bar completed = aggregator.getLastCompletedBar();
        assertEquals(Instant.parse("2026-05-20T10:00:00Z"), completed.timestamp());
        
        Bar inProgressNext = aggregator.getInProgressBar();
        assertEquals(Instant.parse("2026-05-20T10:30:00Z"), inProgressNext.timestamp());
    }
}
