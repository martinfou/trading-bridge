package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Generates a sample backtest PDF report and prints the path for upload to Joplin.
 */
public class BacktestReportUploadTest {

    public static void main(String[] args) throws Exception {
        List<Trade> trades = List.of(
            new Trade("EURUSD", Order.Side.BUY, 1.1000, 1.1050, 10000,
                Instant.parse("2026-01-05T00:00:00Z"), Instant.parse("2026-01-10T00:00:00Z")),
            new Trade("EURUSD", Order.Side.SELL, 1.1050, 1.1020, 10000,
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-20T00:00:00Z")),
            new Trade("GBPUSD", Order.Side.BUY, 1.2500, 1.2600, 5000,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-05T00:00:00Z"))
        );

        BacktestResult result = BacktestResult.builder()
            .strategyName("SMA Test")
            .initialCapital(10000.0)
            .finalEquity(10575.0)
            .totalPnl(575.0)
            .totalReturnPct(5.75)
            .totalTrades(3)
            .winningTrades(2)
            .losingTrades(1)
            .winRatePct(66.67)
            .maxDrawdownPct(3.2)
            .avgTradePnl(191.67)
            .sharpeRatio(1.45)
            .sortinoRatio(2.1)
            .profitFactor(2.8)
            .calmarRatio(1.8)
            .totalCommission(15.0)
            .totalSlippage(5.0)
            .equityCurve(List.of(10000.0, 10050.0, 10020.0, 10100.0, 10080.0, 10200.0, 10575.0))
            .trades(trades)
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();

        Path outDir = Path.of("reports");
        Files.createDirectories(outDir);

        BacktestReportGenerator gen = new BacktestReportGenerator(result, "EURUSD", "SMA_Sample", outDir);
        Path pdfPath = gen.generate();

        System.out.println("PDF_PATH:" + pdfPath.toAbsolutePath());
    }
}
