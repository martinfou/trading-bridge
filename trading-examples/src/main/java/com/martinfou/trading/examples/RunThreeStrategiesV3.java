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
 * Runs 3 newly designed strategies across 9 forex pairs.
 * Strategies: VWAPMomentum, MomentumAcceleration, SessionMomentumFlow
 * Pairs: EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CAD, NZD/USD, USD/CHF, EUR/JPY, GBP/JPY
 * Data: H1 2006-2026 (20+ years, Dukascopy)
 * Costs: 0.5 pips commission (majors), 1 pip (crosses), 1 pip slippage
 * IS/OOS: 70/30 split
 *
 * This is the V3 run of the cron-style strategy R&D pipeline.
 * Previous runs: V1 (DualEmaMomentum, SessionBreakoutMomentum, WeekdaySession)
 *                V2 (CompositeMomentumRanking, ATRExpansionMomentum, MonthWeekPhase)
 *
 * Run: mise exec maven@3.9.16 -- mvn exec:java -pl trading-examples \
 *   -Dexec.mainClass="com.martinfou.trading.examples.RunThreeStrategiesV3" \
 *   -Dexec.args="2006-2026"
 */
public class RunThreeStrategiesV3 {

    private static final double DEFAULT_CAPITAL = 100_000.0;

    // All 9 pairs including EUR/JPY
    private static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD",
        "USD_CAD", "NZD_USD", "USD_CHF", "EUR_JPY", "GBP_JPY"
    };
    private static final Set<String> CROSSES = Set.of("EUR_JPY", "GBP_JPY");

    // Qualification thresholds (Quantified Strategies + Ali Casey + Neurotrader MCPT)
    private static final double MIN_OOS_SHARPE = 1.0;
    private static final double MIN_OOS_PF = 1.3;
    private static final double MAX_OOS_DD = 25.0;
    private static final int MIN_OOS_TRADES = 30;
    private static final double MAX_PF_DEGRADATION = 0.6;  // IS→OOS PF ratio tolerance

    public static void main(String[] args) throws Exception {
        String yearRange = args.length > 0 ? args[0] : "2006-2026";
        boolean verbose = args.length > 1 && args[1].equals("--verbose");

        System.out.println("=".repeat(100));
        System.out.println("  STRATEGY R&D PIPELINE — V3 — 3 New Strategies × 9 Pairs");
        System.out.println("  Data: H1 " + yearRange + " | Capital: $" + DEFAULT_CAPITAL);
        System.out.println("  Costs: 0.5 pip commission (majors) + 1 pip (crosses) + 1 pip slippage");
        System.out.println("  IS/OOS: 70/30 split");
        System.out.println("  Strategies: VWAPMomentum | MomentumAcceleration | SessionMomentumFlow");
        System.out.println("=".repeat(100));

        record StratDef(String id, String desc, java.util.function.Function<String, Strategy> factory) {}
        StratDef[] strats = {
            new StratDef("VWAPMomentum", "VWAP Momentum Continuation (Structure/Technical)",
                sym -> new VWAPMomentumStrategy("VWAPMom_" + symbolToDisplay(sym), sym)),
            new StratDef("MomentumAccel", "Momentum Acceleration Breakout (News/Sentiment proxy)",
                sym -> new MomentumAccelerationStrategy("MomAccel_" + symbolToDisplay(sym), sym)),
            new StratDef("SessionFlow", "Session Momentum Flow (Seasonality/Calendar)",
                sym -> new SessionMomentumFlowStrategy("SessFlow_" + symbolToDisplay(sym), sym)),
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
                double commissionPips = isCross ? 1.0 : 0.5;
                double pipValue = symbol.contains("JPY") ? 0.01 : 0.0001;
                double units = 10000; // mini lots
                double commissionInCurrency = commissionPips * pipValue * units;
                double slippageInCurrency = pipValue * units; // 1 pip slippage

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
        System.out.println("  QUALIFICATION SUMMARY — V3 Run");
        System.out.println("=".repeat(120));
        System.out.printf("%-22s %-10s %6s %7s %6s %4s %7s %7s %6s %4s  %s%n",
            "STRATEGY", "PAIR", "Sharpe", "PF", "DD%", "Tr", "OShrp", "OPF", "ODD%", "OTr", "STATUS");
        System.out.println("-".repeat(120));

        for (ResultRow r : results) {
            var is = r.isResult();
            var oos = r.oosResult();
            String status;

            // Qualification criteria
            boolean oosGoodSharpe = oos.sharpeRatio() >= MIN_OOS_SHARPE;
            boolean oosGoodPF = oos.profitFactor() >= MIN_OOS_PF;
            boolean oosLowDD = oos.maxDrawdownPct() <= MAX_OOS_DD;
            boolean oosMinTrades = oos.totalTrades() >= MIN_OOS_TRADES;
            boolean isOosConsistent = is.profitFactor() > 0 && oos.profitFactor() > 0
                && Math.abs(oos.profitFactor() - is.profitFactor()) / Math.max(is.profitFactor(), 0.1) < MAX_PF_DEGRADATION;

            // Additional MCPT-ready check
            boolean mcptQualified = oos.sharpeRatio() >= 1.5 && oos.profitFactor() >= 1.6 && oosLowDD && oosMinTrades;

            if (mcptQualified && isOosConsistent) {
                status = "✅ MCPT-QUALIFIED";
            } else if (oosGoodSharpe && oosGoodPF && oosLowDD && oosMinTrades && isOosConsistent) {
                status = "✅ QUALIFIED";
            } else if (oosGoodPF && oosLowDD && oosMinTrades) {
                status = "🔶 PROMISING";
            } else if (oosMinTrades && oos.profitFactor() > 1.0) {
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
            if (oos.totalTrades() < MIN_OOS_TRADES) continue;
            if (oos.profitFactor() <= 1.0) continue;

            // Multi-criteria score
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

            // MCPT qualification
            if (oos.sharpeRatio() >= 1.5 && oos.profitFactor() >= 1.6 && oos.maxDrawdownPct() <= 25.0) {
                System.out.println("     ✅ MCPT-qualified: meets Neurotrader/MCPT threshold (Sharpe ≥ 1.5 OOS)");
            } else {
                System.out.println("     🔶 Below MCPT threshold (needs Sharpe ≥ 1.5 OOS, PF ≥ 1.6 for statistical significance)");
            }

            // Anti-overfitting checks
            System.out.println("     📋 Anti-overfitting checklist:");
            int checks = 0, total = 0;

            // PF plateau check
            boolean pfPlateau = is.profitFactor() > 1.0 && oos.profitFactor() > 1.0;
            System.out.printf("        %s OOS PF > 1.0 (%.2f)%n", pfPlateau ? "✅" : "❌", oos.profitFactor());
            total++;

            // Trades count
            boolean minTrades = oos.totalTrades() >= MIN_OOS_TRADES;
            System.out.printf("        %s ≥ %d OOS trades (%d)%n", minTrades ? "✅" : "❌", MIN_OOS_TRADES, oos.totalTrades());
            total++;

            // Max DD
            boolean ddOk = oos.maxDrawdownPct() <= MAX_OOS_DD;
            System.out.printf("        %s OOS DD ≤ %.0f%% (%.2f%%)%n", ddOk ? "✅" : "❌", MAX_OOS_DD, oos.maxDrawdownPct());
            total++;

            // IS→OOS consistency
            boolean consistent = pfRatio > 40;
            System.out.printf("        %s IS→OOS PF consistency (%.0f%%)%n", consistent ? "✅" : "❌", pfRatio);
            total++;

            System.out.printf("        Anti-overfitting score: %d/%d passed%n", checks, total);

            // Seasonality check note
            System.out.println("     ── Additional checks ──");
            System.out.println("     Seasonality: cross-check with " + best.pair() + " monthly patterns");
            System.out.println("     Soft signals: VWAP/volume profile check before live");
            System.out.println("     Regime: apply HMM filter for bull/bear/sideways context");

            // Next steps
            System.out.println("\n  📋 NEXT STEPS:");
            System.out.println("     - Apply Neurotrader MCPT permutation test for p-value");
            System.out.println("     - Meta-labeling with trade-level feature model");
            System.out.println("     - Walk-forward optimization (3-fold) for robustness");
            System.out.println("     - Portfolio construction with existing qualified strategies");
        } else {
            System.out.println("  ❌ No strategy passed minimum qualification thresholds.");
            System.out.println("     All rejected — review failure patterns:");
            System.out.println("     - Overfiltering (too few trades)");
            System.out.println("     - Mean-reversion on H1 (systematic losses)");
            System.out.println("     - Sharpe collapse from undersized positions");
            System.out.println("     Consider parameter tuning or new strategy concepts for next run.");
        }

        // Per-strategy best pairs
        System.out.println("\n  📋 PER-STRATEGY BEST PAIRS (top 3 by OOS score):");
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
            .filter(r -> r.oosResult().sharpeRatio() >= MIN_OOS_SHARPE
                && r.oosResult().profitFactor() >= MIN_OOS_PF
                && r.oosResult().maxDrawdownPct() <= MAX_OOS_DD
                && r.oosResult().totalTrades() >= MIN_OOS_TRADES)
            .count();
        long total = results.size();

        System.out.printf("\n  📊 Overall: %d/%d pairs × strategy combinations qualified%n", qualified, total);
        System.out.println("=".repeat(120));
    }

    record ResultRow(String strategyId, String pair, int totalBars, int isBars, int oosBars,
                     BacktestResult isResult, BacktestResult oosResult) {}
}
