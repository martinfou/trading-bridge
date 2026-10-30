package com.martinfou.trading.runtime.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Side-by-side fill log comparison report across execution modes (PRD §5 F4 & §7).
 *
 * <p>Reads the backtest and paper/live JSONL fill logs for a {@code (strategy, symbol)} pair and
 * computes sample sizes, median slippage per mode, median commission per mode, and the
 * live/paper minus backtest deltas.</p>
 */
public final class FillLogComparisonReport {

    public record ModeSummary(
        String mode,
        int sampleSize,
        double medianSlippage,
        double medianCommission
    ) {}

    public record ComparisonResult(
        String strategy,
        String symbol,
        ModeSummary baseline,
        ModeSummary target,
        double medianSlippageDelta,
        double medianCommissionDelta
    ) {
        public String formatTable() {
            StringBuilder sb = new StringBuilder();
            sb.append("================================================================================\n");
            sb.append(String.format(Locale.US, "  FILL LOG COMPARISON REPORT — %s / %s%n", strategy, symbol));
            sb.append("================================================================================\n");
            sb.append(String.format(Locale.US, "  %-16s | %12s | %16s | %18s%n",
                "Mode", "Sample Size", "Median Slippage", "Median Commission"));
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append(String.format(Locale.US, "  %-16s | %12d | %16.4f | $%17.4f%n",
                baseline.mode(), baseline.sampleSize(), baseline.medianSlippage(), baseline.medianCommission()));
            sb.append(String.format(Locale.US, "  %-16s | %12d | %16.4f | $%17.4f%n",
                target.mode(), target.sampleSize(), target.medianSlippage(), target.medianCommission()));
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append(String.format(Locale.US, "  Delta (%s - %s): slippage = %+.4f (n=%d vs %d) | commission = $%+.4f%n",
                target.mode(), baseline.mode(),
                medianSlippageDelta, target.sampleSize(), baseline.sampleSize(),
                medianCommissionDelta));
            sb.append("================================================================================\n");
            return sb.toString();
        }
    }

    private FillLogComparisonReport() {}

    public static ComparisonResult compare(
        Path fillsDir,
        String strategy,
        String symbol,
        String baselineMode,
        String targetMode
    ) throws IOException {
        Objects.requireNonNull(fillsDir, "fillsDir is required");
        Path baselineFile = FillLogWriter.forRun(fillsDir, strategy, symbol, baselineMode).filePath();
        Path targetFile = FillLogWriter.forRun(fillsDir, strategy, symbol, targetMode).filePath();
        List<FillLogRecord> baselineRecords = FillLogWriter.readAll(baselineFile);
        List<FillLogRecord> targetRecords = FillLogWriter.readAll(targetFile);
        return compareRecords(strategy, symbol, baselineMode, baselineRecords, targetMode, targetRecords);
    }

    public static ComparisonResult compareRecords(
        String strategy,
        String symbol,
        String baselineMode,
        List<FillLogRecord> baselineRecords,
        String targetMode,
        List<FillLogRecord> targetRecords
    ) {
        ModeSummary baseSummary = summarize(baselineMode, baselineRecords);
        ModeSummary targetSummary = summarize(targetMode, targetRecords);
        double slipDelta = round4(targetSummary.medianSlippage() - baseSummary.medianSlippage());
        double commDelta = round4(targetSummary.medianCommission() - baseSummary.medianCommission());
        return new ComparisonResult(
            strategy,
            symbol,
            baseSummary,
            targetSummary,
            slipDelta,
            commDelta
        );
    }

    public static ModeSummary summarize(String mode, List<FillLogRecord> records) {
        if (records == null || records.isEmpty()) {
            return new ModeSummary(mode, 0, 0.0, 0.0);
        }
        double[] slippages = records.stream().mapToDouble(FillLogRecord::computedSlippage).toArray();
        double[] commissions = records.stream().mapToDouble(FillLogRecord::commission).toArray();
        return new ModeSummary(
            mode,
            records.size(),
            round4(median(slippages)),
            round4(median(commissions))
        );
    }

    static double median(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 1) {
            return copy[mid];
        }
        return (copy[mid - 1] + copy[mid]) / 2.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}

