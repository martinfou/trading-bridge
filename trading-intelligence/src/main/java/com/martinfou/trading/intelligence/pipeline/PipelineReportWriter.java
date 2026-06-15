package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.core.agent.PairResult;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.StrategySpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates structured markdown reports for pipeline runs.
 * Saved to _bmad-output/implementation-artifacts/ by default.
 */
public class PipelineReportWriter {

    private static final Path DEFAULT_OUTPUT_DIR = Path.of(
        "_bmad-output/implementation-artifacts/");
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("America/Toronto"));

    private final Path outputDir;

    public PipelineReportWriter() {
        this(DEFAULT_OUTPUT_DIR);
    }

    public PipelineReportWriter(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create report dir: " + outputDir, e);
        }
    }

    /**
     * Generate a report for a completed pipeline run.
     * @return the file path of the generated report
     */
    public Path generate(PipelineResult result) {
        StrategySpec spec = result.spec();
        String filename = String.format("pipeline-report-%s-%s.md",
            spec != null ? spec.name() : "unknown",
            java.time.Instant.now().toString().substring(0, 10));
        Path file = outputDir.resolve(filename);

        var sb = new StringBuilder();
        sb.append("# Pipeline Report — Unified Strategy Engine\n\n");
        sb.append("**Date:** ").append(FMT.format(result.timestamp())).append("\n");
        sb.append("**Duration:** ").append(result.durationMs() / 1000).append("s\n");
        sb.append("**Profile:** ").append(result.profile()).append("\n\n");

        // Verdict
        if (result.qualified()) {
            sb.append("## ✅ Verdict: Qualified\n\n");
        } else {
            sb.append("## ❌ Verdict: Rejected\n\n");
            sb.append("**Reason:** ").append(result.failureReason()).append("\n\n");
        }

        if (spec != null) {
            sb.append("## Strategy Spec\n\n");
            sb.append("| Property | Value |\n");
            sb.append("|----------|-------|\n");
            sb.append("| Name | ").append(spec.name()).append(" |\n");
            sb.append("| Category | ").append(spec.category()).append(" |\n");
            sb.append("| Description | ").append(spec.description()).append(" |\n");
            sb.append("| Inspiration | ").append(spec.inspiration()).append(" |\n");
            sb.append("| Indicators | ").append(String.join(", ", spec.indicators())).append(" |\n");
            sb.append("| Long Entry | ").append(spec.longEntry()).append(" |\n");
            sb.append("| Short Entry | ").append(spec.shortEntry()).append(" |\n");
            sb.append("| Exit | ").append(spec.exitCondition()).append(" |\n");
            sb.append("| SL Mult | ").append(String.format("%.1f", spec.slMultiplier())).append(" |\n");
            sb.append("| TP Mult | ").append(String.format("%.1f", spec.tpMultiplier())).append(" |\n");
            sb.append("| Max Hold | ").append(spec.maxHoldBars()).append(" bars |\n");
            sb.append("\n");
        }

        // Results per pair
        sb.append("## Backtest Results\n\n");
        sb.append("| Pair | PF | Sharpe | DD% | WR% | Trades | PnL | Status |\n");
        sb.append("|------|:--:|:------:|:---:|:---:|:-----:|:---:|:------:|\n");
        for (var pr : result.pairResults()) {
            boolean q = pr.qualified(1.05, 20, 100);
            sb.append(String.format("| %s | %.2f | %.2f | %.1f | %.0f | %d | $%.0f | %s |\n",
                pr.symbol(), pr.pf(), pr.sharpe(), pr.dd(), pr.winRate(),
                pr.trades(), pr.totalPnl(), q ? "✅" : "❌"));
        }
        sb.append("\n");

        // Summary
        sb.append("## Summary\n\n");
        sb.append("- **PF avg:** ").append(String.format("%.2f", result.averagePf())).append("\n");
        sb.append("- **DD max:** ").append(String.format("%.1f%%", result.maxDrawdown())).append("\n");
        sb.append("- **Pairs qualified:** ").append(result.qualifiedPairCount(1.05, 20, 100))
            .append("/").append(result.pairResults().size()).append("\n\n");

        // Lessons
        if (!result.lessonsLearned().isEmpty()) {
            sb.append("## Lessons Learned\n\n");
            for (String lesson : result.lessonsLearned()) {
                sb.append("- ").append(lesson).append("\n");
            }
            sb.append("\n");
        }

        try {
            Files.writeString(file, sb.toString());
            System.out.println("Report saved: " + file);
        } catch (IOException e) {
            System.err.println("Cannot write report: " + e.getMessage());
        }

        return file;
    }
}
