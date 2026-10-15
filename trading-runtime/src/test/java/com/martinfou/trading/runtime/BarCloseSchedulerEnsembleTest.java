package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.SmallAccountMarginGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BarCloseScheduler & Multi-Regime Ensemble Tests (Story 46.6)")
class BarCloseSchedulerEnsembleTest {

    @Test
    @DisplayName("Should execute bar cycle and dispatch approved orders during active CME Globex session")
    void testExecuteBarCycleDuringOpenSession() {
        List<Order> dispatched = new ArrayList<>();

        // Wednesday 14:00 UTC (10:00 AM ET - RTH open session)
        Instant openSessionTime = Instant.parse("2026-08-19T14:00:05Z");

        Bar bar = new Bar("MES", openSessionTime.minusSeconds(3600), 5000.0, 5010.0, 4990.0, 5005.0, 1000L);

        BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MES"),
            sym -> List.of(bar),
            bars -> List.of(new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5005.0)),
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add
        );

        int count = scheduler.runCycle(openSessionTime, 5000.0);

        assertEquals(1, count);
        assertEquals(1, dispatched.size());
        assertEquals("MES", dispatched.get(0).symbol());
        assertEquals(1.0, dispatched.get(0).quantity());
    }

    @Test
    @DisplayName("Should skip strategy evaluation when CME Globex is closed (Saturday)")
    void testSkipEvaluationWhenClosed() {
        List<Order> dispatched = new ArrayList<>();

        // Saturday 14:00 UTC - CME closed
        Instant closedTime = Instant.parse("2026-08-22T14:00:05Z");

        Bar bar = new Bar("MES", closedTime.minusSeconds(3600), 5000.0, 5010.0, 4990.0, 5005.0, 1000L);

        BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MES"),
            sym -> List.of(bar),
            bars -> List.of(new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5005.0)),
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add
        );

        int count = scheduler.runCycle(closedTime, 5000.0);

        assertEquals(0, count);
        assertTrue(dispatched.isEmpty());
    }
}
