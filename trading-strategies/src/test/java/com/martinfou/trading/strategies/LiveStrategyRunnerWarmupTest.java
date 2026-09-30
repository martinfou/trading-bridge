package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the warm-up replay defect of 2026-09-30.
 *
 * <p><b>Defect.</b> Warm-up fed ~200 historical bars to {@code strategy.onBar()} to prime the
 * indicators. Each call queues orders into the strategy's pending list, and nothing drained that list
 * during warm-up. The first tick that saw a new bar then called {@code checkPendingOrders}, which
 * drained the entire backlog and executed all of it at market in a single pass. The runner opened and
 * closed the same position repeatedly (7 round trips in 7 seconds), and because each replayed order
 * carried the stop of the bar that created it, the risk-sized units ranged from 38,200 to 1,000,000.
 *
 * <p><b>Rule asserted here.</b> A historical bar must never produce a live order: after warm-up the
 * strategy's order queue is empty, so no backlog can reach the broker on the first real tick.
 */
class LiveStrategyRunnerWarmupTest {

    /** Queues one market order per bar, exactly like the creative strategies do. */
    private static class OrderQueuingStrategy implements Strategy {
        final List<Order> pending = new ArrayList<>();
        int barsSeen = 0;

        @Override public String name() { return "WarmupProbe"; }

        @Override
        public void onBar(Bar bar) {
            barsSeen++;
            pending.add(new Order("GBP_JPY", Order.Side.BUY, Order.Type.MARKET, 1000, bar.close()));
        }

        @Override public void onTick(double bid, double ask, long volume) { }

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = new ArrayList<>(pending);
            pending.clear();
            return copy;
        }

        @Override public void reset() { pending.clear(); barsSeen = 0; }
    }

    private static List<Bar> bars(int n) {
        List<Bar> out = new ArrayList<>();
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            out.add(new Bar("GBP_JPY", t.plusSeconds(3600L * i), 208.0, 208.5, 207.5, 208.2, 1000L));
        }
        return out;
    }

    private LiveStrategyRunner runner(Strategy s) {
        return new LiveStrategyRunner("dummy-key", "dummy-acc", s, "WarmupProbe", "H1", 60);
    }

    /** Every order queued while warming up must be discarded before the first real tick. */
    @Test
    @DisplayName("warm-up discards the orders queued by historical bars")
    void warmUpDiscardsOrdersQueuedByHistoricalBars() {
        OrderQueuingStrategy strategy = new OrderQueuingStrategy();
        LiveStrategyRunner r = runner(strategy);

        r.warmUp(bars(20));

        assertEquals(20, strategy.barsSeen, "the strategy is primed with every historical bar");
        assertTrue(strategy.getPendingOrders().isEmpty(),
            "no order queued during warm-up may survive it — otherwise the first tick flushes "
                + "the whole history's backlog to the broker in one pass");
    }

    /** A strategy that queues nothing must not make warm-up complain or throw. */
    @Test
    @DisplayName("warm-up is a no-op for a strategy that queues nothing")
    void warmUpIsSafeForAQuietStrategy() {
        Strategy quiet = new Strategy() {
            @Override public String name() { return "Quiet"; }
            @Override public void onBar(Bar bar) { }
            @Override public void onTick(double bid, double ask, long volume) { }
            @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
            @Override public void reset() { }
        };

        LiveStrategyRunner r = runner(quiet);
        r.warmUp(bars(5));

        assertTrue(quiet.getPendingOrders().isEmpty());
    }

    /** Warm-up with no bars at all must not throw and must leave the queue empty. */
    @Test
    @DisplayName("warm-up tolerates an empty bar list")
    void warmUpToleratesEmptyHistory() {
        OrderQueuingStrategy strategy = new OrderQueuingStrategy();
        LiveStrategyRunner r = runner(strategy);

        r.warmUp(List.of());

        assertEquals(0, strategy.barsSeen);
        assertTrue(strategy.getPendingOrders().isEmpty());
    }

    /**
     * A strategy whose {@code getPendingOrders()} COMPUTES orders from its position state
     * (GoBigStrategy, CasinoStrategy) never empties. Warm-up must not pretend it drained such a
     * strategy: this is the limitation the independent review flagged, and it is logged as an error
     * so the operator syncs the strategy to the broker position before running it live.
     */
    @Test
    @DisplayName("warm-up cannot clear a strategy whose orders are a computed view")
    void warmUpCannotClearAComputedViewStrategy() {
        Strategy computedView = new Strategy() {
            boolean inPosition = true;
            @Override public String name() { return "ComputedView"; }
            @Override public void onBar(Bar bar) { }
            @Override public void onTick(double bid, double ask, long volume) { }
            @Override public List<Order> getPendingOrders() {
                List<Order> orders = new ArrayList<>();
                if (inPosition) {
                    orders.add(new Order("GBP_JPY", Order.Side.BUY, Order.Type.MARKET, 10000, 208.0));
                }
                return orders;
            }
            @Override public void reset() { inPosition = false; }
        };

        LiveStrategyRunner r = runner(computedView);
        r.warmUp(bars(3));

        assertTrue(!computedView.getPendingOrders().isEmpty(),
            "a computed-view strategy keeps returning orders, so warm-up must not report a clean "
                + "drain; the runner logs an error and the strategy must be synced before going live");
    }

    // ========================================================================
    // Sizing fail-safes — these must always fail SMALL, never unbudgeted.
    // ========================================================================

    /** A zero risk distance has no denominator: never hand back the requested size unbudgeted. */
    @Test
    @DisplayName("zero stop distance fails small, not unbudgeted")
    void zeroStopDistanceFailsSmall() {
        LiveStrategyRunner r = runner(new OrderQueuingStrategy());

        assertEquals(LiveStrategyRunner.NO_RISK_UNITS_CAP,
            r.riskSizedUnits(96_000, 0.75, 208.0, 208.0, 50_000, "GBP_JPY"), 1e-9,
            "entry and stop at the same price must cap the size, never return the requested 50,000");
    }

    /** No stop at all, and a request below the cap, still resolves to the smaller of the two. */
    @Test
    @DisplayName("missing stop caps at the requested size when that is already small")
    void missingStopKeepsTheSmallerOfRequestAndCap() {
        LiveStrategyRunner r = runner(new OrderQueuingStrategy());

        assertEquals(LiveStrategyRunner.NO_RISK_UNITS_CAP,
            r.riskSizedUnits(96_000, 0.75, 208.0, 0, 50_000, "GBP_JPY"), 1e-9,
            "a 50,000-unit request with no stop caps at the fail-safe");

        assertEquals(1000,
            r.riskSizedUnits(96_000, 0.75, 208.0, 0, 1000, "GBP_JPY"), 1e-9,
            "a 1,000-unit request with no stop stays at 1,000");
    }
}
