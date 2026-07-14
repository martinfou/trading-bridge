package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.strategies.StrategyCatalog.Family;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Backtest runner for LtRSI3Momentum (RSI drift following) strategy.
 *
 * Runs backtests on:
 *   - EUR_USD FULL (2006-2026)
 *   - EUR_USD IS (2006-2014) — in-sample
 *   - EUR_USD OOS1 (2015-2019) — out-of-sample 1
 *   - EUR_USD OOS2 (2020-2026) — out-of-sample 2
 *   - GBP_USD FULL (2006-2026)
 *
 * Prints a comparative report with Leçons apprises.
 */
public class RunLtRSI3Momentum {

    static {
        StrategyCatalog.register("LtRSI3Momentum", Family.EXAMPLE,
            sym -> new LtRSI3Momentum("LtRSI3Momentum", sym),
            "EUR_USD");
    }

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║       LtRSI3Momentum — RSI(3) Drift Following          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Parameters:");
        System.out.println("  RSI period  : 3");
        System.out.println("  EMA filter  : 200");
        System.out.println("  ATR period  : 14");
        System.out.println("  SL mult     : 2.0x ATR");
        System.out.println("  TP mult     : 4.0x ATR");
        System.out.println("  Max trades  : 1 per day");
        System.out.println("  Exit rule   : RSI back to 40-60 neutral zone (closeOnly)");
        System.out.println();

        // Define test runs
        var runs = new LinkedHashMap<String, TestRun>();
        runs.put("EUR_USD FULL (2006-2026)", new TestRun("EUR_USD", "2006-2026", "FULL"));
        runs.put("EUR_USD IS (2006-2014)",   new TestRun("EUR_USD", "2006-2014", "IS"));
        runs.put("EUR_USD OOS1 (2015-2019)", new TestRun("EUR_USD", "2015-2019", "OOS1"));
        runs.put("EUR_USD OOS2 (2020-2026)", new TestRun("EUR_USD", "2020-2026", "OOS2"));
        runs.put("GBP_USD FULL (2006-2026)", new TestRun("GBP_USD", "2006-2026", "FULL"));

        var results = new LinkedHashMap<String, BacktestResult>();

        for (var entry : runs.entrySet()) {
            String label = entry.getKey();
            TestRun testRun = entry.getValue();

            System.out.println("───────────────────────────────────────────────────────────");
            System.out.println(">>> " + label);
            System.out.println("───────────────────────────────────────────────────────────");

            try {
                List<Bar> bars = HistoricalDataLoader.loadYearSpec(
                    testRun.symbol, testRun.yearSpec, "H1",
                    HistoricalDataLoader.DEFAULT_BARS_DIR);

                if (bars.isEmpty()) {
                    System.out.println("  !! No data loaded, skipping.");
                    continue;
                }

                System.out.println("  Data: " + bars.size() + " H1 bars (" + testRun.symbol + ")");
                System.out.println("  Range: " + formatTimestamp(bars.getFirst().timestamp())
                    + " -> " + formatTimestamp(bars.getLast().timestamp()));

                var strategy = new LtRSI3Momentum("LtRSI3Momentum", testRun.symbol);
                double capital = LotSizing.DEFAULT_STARTING_CAPITAL;

                RunContext context = RunContext.forStrategy(strategy, testRun.symbol,
                    RunMode.BACKTEST, bars, capital);

                BacktestResult result = context.run();
                result.printSummary();
                results.put(label, result);

            } catch (Exception e) {
                System.out.println("  !! ERROR: " + e.getMessage());
                e.printStackTrace(System.out);
            }
            System.out.println();
        }

        // Comparative summary
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║               COMPARATIVE SUMMARY                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.printf("%-33s %10s %8s %8s %10s %8s %8s %8s%n",
            "RUN", "P&L ($)", "TRADES", "WIN%", "AVG TRD", "SHARPE", "DD%", "CALMAR");
        System.out.println("-".repeat(95));

        for (var entry : results.entrySet()) {
            String label = entry.getKey();
            BacktestResult r = entry.getValue();

            System.out.printf("%-33s %+10.2f %8d %7.1f%% %+10.2f %8.2f %8.2f %8.2f%n",
                label,
                r.totalPnl(),
                r.totalTrades(),
                r.winRatePct(),
                r.avgTradePnl(),
                r.sharpeRatio(),
                r.maxDrawdownPct(),
                r.calmarRatio());
        }

        // Lecons apprises
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║              LECONS APPRISES (Lessons Learned)          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        printLessons(results);
    }

    private static void printLessons(LinkedHashMap<String, BacktestResult> results) {
        BacktestResult eurFull = results.get("EUR_USD FULL (2006-2026)");
        BacktestResult eurIS = results.get("EUR_USD IS (2006-2014)");
        BacktestResult eurOOS1 = results.get("EUR_USD OOS1 (2015-2019)");
        BacktestResult eurOOS2 = results.get("EUR_USD OOS2 (2020-2026)");
        BacktestResult gbpFull = results.get("GBP_USD FULL (2006-2026)");

        System.out.println("1. Strategie de drift RSI(3) -- suivi continu, pas d'extremes.");
        System.out.println();

        if (eurIS != null && eurOOS1 != null) {
            double isPnl = eurIS.totalPnl();
            double oos1Pnl = eurOOS1.totalPnl();
            System.out.println("2. Performances EUR_USD IS vs OOS1 :");
            System.out.printf("   IS   (2006-2014) : $%+.2f (%d trades, %.1f%% win rate)%n",
                isPnl, eurIS.totalTrades(), eurIS.winRatePct());
            System.out.printf("   OOS1 (2015-2019) : $%+.2f (%d trades, %.1f%% win rate)%n",
                oos1Pnl, eurOOS1.totalTrades(), eurOOS1.winRatePct());
            boolean stable = (isPnl > 0 && oos1Pnl > 0) || (isPnl < 0 && oos1Pnl < 0);
            System.out.println("   Stabilite signe : " + (stable ? "OUI" : "NON"));
            System.out.println();
        }

        if (eurOOS2 != null) {
            System.out.printf("3. EUR_USD OOS2 (2020-2026) : $%+.2f (%d trades)%n",
                eurOOS2.totalPnl(), eurOOS2.totalTrades());
            System.out.println();
        }

        if (eurFull != null && gbpFull != null) {
            System.out.printf("4. Cross-pair validation :%n");
            System.out.printf("   EUR_USD : $%+.2f | Sharpe %.2f | DD %.2f%%%n",
                eurFull.totalPnl(), eurFull.sharpeRatio(), eurFull.maxDrawdownPct());
            System.out.printf("   GBP_USD : $%+.2f | Sharpe %.2f | DD %.2f%%%n",
                gbpFull.totalPnl(), gbpFull.sharpeRatio(), gbpFull.maxDrawdownPct());
            boolean crossValid = (eurFull.totalPnl() > 0 && gbpFull.totalPnl() > 0)
                || (eurFull.totalPnl() < 0 && gbpFull.totalPnl() < 0);
            System.out.println("   Coherence cross-pair : " + (crossValid ? "OUI" : "NON"));
            System.out.println();
        }

        // Overall assessment
        boolean anyProfitable = results.values().stream()
            .anyMatch(r -> r.totalPnl() > 0);
        boolean allProfitable = results.values().stream()
            .allMatch(r -> r.totalPnl() > 0);

        if (allProfitable) {
            System.out.println("5. Bilan global : Tous les backtests sont profitables.");
        } else if (anyProfitable) {
            System.out.println("5. Bilan global : Resultats mitiges -- certaines periodes profitables, d'autres non.");
        } else {
            System.out.println("5. Bilan global : Aucun backtest profitable. La strategie necessite des ajustements.");
        }

        System.out.println();
        System.out.println("6. Recommandations :");
        System.out.println("   - Ajuster les seuils RSI (55/45 au lieu de 60/40) pour plus de signaux");
        System.out.println("   - Modifier les multiples SL/TP (1.5x/3x) pour ameliorer le ratio");
        System.out.println("   - Ajouter un filtre de volatilite pour eviter les marchés range-bound");
        System.out.println("   - Tester sur timeframe H4 pour reduire le bruit");
        System.out.println("   - Considerer un trailing stop au lieu du SL fixe");
    }

    private static String formatTimestamp(Instant ts) {
        if (ts == null) return "N/A";
        return ts.atZone(ZoneId.of("America/New_York"))
            .toLocalDateTime().toString().replace("T", " ");
    }

    private record TestRun(String symbol, String yearSpec, String phase) {}
}
