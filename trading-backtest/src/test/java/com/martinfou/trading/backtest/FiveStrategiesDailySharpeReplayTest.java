package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy;
import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy;
import com.martinfou.trading.strategies.creative.VWPReversionStrategy;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replays the five deployed strategies (FULL / IS / OOS1 / OOS2) and prints PF + the two
 * Sharpe numbers — the legacy per-bar Sharpe ("avant") and the new daily Sharpe ("après").
 *
 * <p>This is a reporting harness, not a unit test: the only assertion is that every
 * strategy × window row was produced. The table is the deliverable (captured from stdout).</p>
 *
 * <p>Cost model: {@link BacktestExecutionCost#DEFAULT} (commission $0.07 + slippageFixed
 * 0.00005) + the legacy {@code SwapCalculator} table — i.e. the pre-cost-model-fix "avant"
 * configuration, chosen so the PF column lines up with the 2026-10-01 baseline report.</p>
 */
class FiveStrategiesDailySharpeReplayTest {

    static final double CAPITAL = 10_000.0;

    record Strat(String key, String symbol, BiFunction<String, String, Strategy> factory) {}

    static final List<Strat> STRATS = List.of(
        new Strat("consecbar",      "GBP_JPY", ConsecutiveBarExhaustionStrategy::new),
        new Strat("vwpreversion",   "USD_CHF", VWPReversionStrategy::new),
        new Strat("monthweekphase", "USD_JPY", MonthWeekPhaseStrategy::new),
        new Strat("compmomentum",   "USD_JPY", CompositeMomentumRankingStrategy::new),
        new Strat("ltrsi3",         "EUR_USD", LtRSI3Momentum::new)
    );

    record Window(String name, int startYear, int endYear) {}

    static final List<Window> WINDOWS = List.of(
        new Window("FULL", 2010, 2025),
        new Window("IS",   2010, 2018),
        new Window("OOS1", 2019, 2022),
        new Window("OOS2", 2023, 2025)
    );

    @Test
    void replayFiveStrategies() throws Exception {
        Path dukascopy = resolveDukascopyDir();
        int rows = 0;

        for (Strat s : STRATS) {
            List<Bar> full = loadSymbol(dukascopy, s.symbol());
            System.out.println();
            System.out.println("==== " + s.key() + " (" + s.symbol() + ") — bars 2010-2025: "
                + slice(full, 2010, 2025).size() + " ====");
            System.out.printf("%-6s | %-7s | %-10s | %-10s | %-8s | %-7s | %-12s%n",
                "WINDOW", "PF", "SH_AVANT", "SH_APRES", "DD%", "TRADES", "NET$");
            for (Window w : WINDOWS) {
                List<Bar> bars = slice(full, w.startYear(), w.endYear());
                BacktestResult r = BacktestExecutionCost.DEFAULT
                    .configure(new BacktestEngine(s.factory().apply(s.key(), s.symbol()), bars, CAPITAL))
                    .run();
                System.out.printf("%-6s | %-7.2f | %-10.2f | %-10.2f | %-8.2f | %-7d | %-12.2f%n",
                    w.name(), r.profitFactor(), r.perBarSharpeRatioLegacy(), r.sharpeRatio(),
                    r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
                rows++;
            }
        }
        assertEquals(STRATS.size() * WINDOWS.size(), rows, "every strategy × window row must be produced");
    }

    // ------------------------------------------------------------------ data

    /** Finds the dukascopy CSV dir by walking up from the working dir (robust to maven module cwd). */
    static Path resolveDukascopyDir() {
        String override = System.getProperty("tb.dataRoot");
        Path start = override != null ? Path.of(override) : Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            Path candidate = p.resolve("data/historical/dukascopy");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("data/historical/dukascopy not found from " + start
            + " (run with -Dtb.dataRoot=/path/to/repo)");
    }

    /** Loads a symbol's full history from its per-year dukascopy bid CSVs. */
    static List<Bar> loadSymbol(Path dukascopy, String symbol) throws Exception {
        String pair = symbol.replace("_", "").toLowerCase();
        List<Path> files;
        try (var stream = Files.list(dukascopy)) {
            files = stream
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    // per-year files only: exclude the full-range "*-2006-2026.csv" and any M1 files
                    return n.startsWith(pair + "-h1-bid-") && n.contains("-01-01-");
                })
                .sorted()
                .toList();
        }
        List<Bar> bars = new ArrayList<>();
        for (Path f : files) {
            bars.addAll(DataLoader.loadDukascopyCSV(f, symbol));
        }
        bars.sort(Comparator.comparing(Bar::timestamp));
        return bars;
    }

    static List<Bar> slice(List<Bar> bars, int startYear, int endYear) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            int y = b.timestamp().atZone(java.time.ZoneOffset.UTC).getYear();
            if (y >= startYear && y <= endYear) out.add(b);
        }
        return out;
    }
}
