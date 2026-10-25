package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.MesTurtleStrategy;
import com.martinfou.trading.strategies.longterm.LtDoubleMA;
import com.martinfou.trading.strategies.longterm.LtEfficiencyRatio;
import com.martinfou.trading.strategies.longterm.LtRangeBreakout;

import com.martinfou.trading.backtest.persistence.BacktestPersistenceService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Institutional-grade CME Futures Backtest Runner.
 *
 * Backtests quantitative strategies on CME Micro Futures:
 *  - MES (Micro E-mini S&P 500)
 *  - MNQ (Micro E-mini Nasdaq 100)
 *  - M2K (Micro E-mini Russell 2000)
 *
 * Execution Realism:
 *  - FillMode: TRADE_THROUGH (orders only fill if bar price penetrates limit/entry)
 *  - Commission: $0.62 per contract (standard IBKR CME Micro rate)
 *  - Slippage: 0.00005 (~0.25 pt / 1 tick on MES)
 *  - Margin Tracking: Enabled
 *  - Rollover Accounting: Enabled
 */
public class RunCmeFuturesBacktest {

    private static final double DEFAULT_CAPITAL = 10_000.0;
    private static final double COMMISSION_PER_CONTRACT = 0.62;
    private static final double SLIPPAGE_PCT = 0.00005; // ~1 tick (0.25 pt on MES @ 5000 = $1.25)
    private static final Path BARS_DIR = Path.of("data/historical/bars");

    public record StrategySpec(
        String id,
        String description,
        java.util.function.BiFunction<String, String, Strategy> factory
    ) {}

    public record BacktestRecord(
        String strategyId,
        String symbol,
        String period,
        int barCount,
        BacktestResult result
    ) {}

    public static void main(String[] args) {
        System.out.println("=".repeat(120));
        System.out.println("  CME MICRO FUTURES STRATEGY BACKTEST SUITE");
        System.out.println("  Contracts: MES (Micro S&P 500) | MNQ (Micro Nasdaq) | M2K (Micro Russell)");
        System.out.println("  Execution Realism: TRADE_THROUGH | Comm: $0.62/contract | Slip: 1 tick | Rollover: true");
        System.out.println("  Initial Capital: $" + String.format("%,.2f", DEFAULT_CAPITAL));
        System.out.println("=".repeat(120));

        List<StrategySpec> strategies = List.of(
            // 1. Classic Turtle Breakout 55/20 Long-Only
            new StrategySpec(
                "Turtle_55_20_LongOnly",
                "Donchian 55/20 Breakout (Long-Only)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, true, 0)
            ),
            // 2. Classic Turtle Breakout 55/20 Symmetric (Long & Short)
            new StrategySpec(
                "Turtle_55_20_Symmetric",
                "Donchian 55/20 Breakout (Long & Short)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, false, 0)
            ),
            // 3. Turtle Breakout 55/20 with 200-bar Trend Filter
            new StrategySpec(
                "Turtle_55_20_SMA200",
                "Donchian 55/20 Breakout + 200-SMA Regime Filter (Long-Only)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, true, 200)
            ),
            // 4. Turtle System 1 (Faster 20/10 Breakout, Long-Only)
            new StrategySpec(
                "Turtle_20_10_LongOnly",
                "Donchian 20/10 Faster Breakout (Long-Only)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 20, 10, 1, true, 0)
            ),
            // 5. LtRangeBreakout with ATR Trailing Stop
            new StrategySpec(
                "LtRangeBreakout",
                "Donchian 20 + ATR(14) 2x SL / 4x TP Trailing",
                (name, sym) -> new LtRangeBreakout(name, sym)
            ),
            // 6. LtDoubleMA (EMA 50 / SMA 200 Golden Cross)
            new StrategySpec(
                "LtDoubleMA",
                "Golden/Death Cross EMA(50)/SMA(200) + ATR SL/TP",
                (name, sym) -> new LtDoubleMA(name, sym)
            ),
            // 7. LtEfficiencyRatio (Kaufman ER Trend Following)
            new StrategySpec(
                "LtEfficiencyRatio",
                "Kaufman ER(20) > 0.5 Trend Following + Trailing Stop",
                (name, sym) -> new LtEfficiencyRatio(name, sym)
            )
        );

        String[] symbols = {"MES", "MNQ", "M2K"};
        String[] periods = {"2020-2026", "2006-2026", "2018-2023", "2024-2026"};

        List<BacktestRecord> records = new ArrayList<>();

        for (String symbol : symbols) {
            System.out.println("\n" + "#".repeat(120));
            System.out.println("  INSTRUMENT: " + symbol + " (" + getContractName(symbol) + ")");
            System.out.println("#".repeat(120));

            // Load bars for each period
            Map<String, List<Bar>> periodBars = new HashMap<>();
            for (String p : periods) {
                try {
                    List<Bar> b = HistoricalDataLoader.loadYearSpec(symbol, p, "H1", BARS_DIR);
                    if (!b.isEmpty()) {
                        periodBars.put(p, b);
                        System.out.printf("  Loaded %s [%s]: %d bars (From %s to %s)%n",
                            symbol, p, b.size(),
                            TimeConventions.toDisplayString(b.getFirst().timestamp()),
                            TimeConventions.toDisplayString(b.getLast().timestamp()));
                    }
                } catch (IOException e) {
                    System.err.printf("  ✗ Error loading %s %s: %s%n", symbol, p, e.getMessage());
                }
            }

            for (StrategySpec spec : strategies) {
                System.out.println("\n  " + "-".repeat(110));
                System.out.println("  ▶ Strategy: " + spec.id() + " — " + spec.description());
                System.out.println("  " + "-".repeat(110));

                for (String p : periods) {
                    List<Bar> bars = periodBars.get(p);
                    if (bars == null || bars.isEmpty()) continue;

                    String runName = spec.id() + "_" + symbol + "_" + p;
                    Strategy instance = spec.factory().apply(runName, symbol);

                    BacktestEngine engine = new BacktestEngine(instance, bars, DEFAULT_CAPITAL)
                        .withFillMode(FillMode.TRADE_THROUGH)
                        .withCommissionFixed(COMMISSION_PER_CONTRACT)
                        .withSlippagePct(SLIPPAGE_PCT)
                        .withRollover(true)
                        .withMarginTracking(true);

                    try {
                        BacktestResult res = engine.run();
                        records.add(new BacktestRecord(spec.id(), symbol, p, bars.size(), res));

                        // Persist run to SQLite events.db for Trading Bridge UI
                        try {
                            String runId = UUID.randomUUID().toString();
                            BacktestExecutionCost costs = new BacktestExecutionCost(COMMISSION_PER_CONTRACT, 0.0, SLIPPAGE_PCT, 0.0, 0.0);
                            RunContext ctx = RunContext.forStrategy(
                                runId,
                                runName,
                                instance,
                                symbol,
                                RunMode.BACKTEST,
                                bars,
                                DEFAULT_CAPITAL,
                                null,
                                costs,
                                "H1",
                                "H1"
                            );
                            BacktestPersistenceService.save(runId, ctx, res);
                        } catch (Exception pe) {
                            System.err.println("    ⚠ Failed to persist run " + runName + ": " + pe.getMessage());
                        }

                        System.out.printf("    %-12s | PnL: %10s (%6.2f%%) | Sharpe: %5.2f | Sortino: %5.2f | MaxDD: %6.2f%% | PF: %5.2f | Win%%: %5.1f%% | Trades: %4d (W:%3d L:%3d) | AvgTrd: %7s%n",
                            p,
                            String.format("$%,.2f", res.totalPnl()),
                            res.totalReturnPct(),
                            res.sharpeRatio(),
                            res.sortinoRatio(),
                            res.maxDrawdownPct(),
                            res.profitFactor(),
                            res.winRatePct(),
                            res.totalTrades(),
                            res.winningTrades(),
                            res.losingTrades(),
                            String.format("$%,.2f", res.avgTradePnl())
                        );
                    } catch (Exception ex) {
                        System.err.printf("    ✗ %s [%s] execution error: %s%n", spec.id(), p, ex.getMessage());
                    }
                }
            }
        }

        // Print Global Comparative Table
        printGlobalSummaryTable(records);
    }

    private static String getContractName(String sym) {
        return switch (sym) {
            case "MES" -> "Micro E-mini S&P 500 (Tick $1.25, Mult 5x)";
            case "MNQ" -> "Micro E-mini Nasdaq 100 (Tick $0.50, Mult 2x)";
            case "M2K" -> "Micro E-mini Russell 2000 (Tick $0.50, Mult 5x)";
            default -> sym;
        };
    }

    private static void printGlobalSummaryTable(List<BacktestRecord> records) {
        System.out.println("\n\n" + "=".repeat(140));
        System.out.println("  GLOBAL PERFORMANCE COMPARISON (Sorted by Sharpe Ratio on 2020-2026)");
        System.out.println("=".repeat(140));
        System.out.printf("%-24s | %-4s | %-10s | %12s | %8s | %7s | %7s | %8s | %6s | %6s | %7s | %6s%n",
            "Strategy", "Sym", "Period", "Net PnL ($)", "Ret (%)", "Sharpe", "Sortino", "Max DD", "PF", "Win %", "Trades", "Verdict");
        System.out.println("-".repeat(140));

        // Filter and sort for the primary comparison window 2020-2026
        List<BacktestRecord> primary = records.stream()
            .filter(r -> "2020-2026".equals(r.period()))
            .sorted(Comparator.comparingDouble((BacktestRecord r) -> r.result().sharpeRatio()).reversed())
            .toList();

        for (BacktestRecord rec : primary) {
            BacktestResult r = rec.result();
            String verdict = (r.sharpeRatio() >= 1.0 && r.profitFactor() >= 1.3 && r.maxDrawdownPct() <= 25.0 && r.totalTrades() >= 30)
                ? "✅ PASS"
                : (r.totalPnl() > 0 ? "⚠️ MIXED" : "❌ FAIL");

            System.out.printf("%-24s | %-4s | %-10s | %12s | %7.2f%% | %7.2f | %7.2f | %7.2f%% | %6.2f | %5.1f%% | %7d | %6s%n",
                rec.strategyId(),
                rec.symbol(),
                rec.period(),
                String.format("$%,.2f", r.totalPnl()),
                r.totalReturnPct(),
                r.sharpeRatio(),
                r.sortinoRatio(),
                r.maxDrawdownPct(),
                r.profitFactor(),
                r.winRatePct(),
                r.totalTrades(),
                verdict
            );
        }

        System.out.println("=".repeat(140));

        // Also display 20-year full historical run (2006-2026)
        System.out.println("\n" + "=".repeat(140));
        System.out.println("  FULL 20-YEAR SECULAR RUN (2006-2026) — Robustness & Drawdown Across Market Cycles");
        System.out.println("=".repeat(140));
        System.out.printf("%-24s | %-4s | %-10s | %12s | %8s | %7s | %7s | %8s | %6s | %6s | %7s | %10s%n",
            "Strategy", "Sym", "Period", "Net PnL ($)", "Ret (%)", "Sharpe", "Sortino", "Max DD", "PF", "Win %", "Trades", "Comm/Slip");
        System.out.println("-".repeat(140));

        List<BacktestRecord> fullHistory = records.stream()
            .filter(r -> "2006-2026".equals(r.period()))
            .sorted(Comparator.comparingDouble((BacktestRecord r) -> r.result().totalPnl()).reversed())
            .toList();

        for (BacktestRecord rec : fullHistory) {
            BacktestResult r = rec.result();
            double costs = r.totalCommission() + r.totalSlippage();
            System.out.printf("%-24s | %-4s | %-10s | %12s | %7.2f%% | %7.2f | %7.2f | %7.2f%% | %6.2f | %5.1f%% | %7d | %10s%n",
                rec.strategyId(),
                rec.symbol(),
                rec.period(),
                String.format("$%,.2f", r.totalPnl()),
                r.totalReturnPct(),
                r.sharpeRatio(),
                r.sortinoRatio(),
                r.maxDrawdownPct(),
                r.profitFactor(),
                r.winRatePct(),
                r.totalTrades(),
                String.format("$%,.2f", costs)
            );
        }
        System.out.println("=".repeat(140));
    }
}
