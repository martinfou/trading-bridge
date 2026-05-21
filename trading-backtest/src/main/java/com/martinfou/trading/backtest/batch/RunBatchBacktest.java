package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.batch.BatchBacktestRunner;
import com.martinfou.trading.backtest.batch.BatchBacktestRunner.BatchComparisonReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * CLI entry point for the batch backtest runner.
 * <p>
 * Example:
 * <pre>{@code
 * java -cp trading-bridge.jar com.martinfou.trading.backtest.batch.RunBatchBacktest \
 *     --data data/historical/dukascopy/gbpusd-h1-bid-2025-01-01-2025-05-19.csv \
 *     --symbol GBP_USD \
 *     --capital 50000
 * }</pre>
 */
public class RunBatchBacktest {

    private static final Logger log = LoggerFactory.getLogger(RunBatchBacktest.class);

    public static void main(String[] args) {
        Path dataFile = null;
        String symbol = "GBP_JPY";
        double capital = 50_000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> dataFile = Path.of(args[++i]);
                case "--symbol" -> symbol = args[++i];
                case "--capital" -> capital = Double.parseDouble(args[++i]);
            }
        }

        // Default data file
        if (dataFile == null) {
            dataFile = Path.of("data/historical/dukascopy/gbpusd-h1-bid-2025-01-01-2025-05-19.csv");
            log.info("No --data specified, defaulting to {}", dataFile);
        }

        BatchBacktestRunner runner = new BatchBacktestRunner()
            .withDataFile(dataFile)
            .withSymbol(symbol)
            .withInitialCapital(capital)
            .withCommissionPct(0.0003);

        log.info("🏁 Batch Backtest Runner");
        log.info("   Data:   {}", dataFile);
        log.info("   Symbol: {}", symbol);
        log.info("   Capital: ${}", String.format("%,.0f", capital));
        log.info("");

        BatchComparisonReport report = runner.runAll();

        log.info("");
        if (report.results().isEmpty()) {
            log.warn("No strategy results generated.");
            return;
        }

        log.info("═══════════════════════════════════════════════════");
        log.info("🏆 RANKING (by Sharpe ratio):");
        log.info("═══════════════════════════════════════════════════");
        log.info(String.format("%-28s %8s %8s %8s %8s %6s %10s",
            "Strategy", "Return%", "Sharpe", "PF", "WinRate", "Trades", "Time(ms)"));
        log.info("─".repeat(76));

        for (BatchBacktestRunner.StrategyResult r : report.results()) {
            log.info(String.format("%-28s %7.2f%% %8.2f %7.2f %7.1f%% %6d %10d",
                r.displayName(),
                r.totalReturnPct(),
                r.sharpeRatio(),
                r.profitFactor(),
                r.winRate(),
                r.totalTrades(),
                r.elapsedMs()));
        }

        log.info("─".repeat(76));
        BatchBacktestRunner.StrategyResult top = report.topBySharpe();
        log.info("🏆 BEST:  {} (Sharpe: {:.2f}, Return: {:.2f}%)",
            top.displayName(), top.sharpeRatio(), top.totalReturnPct());

        System.out.println();
        System.out.println(report.toJson());
    }
}
