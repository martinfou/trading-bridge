package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Story 39.2 — swap must be counted when a position exits via stop-loss,
 * take-profit, or end-of-backtest force close, not only on closeOnly reductions.
 *
 * <p>Regression: {@link BacktestEngine#closePosition} skipped the
 * {@code SwapCalculator.calculateSwap(...)} call that {@code reduceOppositeSide}
 * already performed, so long-running positions closed by SL/TP/force-close
 * carried zero swap cost (audit finding P3).
 */
class SwapOnExitBacktestTest {

    private static final double CAPITAL = 100_000.0;
    private static final String SYMBOL = "EUR_USD";
    private static final Instant MONDAY = Instant.parse("2012-06-04T12:00:00Z");

    /** Daily bars Mon→Fri, one per trading day (weekends skipped like the engine). */
    private static List<Bar> dailyBars(double[][] rows) {
        var bars = new ArrayList<Bar>(rows.length);
        Instant ts = MONDAY;
        for (double[] r : rows) {
            while (com.martinfou.trading.core.ForexMarketCalendar.isWeekendUtc(ts)) {
                ts = ts.plusSeconds(86400L);
            }
            bars.add(new Bar(SYMBOL, ts, r[0], r[1], r[2], r[3], 1000));
            ts = ts.plusSeconds(86400L);
        }
        return bars;
    }

    private static List<Bar> flatDaily(int count, double price) {
        double[][] rows = new double[count][4];
        for (int i = 0; i < count; i++) {
            rows[i] = new double[] {price, price + 0.0010, price - 0.0010, price};
        }
        return dailyBars(rows);
    }

    @Test
    void stopLossExit_accruesSwap() {
        // Mon(emit) Tue(entry) Wed Thu Fri Mon(SL hit)
        List<Bar> bars = dailyBars(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0940, 1.0950}   // low breaks SL 1.0950
        });

        BacktestResult result = new BacktestEngine(
            TestStrategies.buyWithStopLoss(1.0950), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());
        assertEquals(1.0950, result.trades().getFirst().exitPrice(), 1e-9, "SL exit at stop price");

        // Entry fills at bar 1 open (Tue), SL closes at bar 5 (Mon)
        double expectedSwap = SwapCalculator.calculateSwap(
            SYMBOL, com.martinfou.trading.core.Order.Side.BUY, 10_000,
            bars.get(1).timestamp(), bars.get(5).timestamp());

        assertNotEquals(0.0, expectedSwap, "5-day hold must cross at least one rollover");
        assertEquals(expectedSwap, result.totalSwap(), 1e-9,
            "SL exit must accrue swap for the full holding period");
        // Zero-cost engine: totalPnl = trade PnL + signed swap
        double tradePnlSum = result.trades().stream().mapToDouble(Trade::pnl).sum();
        assertEquals(tradePnlSum + result.totalSwap(), result.totalPnl(), 1e-9);
    }

    @Test
    void takeProfitExit_accruesSwap() {
        // Mon(emit) Tue(entry) Wed Thu Fri(TP hit)
        List<Bar> bars = dailyBars(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1040, 1.1060, 1.1030, 1.1055}   // high breaks TP 1.1050
        });

        BacktestResult result = new BacktestEngine(
            TestStrategies.buyWithTakeProfit(1.1050), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());
        assertEquals(1.1050, result.trades().getFirst().exitPrice(), 1e-9, "TP exit at target");

        double expectedSwap = SwapCalculator.calculateSwap(
            SYMBOL, com.martinfou.trading.core.Order.Side.BUY, 10_000,
            bars.get(1).timestamp(), bars.get(4).timestamp());

        assertNotEquals(0.0, expectedSwap, "multi-day hold must cross at least one rollover");
        assertEquals(expectedSwap, result.totalSwap(), 1e-9,
            "TP exit must accrue swap for the full holding period");
    }

    @Test
    void forceCloseAtEndOfRun_accruesSwap() {
        // buyOnce holds to the last trading bar: Mon(emit) Tue..Fri week1, Mon..Fri week2
        List<Bar> bars = flatDaily(10, 1.1000);

        BacktestResult result = new BacktestEngine(
            TestStrategies.buyOnce(), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());

        // Entry fills at bar 1 (Tue); force close at last trading bar close (bar 9, Fri)
        double expectedSwap = SwapCalculator.calculateSwap(
            SYMBOL, com.martinfou.trading.core.Order.Side.BUY, 10_000,
            bars.get(1).timestamp(), bars.get(9).timestamp());

        assertNotEquals(0.0, expectedSwap, "2-week hold must cross several rollovers");
        assertEquals(expectedSwap, result.totalSwap(), 1e-9,
            "end-of-run force close must accrue swap");
    }

    @Test
    void sameDayClose_hasNoSwap() {
        // Entry and closeOnly exit within the same day before the 17:00 ET rollover
        List<Bar> bars = List.of(
            new Bar(SYMBOL, Instant.parse("2012-06-05T12:00:00Z"), 1.1000, 1.1010, 1.0990, 1.1005, 1000),
            new Bar(SYMBOL, Instant.parse("2012-06-05T13:00:00Z"), 1.1000, 1.1010, 1.0990, 1.1005, 1000),
            new Bar(SYMBOL, Instant.parse("2012-06-05T14:00:00Z"), 1.1000, 1.1010, 1.0990, 1.1005, 1000)
        );
        BacktestResult result = new BacktestEngine(
            TestStrategies.buyThenCloseOnlySell(), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());
        assertEquals(0.0, result.totalSwap(), 1e-9,
            "positions closed before the 17:00 ET rollover carry no swap");
    }
}
