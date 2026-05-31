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
 * Runs the 3 newly designed strategies across all available forex pairs.
 * Strategies: DualEmaMomentum, SessionBreakoutMomentum, WeekdaySession
 * Pairs: EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CAD, NZD/USD, USD/CHF, GBP/JPY
 * Data: H1 2006-2026 (20 years)
 * Costs: 0.5 pips commission (majors), 1 pip (crosses), 1 pip slippage
 */
public class RunThreeStrategies {

    private static final double DEFAULT_CAPITAL = 100_000.0;
    private static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD",
        "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"
    };
    private static final String[] CROSSES = {"GBP_JPY"};

    public static void main(String[] args) throws Exception {
        String yearRange = args.length > 0 ? args[0] : "2006-2025";
        boolean verbose = args.length > 1 && args[1].equals("--verbose");

        System.out.println("=".repeat(80));
        System.out.println("  STRATEGY R&D PIPELINE — 3 New Strategies × All Pairs");
        System.out.println("  Data: H1 " + yearRange + " | Capital: $" + DEFAULT_CAPITAL);
        System.out.println("  Costs: 0.5 pip commission + 1 pip slippage");
        System.out.println("=".repeat(80));

        // Strategy factories: (id, factory, defaultSymbol)
        record StratDef(String id, String desc, java.util.function.Function<String, Strategy> factory) {}
        StratDef[] strats = {
            new StratDef("DualEmaMomentum", "Dual-EMA Momentum w/ Volatility Filter (Structure/Technical)",
                sym -> new DualEmaMomentumStrategy("DualEMA_" + symbolToPair(sym), sym)),
            new StratDef("SessionBreakoutMomentum", "Session Breakout Momentum (News/Sentiment)",
                sym -> new SessionBreakoutMomentumStrategy("SessBreak_" + symbolToPair(sym), sym)),
            new StratDef("WeekdaySession", "Weekday Session Pattern (Seasonality/Calendar)",
                sym -> new WeekdaySessionStrategy("Weekday_" + symbolToPair(sym), sym)),
        };

        List<ResultRow> allResults = new ArrayList<>();

        for (StratDef sd : strats) {
            System.out.println("\n" + "-".repeat(80));
            System.out.println("  STRATEGY: " + sd.id + " — " + sd.desc);
            System.out.println("-".repeat(80));

            for (String pair : PAIRS) {
                String symbol = pair;

                // Load data
                List<Bar> bars;
                try {
                    // loadFromArgs expects: defaultSymbol, symbol, yearRange
                    var loaded = HistoricalDataLoader.loadFromArgs(pair, pair, yearRange);
                    bars = loaded.bars();
                } catch (Exception e) {
                    System.err.println("  ⚠ " + pair + ": data load failed — " + e.getMessage());
                    continue;
                }

                if (bars.size() < 100) {
                    System.out.println("  ⚠ " + pair + ": insufficient data (" + bars.size() + " bars)");
                    continue;
                }

                // Split into IS (70%) and OOS (30%)
                int splitIdx = (int)(bars.size() * 0.7);
                List<Bar> isBars = bars.subList(0, splitIdx);
                List<Bar> oosBars = bars.subList(splitIdx, bars.size());

                // Build costs
                boolean isCross = Arrays.asList(CROSSES).contains(pair);
                double commission = isCross ? 1.0 : 0.5; // pips
                double commissionPerTrade = commission * 10; // rough $ per trade for 10k units
                double slippagePct = 0.0001; // 1 pip

                BacktestExecutionCost costs = new BacktestExecutionCost(commissionPerTrade, 0.0, slippagePct, 0.0, 0.0);

                try {
                    // IS backtest
                    Strategy strategy = sd.factory.apply(symbol);
                    BacktestResult isResult = runBacktest(strategy, symbol, isBars, DEFAULT_CAPITAL, costs);

                    // OOS backtest (fresh strategy instance)
                    Strategy oosStrategy = sd.factory.apply(symbol);
                    BacktestResult oosResult = runBacktest(oosStrategy, symbol, oosBars, DEFAULT_CAPITAL, costs);

                    String pairDisplay = symbolToPair(pair);
                    int totalBars = bars.size();
                    int isBarsCount = isBars.size();
                    int oosBarsCount = oosBars.size();

                    ResultRow row = new ResultRow(sd.id, pairDisplay, totalBars, isBarsCount, oosBarsCount,
                        isResult, oosResult);
                    allResults.add(row);

                    if (verbose) {
                        printResult(row);
                    }

                } catch (Exception e) {
                    System.err.println("  ✗ " + pair + ": backtest failed — " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Print summary table
        printSummary(allResults);

        // Top recommendation
        recommendTop(allResults);
    }

    private static BacktestResult runBacktest(Strategy strategy, String symbol,
                                              List<Bar> bars, double capital,
                                              BacktestExecutionCost costs) {
        return RunContext.forStrategy(
            null, null, strategy, symbol, RunMode.BACKTEST, bars, capital, null, costs
        ).run();
    }

    private static String symbolToPair(String symbol) {
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
        System.out.println("\n" + "=".repeat(100));
        System.out.println("  QUALIFICATION SUMMARY");
        System.out.println("=".repeat(100));
        System.out.printf("%-20s %-10s %6s %7s %7s %4s %7s %7s %7s %4s  %s%n",
            "STRATEGY", "PAIR", "Sharpe", "PF", "DD%", "Tr", "OSharpe", "OPF", "ODD%", "OTr", "STATUS");
        System.out.println("-".repeat(100));

        for (ResultRow r : results) {
            var is = r.isResult();
            var oos = r.oosResult();
            String status;

            // Qualification criteria
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

            System.out.printf("%-20s %-10s %6.2f %7.2f %6.2f%% %4d %7.2f %7.2f %6.2f%% %4d  %s%n",
                r.strategyId(), r.pair(),
                is.sharpeRatio(), is.profitFactor(), is.maxDrawdownPct(), is.totalTrades(),
                oos.sharpeRatio(), oos.profitFactor(), oos.maxDrawdownPct(), oos.totalTrades(),
                status);
        }
        System.out.println("-".repeat(100));
    }

    private static void recommendTop(List<ResultRow> results) {
        System.out.println("\n  📊 TOP RECOMMENDATION");

        // Find best OOS Sharpe with at least 30 trades and PF > 1.0
        ResultRow best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ResultRow r : results) {
            var oos = r.oosResult();
            if (oos.totalTrades() < 30) continue;
            if (oos.profitFactor() <= 1.0) continue;

            double score = oos.sharpeRatio() * 0.4 + oos.profitFactor() * 0.3
                - oos.maxDrawdownPct() * 0.02 + Math.min(oos.totalTrades(), 200) * 0.01;

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

            // Seasonality & soft signal consistency check
            System.out.println("     Consistency: IS→OOS PF ratio = "
                + String.format("%.0f%%", (oos.profitFactor() / is.profitFactor() * 100)));
        } else {
            System.out.println("  ❌ No strategy passed minimum qualification thresholds.");
            System.out.println("     All strategies rejected — consider parameter tuning or new ideas.");
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
                .forEach(r -> System.out.printf("%s(S=%.2f/PF=%.2f) ",
                    r.pair(), r.oosResult().sharpeRatio(), r.oosResult().profitFactor()));
            System.out.println();
        }
    }

    record ResultRow(String strategyId, String pair, int totalBars, int isBars, int oosBars,
                     BacktestResult isResult, BacktestResult oosResult) {}
}
