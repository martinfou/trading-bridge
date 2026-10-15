package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Dev utility — A/B probe for the weekend-rollover fix (2026-10-15).
 *
 * <p>Purpose: prove the golden baseline shift is the swap line and nothing else, before re-capturing.
 * Run the SAME source in the pre-fix tree and in the fixed tree, then diff the two outputs.</p>
 *
 * <p>What must hold: (1) the per-trade signature list is byte-identical (same times, sides, prices,
 * quantities, per-trade PnL), (2) PnL_new - PnL_old == swap_new - swap_old, (3) that delta equals the
 * one predicted by the removed rollover nights (old 9-per-week model minus new 7-per-week model),
 * priced at the trade's own swap per night.</p>
 *
 * <pre>{@code
 * mvn -q test-compile exec:java -pl trading-examples -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.martinfou.trading.examples.ProveSwapWeekendShift
 * }</pre>
 */
public final class ProveSwapWeekendShift {

    private ProveSwapWeekendShift() {}

    private static final String SYMBOL = GoldenBacktestBaseline.SYMBOL;
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final int ROLLOVER_HOUR = 17;

    public static void main(String[] args) throws Exception {
        attribute("CI subset", HistoricalDataLoader.loadPath(Path.of("data/ci/EUR_USD_H1_subset.csv"), SYMBOL));
        try {
            List<Bar> year = HistoricalDataLoader.loadYear(
                SYMBOL, GoldenBacktestBaseline.FULL_YEAR, HistoricalDataLoader.DEFAULT_BARS_DIR);
            if (!year.isEmpty()) {
                attribute("Full " + GoldenBacktestBaseline.FULL_YEAR, year);
            }
        } catch (Exception ex) {
            System.err.println("Skipping full year — " + ex.getMessage());
        }
    }

    private static void attribute(String label, List<Bar> bars) {
        BacktestResult r = RunContext.forStrategy(
            null,
            GoldenBacktestBaseline.STRATEGY_ID,
            StrategyCatalog.create(GoldenBacktestBaseline.STRATEGY_ID, SYMBOL),
            SYMBOL,
            RunMode.BACKTEST,
            bars,
            GoldenBacktestBaseline.INITIAL_CAPITAL,
            null,
            RunCostModelReal.BEFORE()).run();

        long nightsOldTotal = 0;
        long nightsNewTotal = 0;
        double predictedDelta = 0.0;
        StringBuilder signatures = new StringBuilder();

        for (Trade t : r.trades()) {
            int oldN = nightsOld(t.entryTime(), t.exitTime());
            int newN = nightsNew(t.entryTime(), t.exitTime());
            nightsOldTotal += oldN;
            nightsNewTotal += newN;
            double swapNow = SwapCalculator.calculateSwap(
                t.symbol(), t.side(), t.quantity(), t.entryTime(), t.exitTime());
            double perNight = newN > 0 ? swapNow / newN : 0.0;
            predictedDelta += (oldN - newN) * perNight;
            signatures.append(String.format(
                "    %s | %s | %-4s | qty %.1f | in %.5f | out %.5f | pnl %.6f | nights %d->%d%n",
                t.entryTime(), t.exitTime(), t.side(), t.quantity(),
                t.entryPrice(), t.exitPrice(), t.pnl(), oldN, newN));
        }

        System.out.printf("%n=== %s (%d bars) ===%n", label, bars.size());
        System.out.printf("  trades                 : %d%n", r.totalTrades());
        System.out.printf("  PnL                    : %.6f%n", r.totalPnl());
        System.out.printf("  swap line              : %.6f%n", r.totalSwap());
        System.out.printf("  nights old model       : %d%n", nightsOldTotal);
        System.out.printf("  nights new model       : %d%n", nightsNewTotal);
        System.out.printf("  nights removed         : %d%n", nightsOldTotal - nightsNewTotal);
        System.out.printf("  delta PnL predicted    : %.6f%n", predictedDelta);
        System.out.print("  --- trade signatures ---");
        System.out.println();
        System.out.print(signatures);
    }

    /** Rollover counter BEFORE the fix: every calendar day counts (Wed = 3, weekend included). */
    static int nightsOld(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) return 0;
        ZonedDateTime fromNY = from.atZone(NY);
        ZonedDateTime toNY = to.atZone(NY);
        ZonedDateTime current = fromNY.withHour(ROLLOVER_HOUR).withMinute(0).withSecond(0);
        if (current.isBefore(fromNY)) current = current.plusDays(1);
        int total = 0;
        while (current.isBefore(toNY)) {
            total += current.getDayOfWeek() == DayOfWeek.WEDNESDAY ? 3 : 1;
            current = current.plusDays(1);
        }
        return total;
    }

    /** Rollover counter AFTER the fix: the FX market is closed on Saturday and Sunday. */
    static int nightsNew(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) return 0;
        ZonedDateTime fromNY = from.atZone(NY);
        ZonedDateTime toNY = to.atZone(NY);
        ZonedDateTime current = fromNY.withHour(ROLLOVER_HOUR).withMinute(0).withSecond(0);
        if (current.isBefore(fromNY)) current = current.plusDays(1);
        int total = 0;
        while (current.isBefore(toNY)) {
            DayOfWeek dow = current.getDayOfWeek();
            if (dow == DayOfWeek.WEDNESDAY) {
                total += 3;
            } else if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                total += 1;
            }
            current = current.plusDays(1);
        }
        return total;
    }
}
