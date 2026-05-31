package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Self-contained HTML due diligence report per run (Story 15.7 / PS-GR16). */
final class DueDiligenceHtmlExporter {

    private DueDiligenceHtmlExporter() {}

    static String exportHtml(RunRecord record, EventStore eventStore, Optional<DeploymentRecord> deployment) {
        List<RunEvent> events = eventStore.replayAll(record.runId());
        ExecutionLabel label = resolveLabel(record, deployment);
        Map<String, Object> started = findPayload(events, RunEventType.RUN_STARTED);
        Map<String, Object> ended = findPayload(events, RunEventType.RUN_ENDED);
        List<Map<String, Object>> fills = extractFills(events);

        double initialCapital = number(started.get("initialCapital"), record.endedPayload()
            .map(p -> number(p.get("initialCapital"), 0.0))
            .orElse(0.0));
        if (initialCapital <= 0 && record.configSnapshot().get("capital") instanceof Number cap) {
            initialCapital = cap.doubleValue();
        }

        ExecutionLabelPresentation presentation = ExecutionLabelCatalog.of(label);
        return buildHtml(record, label, presentation, started, ended, fills, initialCapital);
    }

    private static ExecutionLabel resolveLabel(RunRecord record, Optional<DeploymentRecord> deployment) {
        return deployment
            .map(DeploymentRecord::executionLabel)
            .orElseGet(() -> {
                Object resolved = record.configSnapshot().get("resolvedExecutionLabel");
                if (resolved != null && !resolved.toString().isBlank()) {
                    return ExecutionLabel.parse(resolved.toString());
                }
                return ExecutionLabel.forRunMode(record.mode());
            });
    }

    private static Map<String, Object> findPayload(List<RunEvent> events, RunEventType type) {
        return events.stream()
            .filter(e -> e.type() == type)
            .map(RunEvent::payload)
            .findFirst()
            .orElse(Map.of());
    }

    private static List<Map<String, Object>> extractFills(List<RunEvent> events) {
        List<Map<String, Object>> fills = new ArrayList<>();
        for (RunEvent event : events) {
            if (event.type() == RunEventType.FILL) {
                fills.add(event.payload());
            }
        }
        return fills;
    }

    private static String buildHtml(
        RunRecord record,
        ExecutionLabel label,
        ExecutionLabelPresentation presentation,
        Map<String, Object> started,
        Map<String, Object> ended,
        List<Map<String, Object>> fills,
        double initialCapital
    ) {
        String disclaimer = disclaimerFor(label);
        String generatedAt = Instant.now().toString();

        double finalEquity = number(ended.get("finalEquity"), initialCapital);
        double returnPct = number(ended.get("totalReturnPct"), 0.0);
        double maxDd = number(ended.get("maxDrawdownPct"), Double.NaN);
        int totalTrades = (int) number(ended.get("totalTrades"), fills.size());
        Double sharpe = optionalNumber(ended.get("sharpeRatio"));
        Double profitFactor = optionalNumber(ended.get("profitFactor"));
        Double winRate = optionalNumber(ended.get("winRatePct"));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>Due Diligence — ").append(esc(record.strategyId())).append("</title>\n");
        html.append(STYLES);
        html.append("</head>\n<body>\n");

        html.append("<div class=\"banner\">").append(esc(disclaimer)).append("</div>\n");
        html.append("<h1>").append(esc(record.strategyId())).append("</h1>\n");
        html.append("<p class=\"subtitle\">").append(esc(record.symbol()))
            .append(" · ").append(labelBadge(presentation))
            .append(" · ").append(esc(record.status().name())).append("</p>\n");

        html.append("<section><h2>Run identity</h2><table class=\"meta\">\n");
        row(html, "Run ID", record.runId());
        row(html, "Config hash", record.configHash());
        rowHtml(html, "Execution label", labelBadge(presentation) + " <span class=\"muted\">(" + esc(label.name()) + ")</span>");
        row(html, "Mode", record.mode().name());
        record.completedAt().ifPresent(t -> row(html, "Completed at", t.toString()));
        row(html, "Generated at", generatedAt);
        html.append("</table></section>\n");

        html.append("<section><h2>Performance summary</h2><div class=\"metrics\">\n");
        metric(html, "Initial capital", fmtMoney(initialCapital));
        metric(html, "Final equity", fmtMoney(finalEquity));
        metric(html, "Total return", fmtPct(returnPct));
        metric(html, "Total trades", String.valueOf(totalTrades));
        metric(html, "Max drawdown", Double.isNaN(maxDd) ? "—" : fmtPct(maxDd));
        metric(html, "Sharpe ratio", formatOptional(sharpe));
        metric(html, "Profit factor", formatOptional(profitFactor));
        metric(html, "Win rate", winRate != null ? fmtPct(winRate) : "—");
        html.append("</div></section>\n");

        if (!started.isEmpty()) {
            html.append("<section><h2>Config snapshot</h2><pre class=\"config\">")
                .append(esc(snapshotSummary(started, record)))
                .append("</pre></section>\n");
        }

        html.append("<section><h2>Execution events (fills)</h2>\n");
        if (fills.isEmpty()) {
            html.append("<p class=\"muted\">No fill-level events recorded. Backtest runs aggregate metrics in RUN_ENDED only.</p>\n");
        } else {
            html.append("<table><thead><tr><th>#</th><th>Time</th><th>Symbol</th><th>Side</th><th>Qty</th><th>Price</th></tr></thead><tbody>\n");
            int i = 1;
            for (Map<String, Object> fill : fills) {
                html.append("<tr><td>").append(i++).append("</td>")
                    .append("<td>").append(esc(str(fill.get("timestamp")))).append("</td>")
                    .append("<td>").append(esc(str(fill.get("symbol")))).append("</td>")
                    .append("<td>").append(esc(str(fill.get("side")))).append("</td>")
                    .append("<td>").append(esc(fmtQty(fill.get("quantity")))).append("</td>")
                    .append("<td>").append(esc(fmtPrice(fill.get("price")))).append("</td></tr>\n");
            }
            html.append("</tbody></table>\n");
        }
        html.append("</section>\n");

        html.append("<footer><p>This report is self-contained and does not require the control plane. ")
            .append("Verify config hash and execution label before external submission.</p></footer>\n");
        html.append("</body></html>");
        return html.toString();
    }

    private static String labelBadge(ExecutionLabelPresentation presentation) {
        return "<span class=\"label-badge\" style=\"background:"
            + esc(presentation.badgeBackgroundColor())
            + ";color:"
            + esc(presentation.badgeTextColor())
            + "\">"
            + esc(presentation.displayName())
            + "</span>";
    }

    private static String disclaimerFor(ExecutionLabel label) {
        return switch (label) {
            case BACKTEST -> "DISCLAIMER: Historical backtest simulation — not broker execution. Past performance does not guarantee future results.";
            case PAPER_STUB -> "DISCLAIMER: Development paper stub — does NOT count toward the 30-day OANDA paper observation period.";
            case PAPER_OANDA -> "DISCLAIMER: OANDA demo paper account — simulated broker execution, no real capital at risk.";
            case PAPER_IBKR -> "DISCLAIMER: IBKR paper account via TWS/Gateway — verify connection and account before acting on this report.";
            case LIVE_OANDA -> "DISCLAIMER: LIVE OANDA execution — real capital at risk. Verify account and deployment before acting on this report.";
            case LIVE_IBKR -> "DISCLAIMER: LIVE IBKR execution — real capital at risk. Verify account and deployment before acting on this report.";
        };
    }

    private static String snapshotSummary(Map<String, Object> started, RunRecord record) {
        Map<String, Object> summary = new LinkedHashMap<>(record.configSnapshot());
        if (started.get("barCount") != null) {
            summary.putIfAbsent("barCount", started.get("barCount"));
        }
        if (started.get("initialCapital") != null) {
            summary.putIfAbsent("initialCapital", started.get("initialCapital"));
        }
        try {
            return RunEventMessages.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        } catch (Exception e) {
            return summary.toString();
        }
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(value)).append("</td></tr>\n");
    }

    private static void rowHtml(StringBuilder html, String label, String valueHtml) {
        html.append("<tr><th>").append(esc(label)).append("</th><td>").append(valueHtml).append("</td></tr>\n");
    }

    private static void metric(StringBuilder html, String label, String value) {
        html.append("<div class=\"metric\"><div class=\"label\">").append(esc(label))
            .append("</div><div class=\"value\">").append(esc(value)).append("</div></div>\n");
    }

    private static double number(Object value, double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return defaultValue;
    }

    private static Double optionalNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static String formatOptional(Double value) {
        return value != null ? String.format("%.2f", value) : "—";
    }

    private static String fmtMoney(double v) {
        return String.format("$%,.2f", v);
    }

    private static String fmtPct(double v) {
        return String.format("%.2f%%", v);
    }

    private static String fmtQty(Object v) {
        return v instanceof Number n ? String.format("%,.0f", n.doubleValue()) : str(v);
    }

    private static String fmtPrice(Object v) {
        return v instanceof Number n ? String.format("%.5f", n.doubleValue()) : str(v);
    }

    private static String str(Object v) {
        return v != null ? v.toString() : "";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String STYLES = """
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  background:#f8f9fb;color:#1a1a2e;padding:24px;max-width:960px;margin:0 auto;line-height:1.5}
.banner{background:#fff3cd;border:2px solid #ffc107;color:#664d03;padding:16px 20px;
  border-radius:8px;font-weight:600;margin-bottom:24px}
h1{font-size:1.75rem;margin-bottom:4px}
.subtitle{color:#5c6370;margin-bottom:24px}
.label-badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;
  font-weight:600;letter-spacing:0.2px;vertical-align:middle}
h2{font-size:1.1rem;margin:24px 0 12px;color:#333}
section{margin-bottom:8px}
.metrics{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:12px}
.metric{background:#fff;border:1px solid #e0e4ea;border-radius:8px;padding:12px 16px}
.metric .label{font-size:11px;text-transform:uppercase;color:#8899aa;letter-spacing:0.4px}
.metric .value{font-size:18px;font-weight:600;margin-top:4px}
table{width:100%;border-collapse:collapse;font-size:13px;background:#fff;border-radius:8px;overflow:hidden}
table.meta th{text-align:left;width:180px;background:#eef1f6;padding:8px 12px;font-weight:600}
table.meta td{padding:8px 12px;border-bottom:1px solid #eef1f6}
th{background:#eef1f6;padding:8px 12px;text-align:left;font-size:11px;text-transform:uppercase;color:#5c6370}
td{padding:8px 12px;border-bottom:1px solid #eef1f6}
pre.config{background:#fff;border:1px solid #e0e4ea;border-radius:8px;padding:12px;
  font-size:12px;overflow-x:auto;white-space:pre-wrap}
.muted{color:#8899aa;font-style:italic}
footer{margin-top:32px;padding-top:16px;border-top:1px solid #e0e4ea;font-size:12px;color:#8899aa}
</style>
""";
}
