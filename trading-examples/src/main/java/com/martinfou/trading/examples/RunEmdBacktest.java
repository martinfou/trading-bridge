package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.backtest.persistence.BacktestPersistenceService;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.MesTurtleStrategy;
import com.martinfou.trading.strategies.longterm.LtDoubleMA;
import com.martinfou.trading.strategies.longterm.LtEfficiencyRatio;
import com.martinfou.trading.strategies.longterm.LtRangeBreakout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * RunEmdBacktest — Comprehensive backtesting of CME E-mini S&P MidCap 400 (EMD) futures.
 *
 * Contract Specifications:
 *   - Symbol: EMD (E-mini S&P MidCap 400)
 *   - Multiplier: $100.0 per index point (1.0 pt = $100.00)
 *   - Min Tick: 0.10 pt ($10.00 / tick)
 *   - Commission: $1.25 per contract (IBKR Tiered E-mini rate)
 *   - Slippage: 0.00003 (~0.10 pt / 1 tick @ 3,000 index level)
 *   - Initial Capital: $50,000.00 (Standard margin buffer for 1 contract)
 *   - Execution Realism: TRADE_THROUGH + Rollover tracking enabled
 */
public class RunEmdBacktest {

    private static final String SYMBOL = "EMD";
    private static final double DEFAULT_CAPITAL = 50_000.0;
    private static final double COMMISSION_PER_CONTRACT = 1.25;
    private static final double SLIPPAGE_PCT = 0.00003; // ~0.10 pt on 3,000 index = $10.00
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
        System.out.println("=".repeat(130));
        System.out.println("  CME E-MINI S&P MIDCAP 400 (EMD) STRATEGY BACKTEST SUITE");
        System.out.println("  Contract: EMD | Multiplier: $100/pt | Tick: 0.10 ($10/tick) | IBKR Comm: $1.25/contract");
        System.out.println("  Initial Capital: $" + String.format("%,.2f", DEFAULT_CAPITAL) + " | Execution: TRADE_THROUGH + Rollover");
        System.out.println("=".repeat(130));

        List<StrategySpec> strategies = List.of(
            new StrategySpec(
                "Turtle_55_20_LongOnly",
                "Donchian 55/20 Breakout (Long-Only)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, true, 0)
            ),
            new StrategySpec(
                "Turtle_55_20_SMA200",
                "Donchian 55/20 (Long-Only + 200 SMA Filter)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, true, 200)
            ),
            new StrategySpec(
                "Turtle_20_10_LongOnly",
                "Donchian 20/10 Breakout (Long-Only System 1)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 20, 10, 1, true, 0)
            ),
            new StrategySpec(
                "Turtle_55_20_Symmetric",
                "Donchian 55/20 Breakout (Long & Short)",
                (name, sym) -> new MesTurtleStrategy(name, sym, 55, 20, 1, false, 0)
            ),
            new StrategySpec(
                "LtRangeBreakout",
                "Daily High/Low Range Breakout + ATR Stop",
                (name, sym) -> new LtRangeBreakout(name, sym)
            ),
            new StrategySpec(
                "LtDoubleMA",
                "Double Moving Average Crossover (EMA 21 / SMA 55)",
                (name, sym) -> new LtDoubleMA(name, sym)
            ),
            new StrategySpec(
                "LtEfficiencyRatio",
                "Kaufman ER(20) > 0.5 Trend Following + Trailing Stop",
                (name, sym) -> new LtEfficiencyRatio(name, sym)
            )
        );

        String[] periods = {"2024-2026", "2020-2026", "2018-2023", "2006-2026"};
        List<BacktestRecord> records = new ArrayList<>();

        // Load bars for each period
        Map<String, List<Bar>> periodBars = new HashMap<>();
        for (String p : periods) {
            try {
                List<Bar> b = HistoricalDataLoader.loadYearSpec(SYMBOL, p, "H1", BARS_DIR);
                if (!b.isEmpty()) {
                    periodBars.put(p, b);
                    System.out.printf("  Loaded %s [%s]: %d bars (From %s to %s)%n",
                        SYMBOL, p, b.size(),
                        TimeConventions.toDisplayString(b.getFirst().timestamp()),
                        TimeConventions.toDisplayString(b.getLast().timestamp()));
                }
            } catch (IOException e) {
                System.err.printf("  ✗ Error loading %s %s: %s%n", SYMBOL, p, e.getMessage());
            }
        }

        for (StrategySpec spec : strategies) {
            System.out.println("\n  " + "-".repeat(115));
            System.out.println("  ▶ Strategy: " + spec.id() + " — " + spec.description());
            System.out.println("  " + "-".repeat(115));

            for (String p : periods) {
                List<Bar> bars = periodBars.get(p);
                if (bars == null || bars.isEmpty()) continue;

                String runName = spec.id() + "_" + SYMBOL + "_" + p;
                Strategy instance = spec.factory().apply(runName, SYMBOL);

                BacktestEngine engine = new BacktestEngine(instance, bars, DEFAULT_CAPITAL)
                    .withFillMode(FillMode.TRADE_THROUGH)
                    .withCommissionFixed(COMMISSION_PER_CONTRACT)
                    .withSlippagePct(SLIPPAGE_PCT)
                    .withRollover(true)
                    .withMarginTracking(true);

                try {
                    BacktestResult res = engine.run();
                    records.add(new BacktestRecord(spec.id(), SYMBOL, p, bars.size(), res));

                    // Persist run to SQLite events.db for Trading Bridge UI
                    try {
                        String runId = UUID.randomUUID().toString();
                        BacktestExecutionCost costs = new BacktestExecutionCost(COMMISSION_PER_CONTRACT, 0.0, SLIPPAGE_PCT, 0.0, 0.0);
                        RunContext ctx = RunContext.forStrategy(
                            runId,
                            runName,
                            instance,
                            SYMBOL,
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

                    System.out.printf("    %-12s | PnL: %11s (%6.2f%%) | Sharpe: %5.2f | Sortino: %5.2f | MaxDD: %6.2f%% | PF: %5.2f | Win%%: %5.1f%% | Trades: %4d (W:%3d L:%3d) | AvgTrd: %8s%n",
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

        // Summary Table
        System.out.println("\n" + "=".repeat(140));
        System.out.println("  SUMMARY: CME E-MINI S&P MIDCAP 400 (EMD) — RANKED BY TOTAL PNL");
        System.out.println("=".repeat(140));
        System.out.printf("%-26s | %-10s | %13s | %8s | %7s | %7s | %8s | %6s | %6s | %7s | %10s%n",
            "Strategy", "Period", "Net PnL ($)", "Ret (%)", "Sharpe", "Sortino", "Max DD", "PF", "Win %", "Trades", "Comm/Slip");
        System.out.println("-".repeat(140));

        List<BacktestRecord> sorted = records.stream()
            .sorted(Comparator.comparingDouble((BacktestRecord r) -> r.result().totalPnl()).reversed())
            .toList();

        for (BacktestRecord rec : sorted) {
            BacktestResult r = rec.result();
            double costs = r.totalCommission() + r.totalSlippage();
            System.out.printf("%-26s | %-10s | %13s | %7.2f%% | %7.2f | %7.2f | %7.2f%% | %6.2f | %5.1f%% | %7d | %10s%n",
                rec.strategyId(),
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
