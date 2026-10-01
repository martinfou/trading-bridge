package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stories 1.3 and C1 for {@link LtRSI3Momentum}.
 *
 * <p><b>1.3</b> — an {@code ltrsi3} entry order carries its ATR stop ({@code atr × SL_MULT}) on both
 * the BUY and SELL branches, and the take-profit is NOT attached to the order (D30: the target stays
 * at the strategy). These assertions fail on the pre-fix code, where {@code evaluateEntry} emitted a
 * bare order with {@code stopLoss() == 0}.
 *
 * <p><b>C1</b> — once the stop lives in the order (broker SL in live, engine SL/TP in backtest), the
 * strategy's own stop check is the second driver of the same exit and must be a no-op: a bar that
 * merely touches the stop no longer makes the strategy emit its own stop exit.
 */
class LtRSI3MomentumStopTest {

    private static final String SYMBOL = "EUR_USD";

    private static Bar bar(Instant t, double open, double high, double low, double close) {
        return new Bar(SYMBOL, t, open, high, low, close, 1000L);
    }

    /** {@code n} bars climbing 0.001/bar; high/low padded 0.0005 around the close. */
    private static List<Bar> uptrend(int n) {
        List<Bar> out = new ArrayList<>();
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        double prev = 1.1000;
        for (int i = 0; i < n; i++) {
            double close = 1.1000 + (i + 1) * 0.001;
            out.add(bar(t.plusSeconds(3600L * i), prev, close + 0.0005, prev - 0.0005, close));
            prev = close;
        }
        return out;
    }

    /** {@code n} bars falling 0.001/bar; high/low padded 0.0005 around the close. */
    private static List<Bar> downtrend(int n) {
        List<Bar> out = new ArrayList<>();
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        double prev = 1.1000;
        for (int i = 0; i < n; i++) {
            double close = 1.1000 - (i + 1) * 0.001;
            out.add(bar(t.plusSeconds(3600L * i), prev, prev + 0.0005, close - 0.0005, close));
            prev = close;
        }
        return out;
    }

    private static LtRSI3Momentum primed(List<Bar> bars) {
        LtRSI3Momentum s = new LtRSI3Momentum("ltrsi3", SYMBOL);
        for (Bar b : bars) s.onBar(b);
        return s;
    }

    // ========================================================================
    // Story 1.3 — the ATR stop rides the entry order, the target stays internal
    // ========================================================================

    @Test
    @DisplayName("BUY entry carries an ATR stop (atr × SL_MULT) and no take profit")
    void buyEntryCarriesAtrStop() {
        List<Bar> bars = uptrend(210);
        LtRSI3Momentum s = primed(bars);

        List<Order> orders = s.getPendingOrders();
        assertEquals(1, orders.size(), "exactly one entry order after the warm-up bars");
        Order o = orders.get(0);
        assertEquals(Order.Side.BUY, o.side());
        assertTrue(o.stopLoss() > 0, "a live entry must carry a stop");
        assertTrue(o.stopLoss() < o.price(), "a BUY stop sits below the entry price");
        assertEquals(0.0, o.takeProfit(), 1e-9, "D30: the take profit stays at the strategy, never on the order");

        double expectedAtr = Indicators.atr(bars, 14);
        assertEquals(2.0 * expectedAtr, o.price() - o.stopLoss(), 1e-9,
            "stop distance must equal atr × SL_MULT (2.0)");
    }

    @Test
    @DisplayName("SELL entry carries an ATR stop (atr × SL_MULT) and no take profit")
    void sellEntryCarriesAtrStop() {
        List<Bar> bars = downtrend(210);
        LtRSI3Momentum s = primed(bars);

        List<Order> orders = s.getPendingOrders();
        assertEquals(1, orders.size(), "exactly one entry order after the warm-up bars");
        Order o = orders.get(0);
        assertEquals(Order.Side.SELL, o.side());
        assertTrue(o.stopLoss() > 0, "a live entry must carry a stop");
        assertTrue(o.stopLoss() > o.price(), "a SELL stop sits above the entry price");
        assertEquals(0.0, o.takeProfit(), 1e-9, "D30: the take profit stays at the strategy, never on the order");

        double expectedAtr = Indicators.atr(bars, 14);
        assertEquals(2.0 * expectedAtr, o.stopLoss() - o.price(), 1e-9,
            "stop distance must equal atr × SL_MULT (2.0)");
    }

    // ========================================================================
    // C1 — the stop exit is single-driven (the broker SL), never also the strategy
    // ========================================================================

    @Test
    @DisplayName("a bar that touches the stop no longer makes the strategy emit its own stop exit")
    void stopTouchingBarDoesNotSelfExit() {
        List<Bar> bars = uptrend(210);
        LtRSI3Momentum s = primed(bars);
        Order entry = s.getPendingOrders().get(0);
        double stopLoss = entry.stopLoss();
        double lastClose = bars.get(209).close();
        Instant t = bars.get(209).timestamp();

        // Keep RSI(3) > 60 after the entry so only the stop could trigger an exit.
        for (int i = 1; i <= 3; i++) {
            double close = lastClose + i * 0.001;
            s.onBar(bar(t.plusSeconds(3600L * i), close - 0.001, close + 0.0005, close - 0.0015, close));
        }
        double preWickClose = lastClose + 3 * 0.001;

        // A wick bar: its low crosses the stop, but it closes back up so RSI(3) stays high and the
        // take profit is untouched. Pre-fix, stopHit fired here and queued a stop exit; the stop now
        // belongs to the broker, so the strategy must stay silent.
        double wickClose = preWickClose + 0.001;
        s.onBar(bar(t.plusSeconds(3600L * 4), preWickClose, wickClose + 0.0005, stopLoss - 0.002, wickClose));

        assertTrue(s.getPendingOrders().isEmpty(),
            "a bar that only touches the stop must not emit the strategy's own stop exit (C1)");
    }
}
