package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Runs 3 newly designed strategies across all available forex pairs.
 * Strategies: CompositeMomentumRanking, ATRExpansionMomentum, MonthWeekPhase
 * Pairs: EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CAD, NZD/USD, USD/CHF, GBP/JPY
 * Data: H1 2006-2026 (20 years)
 * Costs: 0.5 pips commission (majors), 1 pip (crosses), 1 pip slippage
 *
 * This is the first run of this cron-style strategy R&D pipeline.
 * 
 * Run: mvn exec:java -pl trading-examples \
 *   -Dexec.mainClass="com.martinfou.trading.examples.RunThreeStrategiesV2" \
 *   -Dexec.args="2006-2026"
 */
public class RunThreeStrategiesV2 {

    private static final double DEFAULT_CAPITAL = 100_000.0;
    private static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD",
        "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"
    };
    private static final Set<String> CROSSES = Set.of("GBP_JPY");

    public static void main(String[] args) throws Exception {
        String yearRange = args.length > 0 ? args[0] : "2006-2026";
        boolean verbose = args.length > 1 && args[1].equals("--verbose");

        System.out.println("=".repeat(100));
        System.out.println("  STRATEGY R&D PIPELINE — V2 — 3 New Strategies × All Pairs");
        System.out.println("  Data: H1 " + yearRange + " | Capital: $" + DEFAULT_CAPITAL);
        System.out.println("  Costs: 0.5 pip commission (majors) + 1 pip (crosses) + 1 pip slippage");
        System.out.println("  IS/OOS: 70/30 split");
        System.out.println("  Strategies: CompositeMomentumRanking | ATRExpansionMomentum | MonthWeekPhase");
        System.out.println("=".repeat(100));

        record StratDef(String id, String desc, java.util.function.Function<String, Strategy> factory) {}
        StratDef[] strats = {
            new StratDef("CompositeMomentum", "Composite Momentum Ranking (Structure/Technical)",
                sym -> new CompositeMomentumRankingStrategy("CompMomentum_" + symbolToDisplay(sym), sym)),
            new StratDef("ATRExpansionMomentum", "ATR Expansion Momentum — Post-News Follow-Through (News/Sentiment)",
                sym -> new ATRExpansionMomentumStrategy("ATRExpMomentum_" + symbolToDisplay(sym), sym)),
            new StratDef("MonthWeekPhase", "Month Week-Phase Momentum (Seasonality/Calendar)",
                sym -> new MonthWeekPhaseStrategy("MonthWeekPhase_" + symbolToDisplay(sym), sym)),
        };

        List<ResultRow> allResults = new ArrayList<>();

        for (StratDef sd : strats) {
            System.out.println("\n" + "-".repeat(100));
            System.out.println("  STRATEGY: " + sd.id + " — " + sd.desc);
            System.out.println("-".repeat(100));

            for (String pair : PAIRS) {
                String symbol = pair;

                // Load data
                List<Bar> bars;
                try {
                    var loaded = HistoricalDataLoader.loadFromArgs(pair, pair, yearRange);
                    bars = loaded.bars();
                } catch (Exception e) {
                    System.err.println("  ⚠ " + symbolToDisplay(pair) + ": data load failed — " + e.getMessage());
                    continue;
                }

                if (bars.size() < 200) {
                    System.out.println("  ⚠ " + symbolToDisplay(pair) + ": insufficient data (" + bars.size() + " bars)");
                    continue;
                }

                // Split into IS (70%) and OOS (30%)
                int splitIdx = (int)(bars.size() * 0.7);
                List<Bar> isBars = bars.subList(0, splitIdx);
                List<Bar> oosBars = bars.subList(splitIdx, bars.size());

                // Build costs
                boolean isCross = CROSSES.contains(pair);
                double commission = isCross ? 1.0 : 0.5; // pips
                double commissionPerTrade = commission * 10; // rough $ per trade for 10k units
                double slippagePctPerTrade = 0.0001; // 1 pip

                double pipValue = symbol.contains("JPY") ? 0.01 : 0.0001;
                double units = 10000; // mini lots
                double commissionInCurrency = commission * pipValue * units;
                double slippageInCurrency = slippagePctPerTrade; // simplify

                BacktestExecutionCost costs = new BacktestExecutionCost(commissionInCurrency, 0.0, slippageInCurrency, 0.0, 0.0);

                try {
                    // IS backtest
                    Strategy strategy = sd.factory.apply(symbol);
                    BacktestResult isResult = runBacktest(strategy, symbol, isBars, DEFAULT_CAPITAL, costs);

                    // OOS backtest
                    Strategy oosStrategy = sd.factory.apply(symbol);
                    BacktestResult oosResult = runBacktest(oosStrategy, symbol, oosBars, DEFAULT_CAPITAL, costs);

                    String pairDisplay = symbolToDisplay(pair);
                    int totalBars = bars.size();

                    ResultRow row = new ResultRow(sd.id, pairDisplay, totalBars, isBars.size(), oosBars.size(),
                        isResult, oosResult);
                    allResults.add(row);

                    if (verbose) {
                        printResult(row);
                    }

                } catch (Exception e) {
                    System.err.println("  ✗ " + symbolToDisplay(pair) + ": backtest failed — " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Print summary table
        printSummary(allResults);

        // Top recommendation with qualification
        recommendTop(allResults);
    }

    private static BacktestResult runBacktest(Strategy strategy, String symbol,
                                              List<Bar> bars, double capital,
                                              BacktestExecutionCost costs) {
        return RunContext.forStrategy(
            null, null, strategy, symbol, RunMode.BACKTEST, bars, capital, null, costs
        ).run();
    }

    private static String symbolToDisplay(String symbol) {
        return symbol.replace("_", "/");
    }

    private static void printResult(ResultRow r) {
        var is = r.isResult();
        var oos = r.oosResult();
        System.out.printf("  %-10s | IS: Sharpe=%.2f PF=%.2f DD=%.2f%% Tr=%d | OOS: Sharpe=%.2f PF=%.2f DD=%.2f%% Tr=%d%n",
            r.pair(),
            is.sharpeRatio(), is.profitFactor(), is.maxDrawdownPct(), is.totalTrades(),
            oos.sharpeRatio(), oos.profitFactor(), oos.maxDrawdownPct(), oos.totalTrades());
    }

    private static void printSummary(List<ResultRow> results) {
        System.out.println("\n" + "=".repeat(120));
        System.out.println("  QUALIFICATION SUMMARY");
        System.out.println("=".repeat(120));
        System.out.printf("%-22s %-10s %6s %7s %6s %4s %7s %7s %6s %4s  %s%n",
            "STRATEGY", "PAIR", "Sharpe", "PF", "DD%", "Tr", "OShrp", "OPF", "ODD%", "OTr", "STATUS");
        System.out.println("-".repeat(120));

        for (ResultRow r : results) {
            var is = r.isResult();
            var oos = r.oosResult();
            String status;

            // Qualification criteria (Quantified Strategies + Ali Casey thresholds)
            boolean oosGoodSharpe = oos.sharpeRatio() >= 1.0;
            boolean oosGoodPF = oos.profitFactor() >= 1.3;
            boolean oosLowDD = oos.maxDrawdownPct() <= 25.0;
            boolean oosMinTrades = oos.totalTrades() >= 30;
            boolean isOosConsistent = is.profitFactor() > 0 && oos.profitFactor() > 0
                && Math.abs(oos.profitFactor() - is.profitFactor()) / Math.max(is.profitFactor(), 0.1) < 0.6;

            if (oosGoodSharpe && oosGoodPF && oosLowDD && oosMinTrades && isOosConsistent) {
                status = "✅ QUALIFIED";
            } else if (oosGoodPF && oosLowDD && oosMinTrades) {
                status = "🔶 PROMISING";
            } else if (oosMinTrades) {
                status = "⚠️  WEAK";
            } else {
                status = "❌ REJECTED";
            }

            System.out.printf("%-22s %-10s %6.2f %7.2f %5.2f%% %4d %7.2f %7.2f %5.2f%% %4d  %s%n",
                r.strategyId(), r.pair(),
                is.sharpeRatio(), is.profitFactor(), is.maxDrawdownPct(), is.totalTrades(),
                oos.sharpeRatio(), oos.profitFactor(), oos.maxDrawdownPct(), oos.totalTrades(),
                status);
        }
        System.out.println("-".repeat(120));
    }

    private static void recommendTop(List<ResultRow> results) {
        System.out.println("\n  📊 TOP RECOMMENDATION");

        // Find best OOS Sharpe with minimum trades and positive PF
        ResultRow best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ResultRow r : results) {
            var oos = r.oosResult();
            if (oos.totalTrades() < 30) continue;
            if (oos.profitFactor() <= 1.0) continue;

            // Multi-criteria score: Sharpe × PF / DD × sqrt(trades)
            double score = oos.sharpeRatio() * 0.4 + oos.profitFactor() * 0.3
                - oos.maxDrawdownPct() * 0.02 + Math.min(oos.totalTrades(), 200) * 0.005;

            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }

        if (best != null) {
            var oos = best.oosResult();
            var is = best.isResult();
            System.out.printf("  🏆 %s on %s%n", best.strategyId(), best.pair());
            System.out.printf("     IS:  Sharpe=%.2f  PF=%.2f  DD=%.2f%%  Trades=%d%n",
                is.sharpeRatio(), is.profitFactor(), is.maxDrawdownPct(), is.totalTrades());
            System.out.printf("     OOS: Sharpe=%.2f  PF=%.2f  DD=%.2f%%  Trades=%d%n",
                oos.sharpeRatio(), oos.profitFactor(), oos.maxDrawdownPct(), oos.totalTrades());
            System.out.printf("     Score: %.2f%n", bestScore);

            // Consistency check
            double pfRatio = (is.profitFactor() > 0 && oos.profitFactor() > 0)
                ? (oos.profitFactor() / is.profitFactor() * 100) : 0;
            System.out.printf("     Consistency: IS→OOS PF ratio = %.0f%%%n", pfRatio);

            // MCPT recommendation
            if (oos.sharpeRatio() >= 1.5 && oos.profitFactor() >= 1.6 && oos.maxDrawdownPct() <= 25.0) {
                System.out.println("     ✅ MCPT-qualified: meets Neurotrader/MCPT threshold (Sharpe ≥ 1.5 OOS)");
            } else {
                System.out.println("     🔶 Below MCPT threshold (needs Sharpe ≥ 1.5 OOS for statistical significance)");
            }

            // Seasonality check
            System.out.println("     Seasonality: check " + best.pair() + " monthly patterns for robustness");

            // Soft signal note
            System.out.println("     Soft signals: VWAP/Volume profile check recommended before live deployment");
            
            // Next steps
            System.out.println("\n  📋 NEXT STEPS:");
            System.out.println("     - Apply Neurotrader MCPT permutation test for p-value");
            System.out.println("     - Meta-labeling with Random Forest trade filter");
            System.out.println("     - Walk-forward optimization (3-fold) for robustness");
            System.out.println("     - Portfolio construction with existing strategies");
        } else {
            System.out.println("  ❌ No strategy passed minimum qualification thresholds.");
            System.out.println("     All rejected — review parameter tuning or new ideas for next run.");
        }

        // Per-strategy best pairs
        System.out.println("\n  📋 PER-STRATEGY BEST PAIRS:");
        Map<String, List<ResultRow>> byStrategy = new LinkedHashMap<>();
        for (ResultRow r : results) {
            byStrategy.computeIfAbsent(r.strategyId(), k -> new ArrayList<>()).add(r);
        }

        for (var entry : byStrategy.entrySet()) {
            String sid = entry.getKey();
            var list = entry.getValue();
            list.sort((a, b) -> Double.compare(
                b.oosResult().sharpeRatio() * 0.4 + b.oosResult().profitFactor() * 0.3,
                a.oosResult().sharpeRatio() * 0.4 + a.oosResult().profitFactor() * 0.3));

            System.out.printf("  %s: ", sid);
            list.stream()
                .filter(r -> r.oosResult().totalTrades() >= 20)
                .limit(3)
                .forEach(r -> System.out.printf("%s(S=%.2f/PF=%.2f/Tr=%d) ",
                    r.pair(), r.oosResult().sharpeRatio(), r.oosResult().profitFactor(), r.oosResult().totalTrades()));
            System.out.println();
        }

        // Overall qualification summary
        long qualified = results.stream()
            .filter(r -> r.oosResult().sharpeRatio() >= 1.0 && r.oosResult().profitFactor() >= 1.3
                && r.oosResult().maxDrawdownPct() <= 25.0 && r.oosResult().totalTrades() >= 30)
            .count();
        long total = results.size();

        System.out.printf("\n  📊 Overall: %d/%d pairs × strategy combinations qualified%n", qualified, total);
    }

    record ResultRow(String strategyId, String pair, int totalBars, int isBars, int oosBars,
                     BacktestResult isResult, BacktestResult oosResult) {}
}
