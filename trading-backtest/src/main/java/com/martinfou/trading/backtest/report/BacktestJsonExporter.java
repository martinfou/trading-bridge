package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Trade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Exports a {@link BacktestResult} to the JSON schema expected by the
 * trading-dashboard Laravel application (Backtest show/integration).
 *
 * <p>No external JSON library required — output is produced via
 * straightforward string concatenation with proper escaping.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * BacktestResult result = engine.run();
 * BacktestJsonExporter exporter = new BacktestJsonExporter(result);
 * exporter.writeJson(outputDir.resolve("my_strategy_report.json"));
 * }</pre>
 *
 * <h3>Output JSON Schema</h3>
 * <pre>{@code
 * {
 *   "strategyName": "...",
 *   "period": { "from": "...", "to": "..." },
 *   "metrics": { ... metrics object ... },
 *   "trades": [ ... array of trade objects ... ],
 *   "equityCurve": [ ... array of equity values ... ]
 * }
 * }</pre>
 */
public class BacktestJsonExporter {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final BacktestResult result;

    public BacktestJsonExporter(BacktestResult result) {
        this.result = result;
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Returns the complete report as a JSON string.
     */
    public String toJson() {
        return "{\n"
            + "  \"strategyName\": " + quote(result.strategyName()) + ",\n"
            + periodJson() + ",\n"
            + metricsJson() + ",\n"
            + equityCurveJson() + ",\n"
            + tradesJson() + "\n"
            + "}";
    }

    /**
     * Writes the JSON report to the specified file path.
     *
     * @param outputPath destination file
     * @return the output path
     * @throws IOException if the file cannot be written
     */
    public Path writeJson(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, toJson());
        return outputPath;
    }

    // ---------------------------------------------------------------
    //  JSON builders
    // ---------------------------------------------------------------

    private String periodJson() {
        return "  \"period\": {\n"
            + "    \"from\": " + quote(formatInstant(result.periodStart())) + ",\n"
            + "    \"to\": " + quote(formatInstant(result.periodEnd())) + "\n"
            + "  }";
    }

    private String metricsJson() {
        return "  \"metrics\": {\n"
            + "    \"totalTrades\": " + result.totalTrades() + ",\n"
            + "    \"winningTrades\": " + result.winningTrades() + ",\n"
            + "    \"losingTrades\": " + result.losingTrades() + ",\n"
            + "    \"winRate\": " + round1(result.winRatePct()) + ",\n"
            + "    \"totalReturn\": " + round2(result.totalReturnPct()) + ",\n"
            + "    \"initialBalance\": " + round2(result.initialCapital()) + ",\n"
            + "    \"finalBalance\": " + round2(result.finalEquity()) + ",\n"
            + "    \"maxDrawdown\": " + round2(result.maxDrawdownPct()) + ",\n"
            + "    \"profitFactor\": " + round3(result.profitFactor()) + ",\n"
            + "    \"sharpeRatio\": " + round2(result.sharpeRatio()) + ",\n"
            + "    \"avgWin\": " + round2(avgWin()) + ",\n"
            + "    \"avgLoss\": " + round2(avgLoss()) + ",\n"
            + "    \"maxConsecutiveWins\": " + maxConsecutiveWins() + ",\n"
            + "    \"maxConsecutiveLosses\": " + maxConsecutiveLosses() + ",\n"
            + "    \"sortinoRatio\": " + round2(result.sortinoRatio()) + ",\n"
            + "    \"calmarRatio\": " + round2(result.calmarRatio()) + ",\n"
            + "    \"avgTradePnl\": " + round2(result.avgTradePnl()) + ",\n"
            + "    \"totalCommission\": " + round2(result.totalCommission()) + ",\n"
            + "    \"totalSlippage\": " + round2(result.totalSlippage()) + "\n"
            + "  }";
    }

    private String equityCurveJson() {
        List<Double> curve = result.equityCurve();
        if (curve == null || curve.isEmpty()) {
            return "  \"equityCurve\": []";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  \"equityCurve\": [\n");
        for (int i = 0; i < curve.size(); i++) {
            sb.append("    ").append(String.format("%.2f", curve.get(i)));
            if (i < curve.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    @SuppressWarnings("StringBufferReplaceableWithString")
    private String tradesJson() {
        List<Trade> trades = result.trades();
        if (trades == null || trades.isEmpty()) {
            return "  \"trades\": []";
        }

        // Compute additional trade-level metrics
        List<String> directions = trades.stream()
            .map(t -> t.side() == com.martinfou.trading.core.Order.Side.BUY ? "LONG" : "SHORT")
            .toList();

        List<Double> pnlPips = trades.stream()
            .map(t -> (t.exitPrice() - t.entryPrice())
                * (t.side() == com.martinfou.trading.core.Order.Side.BUY ? 1.0 : -1.0)
                * 10000.0)
            .toList();

        List<Long> barsHeld = trades.stream()
            .map(t -> Math.max(0,
                java.time.Duration.between(t.entryTime(), t.exitTime()).toHours()))
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("  \"trades\": [\n");
        for (int i = 0; i < trades.size(); i++) {
            Trade t = trades.get(i);
            sb.append("    {\n");
            sb.append("      \"entryTime\": ").append(quote(formatInstant(t.entryTime()))).append(",\n");
            sb.append("      \"exitTime\": ").append(quote(formatInstant(t.exitTime()))).append(",\n");
            sb.append("      \"entryPrice\": ").append(String.format("%.5f", t.entryPrice())).append(",\n");
            sb.append("      \"exitPrice\": ").append(String.format("%.5f", t.exitPrice())).append(",\n");
            sb.append("      \"direction\": ").append(quote(directions.get(i))).append(",\n");
            sb.append("      \"quantity\": ").append(String.format("%.1f", t.quantity())).append(",\n");
            sb.append("      \"sl\": 0.0,\n");
            sb.append("      \"pt\": 0.0,\n");
            sb.append("      \"exitReason\": ").append(quote("SIGNAL")).append(",\n");
            sb.append("      \"pnlPips\": ").append(round2(pnlPips.get(i))).append(",\n");
            sb.append("      \"pnlDollars\": ").append(round2(t.pnl())).append(",\n");
            sb.append("      \"barsHeld\": ").append(barsHeld.get(i)).append("\n");
            sb.append("    }");
            if (i < trades.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Derived metrics
    // ---------------------------------------------------------------

    private double avgWin() {
        List<Trade> trades = result.trades();
        if (trades == null || trades.isEmpty()) return 0.0;
        double sum = 0;
        int count = 0;
        for (Trade t : trades) {
            if (t.pnl() > 0) {
                sum += t.pnl();
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private double avgLoss() {
        List<Trade> trades = result.trades();
        if (trades == null || trades.isEmpty()) return 0.0;
        double sum = 0;
        int count = 0;
        for (Trade t : trades) {
            if (t.pnl() < 0) {
                sum += t.pnl();
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private int maxConsecutiveWins() {
        List<Trade> trades = result.trades();
        if (trades == null || trades.isEmpty()) return 0;
        int max = 0, cur = 0;
        for (Trade t : trades) {
            if (t.pnl() > 0) {
                cur++;
                if (cur > max) max = cur;
            } else {
                cur = 0;
            }
        }
        return max;
    }

    private int maxConsecutiveLosses() {
        List<Trade> trades = result.trades();
        if (trades == null || trades.isEmpty()) return 0;
        int max = 0, cur = 0;
        for (Trade t : trades) {
            if (t.pnl() < 0) {
                cur++;
                if (cur > max) max = cur;
            } else {
                cur = 0;
            }
        }
        return max;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static String formatInstant(Instant instant) {
        if (instant == null) return "—";
        // Simple ISO-like format without nanos
        return instant.toString().replace("Z", "").substring(0, 16);
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String round1(double v) {
        return String.format("%.1f", v);
    }

    private static String round2(double v) {
        return String.format("%.2f", v);
    }

    private static String round3(double v) {
        return String.format("%.3f", v);
    }
}
