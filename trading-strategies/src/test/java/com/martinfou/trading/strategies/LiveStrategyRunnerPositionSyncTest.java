package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 (story 1.3/1.7) — the broker's stop-loss is the single driver of the stop, so the strategy must
 * learn from the runner's reconciliation that its position is gone. Without this, {@code LtRSI3Momentum}
 * keeps {@code inTrade == true} after a broker SL close and never enters again (the silent-zombie class,
 * already seen on TurnOfMonthFlowStrategy).
 *
 * <p>The runner must call {@code strategy.syncPosition(null, 0, 0, 0)} — the canonical flat-state
 * notification that {@code OandaStreamingExecutor} already makes — once it has reconciled the last open
 * position for the instrument away. These tests fail on the pre-fix code, where the removal paths
 * ({@code completeReconciliation}, {@code reconcileClosedTrade}'s eviction, {@code reconcileTradeFallback})
 * never notify the strategy.
 */
class LiveStrategyRunnerPositionSyncTest {

    /** Records every {@code syncPosition} call so the test can assert the runner notified the strategy. */
    private static final class RecordingStrategy implements Strategy {
        final List<Object[]> syncCalls = new ArrayList<>();

        @Override public String name() { return "recording"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }

        @Override public void syncPosition(Order.Side side, double quantity, double sl, double tp) {
            syncCalls.add(new Object[]{side, quantity, sl, tp});
        }
    }

    private static LiveStrategyRunner runner(Strategy s) {
        return new LiveStrategyRunner("k", "acc", s, "positionsync", "H1", 60);
    }

    @Test
    void reconciliationOfLastTradeNotifiesStrategyFlat() {
        RecordingStrategy s = new RecordingStrategy();
        LiveStrategyRunner r = runner(s);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "GBP_JPY", "BUY", 208.000, 1000, 207.0, 0, Instant.now()));

        r.completeReconciliation("t1", -1.5);

        assertEquals(1, s.syncCalls.size(),
            "the strategy must be notified once its last tracked position is reconciled away");
        Object[] call = s.syncCalls.get(0);
        assertNull(call[0], "the notification must carry a null side (flat)");
        assertEquals(0.0, (Double) call[1], 1e-9, "flat quantity");
        assertEquals(0.0, (Double) call[2], 1e-9, "flat stop");
        assertEquals(0.0, (Double) call[3], 1e-9, "flat target");
    }

    @Test
    void brokerClosedWithoutRealizedPlAlsoNotifiesStrategyFlat() {
        RecordingStrategy s = new RecordingStrategy();
        LiveStrategyRunner r = runner(s);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));

        // The broker reports the SL-closed trade with no usable realizedPL (the M2 branch).
        var node = new ObjectMapper().createObjectNode().put("state", "CLOSED");
        r.reconcileClosedTrade("t1", node);

        assertEquals(1, s.syncCalls.size(),
            "a broker-CLOSED trade evicted without a realizedPL must still notify the strategy it is flat");
        assertNull(s.syncCalls.get(0)[0], "the notification must carry a null side (flat)");
    }

    @Test
    void strategyNotNotifiedWhileAnotherTradeForSameSymbolStaysOpen() {
        RecordingStrategy s = new RecordingStrategy();
        LiveStrategyRunner r = runner(s);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t2", "GBP_JPY", "SELL", 208.500, 1000, 0, 0, Instant.now()));

        r.completeReconciliation("t1", -1.0);

        assertTrue(s.syncCalls.isEmpty(),
            "the strategy must not be told it is flat while another position for the same symbol stays open");
    }
}
