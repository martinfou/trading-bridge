package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.FillMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.strategies.sqimported.Strategy_Gold_Breakout_M30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 2: Institutional Backtest & Cost Model Validation for Micro Gold (MGC).
 *
 * <p>Drives {@link Strategy_Gold_Breakout_M30} on real COMEX M30 bars downloaded from IB Gateway.
 * Enforces real-world COMEX execution costs ($0.62/contract fee, 1 tick slippage) and zero-martingale sizing.</p>
 */
public class RunMgcGoldBreakoutBacktest {

    private static final Logger log = LoggerFactory.getLogger(RunMgcGoldBreakoutBacktest.class);

    private static final double INITIAL_CAPITAL = 11_270.0; // Current paper account balance in USD
    private static final double COMMISSION_PER_CONTRACT = 0.62; // IBKR COMEX Micro Gold rate
    private static final double SLIPPAGE_PRICE = 0.10; // 1 tick ($0.10/oz = $1.00 P&L impact)

    public static void main(String[] args) throws Exception {
        Path csvPath = Paths.get("data/historical/csv/MGC_M30_2026.csv");
        if (!csvPath.toFile().exists()) {
            log.error("Data file {} not found. Run FetchMgcBars first.", csvPath);
            System.exit(1);
        }

        List<Bar> bars = loadIbkrCsv(csvPath, "MGC");
        log.info("Loaded {} historical M30 bars for MGC ({} -> {})",
            bars.size(),
            TimeConventions.toDisplayString(bars.get(0).timestamp()),
            TimeConventions.toDisplayString(bars.get(bars.size() - 1).timestamp()));

        Strategy_Gold_Breakout_M30 strategy = new Strategy_Gold_Breakout_M30("MGC");

        BacktestEngine engine = new BacktestEngine(strategy, bars, INITIAL_CAPITAL)
            .withCommissionFixed(COMMISSION_PER_CONTRACT)
            .withSlippageFixed(SLIPPAGE_PRICE)
            .withFillMode(FillMode.TOUCH)
            .withMarginTracking(true);

        log.info("Running backtest with BacktestEngine on MGC...");
        BacktestResult result = engine.run();

        printReport(result);
    }

    private static List<Bar> loadIbkrCsv(Path path, String symbol) throws Exception {
        List<Bar> bars = new ArrayList<>();
        ZoneId eastern = ZoneId.of("America/New_York");
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 6) continue;
                String rawDate = p[0].trim().replace(" US/Eastern", "");
                String cleanDate = rawDate.replaceAll("\\s+", " ");
                LocalDateTime ldt = LocalDateTime.parse(cleanDate, DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
                var instant = ldt.atZone(eastern).toInstant();

                double open = Double.parseDouble(p[1]);
                double high = Double.parseDouble(p[2]);
                double low = Double.parseDouble(p[3]);
                double close = Double.parseDouble(p[4]);
                long volume = Long.parseLong(p[5]);

                bars.add(new Bar(symbol, instant, open, high, low, close, volume));
            }
        }
        return bars;
    }

    private static void printReport(BacktestResult r) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  MGC MICRO GOLD BREAKOUT M30 — BACKTEST & REAL COST AUDIT");
        System.out.println("  Strategy: " + r.strategyName());
        System.out.println("  Symbol: MGC (COMEX Micro Gold, 10 oz multiplier)");
        System.out.println("  Costs: $" + COMMISSION_PER_CONTRACT + "/contract comm | 1 tick ($" + SLIPPAGE_PRICE + ") slippage");
        System.out.println("  Sizing: Strictly 1 contract (ZERO Martingale Mandate)");
        System.out.println("=".repeat(80));

        System.out.printf("  Initial Balance:    $%,10.2f%n", r.initialCapital());
        System.out.printf("  Ending Equity:      $%,10.2f%n", r.finalEquity());
        System.out.printf("  Total Net P&L:      $%,10.2f (%+.2f%%)%n", r.totalPnl(), r.totalReturnPct());
        System.out.printf("  Max Drawdown:        %.2f%%%n", r.maxDrawdownPct());
        System.out.printf("  Profit Factor:       %.2f%n", r.profitFactor());
        System.out.printf("  Win Rate:            %.1f%% (%d wins / %d losses)%n",
            r.winRatePct(), r.winningTrades(), r.losingTrades());
        System.out.printf("  Total Trades:        %d%n", r.totalTrades());
        System.out.printf("  Total Commission:   $%,10.2f%n", r.totalCommission());
        System.out.printf("  Total Slippage:     $%,10.2f%n", r.totalSlippage());
        System.out.printf("  Sharpe Ratio:        %.2f%n", r.sharpeRatio());
        System.out.printf("  Sortino Ratio:       %.2f%n", r.sortinoRatio());
        System.out.println("-".repeat(80));

        List<Trade> trades = r.trades();
        if (trades != null && !trades.isEmpty()) {
            System.out.println("  Sample Trade Executions (Last 10 trades):");
            int start = Math.max(0, trades.size() - 10);
            for (int i = start; i < trades.size(); i++) {
                Trade t = trades.get(i);
                System.out.printf("   #%02d: %s | %s %.1f @ %.2f -> %.2f | P&L: $%+7.2f%n",
                    i + 1,
                    TimeConventions.toDisplayString(t.entryTime()),
                    t.side(), t.quantity(), t.entryPrice(), t.exitPrice(),
                    t.pnl());
            }
        }
        System.out.println("=".repeat(80) + "\n");
    }
}
