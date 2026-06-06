package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CLI-style backtest summary for control-plane run JSON (mirrors {@code BacktestResult#printSummary}). */
final class TuiBacktestReport {

    private TuiBacktestReport() {}

    static List<String> format(JsonNode run) {
        JsonNode metrics = run.has("result") ? run.get("result") : run;
        if (!metrics.has("totalTrades")) {
            return List.of();
        }
        String strategy = run.path("strategyId").asText("strategy");
        double initialCap = number(metrics, "initialCapital",
            run.path("configSnapshot").path("capital").asDouble(TuiDefaults.STARTING_CAPITAL));
        double finalEquity = number(metrics, "finalEquity", initialCap);
        double totalPnl = number(metrics, "totalPnl", finalEquity - initialCap);
        double returnPct = number(metrics, "totalReturnPct", 0.0);
        int totalTrades = metrics.get("totalTrades").asInt();
        int winners = metrics.path("winningTrades").asInt(0);
        int losers = metrics.path("losingTrades").asInt(0);
        double winRate = number(metrics, "winRatePct", 0.0);
        double avgTrade = number(metrics, "avgTradePnl", 0.0);
        double maxDd = number(metrics, "maxDrawdownPct", 0.0);
        double sharpe = number(metrics, "sharpeRatio", 0.0);
        double sortino = number(metrics, "sortinoRatio", 0.0);
        double profitFactor = number(metrics, "profitFactor", 0.0);
        double calmar = number(metrics, "calmarRatio", 0.0);
        double commission = number(metrics, "totalCommission", 0.0);
        double slippage = number(metrics, "totalSlippage", 0.0);

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("================================================");
        lines.add("  BACKTEST RESULT: " + strategy);
        lines.add("================================================");
        appendPeriod(lines, metrics, run);
        lines.add("Initial Cap:   $" + money(initialCap));
        lines.add("Final Equity:  $" + money(finalEquity));
        lines.add("P&L:           $" + money(totalPnl) + " (" + fmt(returnPct) + "%)");
        lines.add("─── Trades ─────────────────────────────────");
        lines.add("Total Trades:  " + totalTrades);
        lines.add("Winners:       " + winners + " | Losers: " + losers);
        lines.add("Win Rate:      " + fmt1(winRate) + "%");
        lines.add("Avg Trade:     $" + money(avgTrade));
        lines.add("─── Risk ───────────────────────────────────");
        lines.add("Max DD:        " + fmt(maxDd) + "%");
        lines.add("Sharpe:        " + fmt(sharpe));
        lines.add("Sortino:       " + fmt(sortino));
        lines.add("Profit Fact:   " + fmt(profitFactor));
        lines.add("Calmar:        " + fmt(calmar));
        lines.add("─── Costs ──────────────────────────────────");
        lines.add("Commission:    $" + money(commission));
        lines.add("Slippage:      $" + money(slippage));
        lines.add("===============================================");
        appendDataSource(lines, run);
        return lines;
    }

    private static void appendPeriod(List<String> lines, JsonNode metrics, JsonNode run) {
        if (metrics.has("periodStart") && metrics.has("periodEnd")) {
            lines.add("Period:        " + metrics.get("periodStart").asText()
                + " → " + metrics.get("periodEnd").asText());
        }
        JsonNode config = run.get("configSnapshot");
        if (config != null && config.has("barsSourceType")) {
            String source = config.get("barsSourceType").asText();
            String detail = source;
            if (config.has("barsSourceYear")) {
                detail = source + " " + config.get("barsSourceYear").asText();
            } else if (config.has("barsSourceCount")) {
                detail = source + " " + config.get("barsSourceCount").asInt() + " bars";
            } else if (config.has("barsSourcePath")) {
                detail = source + " " + config.get("barsSourcePath").asText();
            }
            lines.add("Data:          " + detail);
        }
        if (config != null && config.has("lotSize")) {
            lines.add("Lot size:      " + config.get("lotSize").asDouble() + " ("
                + config.path("quantity").asInt(0) + " units)");
        }
    }

    private static void appendDataSource(List<String> lines, JsonNode run) {
        lines.add("Run ID:        " + run.path("runId").asText());
    }

    private static double number(JsonNode node, String field, double fallback) {
        return node.has(field) ? node.get(field).asDouble() : fallback;
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
