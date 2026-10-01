package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 1.4 (scoped) and the JPY-format half of story 1.7.
 *
 * <p>The guard predicate lives once in trading-core ({@link Order#hasProtectiveStop()}); the live
 * runner turns a missing stop into a refusal plus an observability counter, and it is NOT applied to
 * the backtest engine (mirror deferred). The format regression pins the exact precision the broker
 * expects per instrument — the {@code %.5f}-on-JPY class of bug must not come back on the
 * stop-on-fill path.
 */
class LiveStrategyRunnerEntryGuardTest {

    private static final Strategy QUIET = new Strategy() {
        @Override public String name() { return "quiet"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    };

    private static LiveStrategyRunner runner() {
        return new LiveStrategyRunner("k", "acc", QUIET, "quiet", "H1", 60);
    }

    @Test
    @DisplayName("a stopless entry is refused and the refusal is counted")
    void stoplessEntryIsRefusedAndCounted() {
        LiveStrategyRunner r = runner();
        Order noStop = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.1000);

        assertTrue(r.rejectEntryWithoutStop(noStop, "EUR_USD"),
            "a live entry without a stop must be refused");
        assertEquals(1, r.getRejectedNoStopEntries(),
            "the refusal is counted for observability, not silent");

        assertFalse(r.rejectEntryWithoutStop(noStop.withStopLoss(1.0950), "EUR_USD"),
            "a stop-bearing entry passes the guard");
        assertEquals(1, r.getRejectedNoStopEntries(),
            "the counter does not move for a valid entry");
    }

    @Test
    @DisplayName("the stop formats to the broker's precision per instrument")
    void stopPriceFormatsPerInstrument() {
        assertEquals("207.123", LiveStrategyRunner.formatPrice(207.12345, "GBP_JPY"),
            "JPY pairs must use 3 decimals, not 5");
        assertEquals("207.5", LiveStrategyRunner.formatPrice(207.5, "XAU_USD"),
            "metals must use 1 decimal");
        assertEquals("1.12345", LiveStrategyRunner.formatPrice(1.12345, "EUR_USD"),
            "default pairs must use 5 decimals");
    }

    @Test
    @DisplayName("the single entry gate refuses stopless MARKET and STOP entries, and spares close-only exits")
    void singleEntryGateCoversMarketAndStopAndSparesCloseOnly() {
        LiveStrategyRunner r = runner();
        Order marketNoStop = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.1100);
        Order stopNoStop = new Order("EUR_USD", Order.Side.BUY, Order.Type.STOP, 1000, 1.1100);
        Order stopWithStop = new Order("EUR_USD", Order.Side.BUY, Order.Type.STOP, 1000, 1.1100)
            .withStopLoss(1.0950);
        Order closeOnlyNoStop = new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 1000, 1.1000)
            .asCloseOnly();

        assertTrue(r.refuseEntryOrder(marketNoStop, "EUR_USD"),
            "a stopless MARKET entry must be refused at the single entry gate");
        assertTrue(r.refuseEntryOrder(stopNoStop, "EUR_USD"),
            "a stopless STOP entry must be refused at the single entry gate");
        assertFalse(r.refuseEntryOrder(stopWithStop, "EUR_USD"),
            "a STOP entry carrying a stop passes the gate");
        assertFalse(r.refuseEntryOrder(closeOnlyNoStop, "EUR_USD"),
            "a close-only exit is never refused for lacking a stop");

        assertEquals(2, r.getRejectedNoStopEntries(),
            "the two stopless entries are counted; the valid entry and the exit are not");
    }
}
