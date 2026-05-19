package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.Trade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates StrategyQuant-style HTML backtest reports with interactive
 * Chart.js visualisations.
 *
 * <p>The report includes tabs for: Overview, Equity Curve, Trades,
 * Monthly Analysis, Drawdown Analysis, and Monte Carlo overlay
 * (when provided).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * HtmlReportGenerator gen = new HtmlReportGenerator(result);
 * gen.withMonteCarlo(mcResult)
 *    .generate(reportDir.resolve("backtest-report.html"));
 * }</pre>
 */
public class HtmlReportGenerator {

    private final BacktestResult result;
    private MonteCarloSimulation.Result monteCarlo;
    private final List<BacktestResult> comparisonResults = new ArrayList<>();
    private final List<String> comparisonNames = new ArrayList<>();

    public HtmlReportGenerator(BacktestResult result) {
        this.result = result;
    }

    /** Optionally attaches Monte Carlo simulation results for overlay. */
    public HtmlReportGenerator withMonteCarlo(MonteCarloSimulation.Result mc) {
        this.monteCarlo = mc;
        return this;
    }

    /** Adds a comparison result for the multi-strategy overview. */
    public HtmlReportGenerator withComparison(String name, BacktestResult r) {
        this.comparisonNames.add(name);
        this.comparisonResults.add(r);
        return this;
    }

    // ---------------------------------------------------------------
    //  Generation
    // ---------------------------------------------------------------

    /**
     * Generates the HTML report file.
     *
     * @param outputPath destination path for the HTML file
     * @return the output path
     */
    public Path generate(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            String html = buildHtml();
            Files.writeString(outputPath, html);
            return outputPath.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate HTML report", e);
        }
    }

    // ---------------------------------------------------------------
    //  HTML builder
    // ---------------------------------------------------------------

    private String buildHtml() {
        String tabButtons = tabButtonsHtml();
        String tabContent = tabsContentHtml();
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Backtest Report — %s</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  background:#0f1923;color:#e0e6ed;padding:20px}
h1{color:#00d4aa;margin-bottom:8px}
.subtitle{color:#8899aa;margin-bottom:24px}
.tabs{display:flex;gap:2px;margin-bottom:20px;flex-wrap:wrap}
.tab{padding:10px 20px;background:#1a2d3d;cursor:pointer;border-radius:6px 6px 0 0;
  color:#8899aa;font-size:14px;transition:all 0.2s}
.tab:hover{background:#243b52;color:#c0d0e0}
.tab.active{background:#00d4aa;color:#0f1923;font-weight:600}
.tab-content{display:none}
.tab-content.active{display:block}
.metrics-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));
  gap:12px;margin-bottom:24px}
.metric-card{background:#1a2d3d;padding:16px;border-radius:8px;
  border-left:3px solid #00d4aa}
.metric-card .label{font-size:11px;text-transform:uppercase;color:#8899aa;
  letter-spacing:0.5px}
.metric-card .value{font-size:20px;font-weight:700;margin-top:4px}
.metric-card .value.positive{color:#00d4aa}
.metric-card .value.negative{color:#ff6b6b}
.chart-container{background:#1a2d3d;padding:16px;border-radius:8px;margin-bottom:20px}
.chart-container canvas{max-height:400px;width:100%%}
table{width:100%%;border-collapse:collapse;font-size:13px}
th{background:#1a2d3d;color:#8899aa;padding:8px 12px;text-align:left;
  font-weight:600;text-transform:uppercase;font-size:11px}
td{padding:6px 12px;border-bottom:1px solid #1a2d3d}
tr:hover td{background:#1e3347}
.positive{color:#00d4aa}
.negative{color:#ff6b6b}
</style>
</head>
<body>
<h1>%s</h1>
<div class="subtitle">Backtest Report — %s</div>

<div class="tabs" id="tabs">
%s
</div>

%s

<script>
function switchTab(name){
  document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(t=>t.classList.remove('active'));
  document.querySelector('.tab[onclick*=\"'\"'\"'+name+'\"'\"'\"]').classList.add('active');
  document.getElementById('tab-'+name).classList.add('active');
}
</script>
</body>
</html>""".formatted(
            escapeHtml(result.strategyName()),
            escapeHtml(result.strategyName()),
            periodStr(),
            tabButtons,
            tabContent
        );
    }

    // ---------------------------------------------------------------
    //  Tab buttons
    // ---------------------------------------------------------------

    private String tabButtonsHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append(tabBtn("overview", true));
        sb.append(tabBtn("equity", false));
        sb.append(tabBtn("trades", false));
        sb.append(tabBtn("monthly", false));
        sb.append(tabBtn("drawdown", false));
        if (monteCarlo != null) sb.append(tabBtn("montecarlo", false));
        if (!comparisonResults.isEmpty()) sb.append(tabBtn("comparison", false));
        return sb.toString();
    }

    private String tabBtn(String name, boolean active) {
        String cls = active ? "tab active" : "tab";
        String label = switch (name) {
            case "overview" -> "Overview";
            case "equity" -> "Equity Curve";
            case "trades" -> "Trades";
            case "monthly" -> "Monthly";
            case "drawdown" -> "Drawdown";
            case "montecarlo" -> "Monte Carlo";
            case "comparison" -> "Comparison";
            default -> name;
        };
        return String.format("<div class=\"%s\" onclick=\"switchTab('%s')\">%s</div>\n",
            cls, name, label);
    }

    // ---------------------------------------------------------------
    //  Tab content
    // ---------------------------------------------------------------

    private String tabsContentHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append(overviewTab());
        sb.append(equityTab());
        sb.append(tradesTab());
        sb.append(monthlyTab());
        sb.append(drawdownTab());
        if (monteCarlo != null) sb.append(monteCarloTab());
        if (!comparisonResults.isEmpty()) sb.append(comparisonTab());
        return sb.toString();
    }

    private String overviewTab() {
        var r = result;
        return contentDiv("overview", true, """
<div class="metrics-grid">
  <div class="metric-card"><div class="label">Total Return</div>
    <div class="value %s">%.2f%%</div></div>
  <div class="metric-card"><div class="label">Net P&amp;L</div>
    <div class="value %s">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Total Trades</div>
    <div class="value">%d</div></div>
  <div class="metric-card"><div class="label">Win Rate</div>
    <div class="value %s">%.1f%%</div></div>
  <div class="metric-card"><div class="label">Max Drawdown</div>
    <div class="value negative">%.2f%%</div></div>
  <div class="metric-card"><div class="label">Sharpe</div>
    <div class="value %s">%.2f</div></div>
  <div class="metric-card"><div class="label">Sortino</div>
    <div class="value %s">%.2f</div></div>
  <div class="metric-card"><div class="label">Profit Factor</div>
    <div class="value %s">%.2f</div></div>
  <div class="metric-card"><div class="label">Calmar</div>
    <div class="value %s">%.2f</div></div>
  <div class="metric-card"><div class="label">Avg Trade</div>
    <div class="value %s">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Commission</div>
    <div class="value negative">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Period</div>
    <div class="value" style="font-size:14px">%s</div></div>
</div>""".formatted(
            cssClass(r.totalReturnPct()), r.totalReturnPct(),
            cssClass(r.totalPnl()), r.totalPnl(),
            r.totalTrades(),
            cssClass(r.winRatePct() - 50), r.winRatePct(),
            r.maxDrawdownPct(),
            cssClass(r.sharpeRatio()), r.sharpeRatio(),
            cssClass(r.sortinoRatio()), r.sortinoRatio(),
            cssClass(r.profitFactor() - 1), r.profitFactor(),
            cssClass(r.calmarRatio()), r.calmarRatio(),
            cssClass(r.avgTradePnl()), r.avgTradePnl(),
            r.totalCommission(),
            periodStr()
        ));
    }

    private String equityTab() {
        String data = jsonArray(result.equityCurve());
        int len = result.equityCurve().size();
        return contentDiv("equity", false, """
<div class="chart-container"><canvas id="eqChart"></canvas></div>
<script>
new Chart(document.getElementById('eqChart'),{
type:'line',
data:{labels:Array.from({length:%d},(_,i)=>i),
  datasets:[{label:'Equity',data:%s,borderColor:'#00d4aa',
  backgroundColor:'rgba(0,212,170,0.1)',fill:true,tension:0.4,pointRadius:0}]},
options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{labels:{color:'#e0e6ed'}}},
  scales:{x:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}},
          y:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}}}}});
</script>""".formatted(len, data));
    }

    private String tradesTab() {
        if (result.trades() == null || result.trades().isEmpty()) {
            return contentDiv("trades", false, "<p>No trades recorded.</p>");
        }
        StringBuilder rows = new StringBuilder();
        int i = 1;
        for (Trade t : result.trades()) {
            String cls = t.pnl() >= 0 ? "positive" : "negative";
            rows.append("<tr><td>").append(i++).append("</td>")
                .append("<td>").append(esc(t.symbol())).append("</td>")
                .append("<td>").append(t.side()).append("</td>")
                .append("<td>").append(df(t.entryPrice())).append("</td>")
                .append("<td>").append(df(t.exitPrice())).append("</td>")
                .append("<td>").append(qf(t.quantity())).append("</td>")
                .append("<td class=\"").append(cls).append("\">$")
                .append(pf(t.pnl())).append("</td></tr>\n");
        }
        return contentDiv("trades", false, """
<div class="chart-container" style="overflow-x:auto">
<table><thead><tr>
<th>#</th><th>Symbol</th><th>Side</th><th>Entry</th><th>Exit</th><th>Qty</th><th>P&amp;L</th>
</tr></thead><tbody>%s</tbody></table>
</div>""".formatted(rows.toString()));
    }

    private String monthlyTab() {
        return contentDiv("monthly", false, """
<p style="color:#8899aa;margin-bottom:12px">Monthly analysis (per-bar timestamps required for full breakdown).</p>
<div class="chart-container"><canvas id="monChart"></canvas></div>
<script>
new Chart(document.getElementById('monChart'),{
type:'bar',
data:{labels:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'],
  datasets:[{label:'Return',data:[0,0,0,0,0,0,0,0,0,0,0,0],backgroundColor:'#00d4aa'}]},
options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{labels:{color:'#e0e6ed'}}},
  scales:{x:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}},
          y:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}}}}});
</script>""");
    }

    private String drawdownTab() {
        List<Double> dd = computeDrawdownCurve();
        String data = jsonArray(dd);
        return contentDiv("drawdown", false, """
<div class="chart-container"><canvas id="ddChart"></canvas></div>
<script>
new Chart(document.getElementById('ddChart'),{
type:'line',
data:{labels:Array.from({length:%d},(_,i)=>i),
  datasets:[{label:'Drawdown %%',data:%s,borderColor:'#ff6b6b',
  backgroundColor:'rgba(255,107,107,0.1)',fill:true,tension:0.4,pointRadius:0}]},
options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{labels:{color:'#e0e6ed'}}},
  scales:{x:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}},
          y:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'},reverse:true}}}}});
</script>""".formatted(dd.size(), data));
    }

    private String monteCarloTab() {
        if (monteCarlo == null) return "";
        String hist = buildHistogramJson(monteCarlo.pnlValuesSorted(), 20);
        var mc = monteCarlo;
        return contentDiv("montecarlo", false, """
<div class="metrics-grid">
  <div class="metric-card"><div class="label">Median P&amp;L</div>
    <div class="value %s">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Mean P&amp;L</div>
    <div class="value %s">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Worst</div>
    <div class="value negative">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Best</div>
    <div class="value positive">$%,.2f</div></div>
  <div class="metric-card"><div class="label">VaR 95%%</div>
    <div class="value negative">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Loss Prob.</div>
    <div class="value %s">%.1f%%</div></div>
  <div class="metric-card"><div class="label">Baseline P&amp;L</div>
    <div class="value %s">$%,.2f</div></div>
  <div class="metric-card"><div class="label">Median Sharpe</div>
    <div class="value %s">%.2f</div></div>
</div>
<div class="chart-container"><canvas id="mcChart"></canvas></div>
<script>
const mcData=%s;
new Chart(document.getElementById('mcChart'),{
type:'bar',
data:{labels:mcData.labels,datasets:[{label:'Runs',data:mcData.counts,
  backgroundColor:'rgba(0,212,170,0.6)',borderColor:'#00d4aa',borderWidth:1}]},
options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{labels:{color:'#e0e6ed'}}},
  scales:{x:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}},
          y:{ticks:{color:'#8899aa'},grid:{color:'#1a2d3d'}}}}});
</script>""".formatted(
            cssClass(mc.medianPnl()), mc.medianPnl(),
            cssClass(mc.meanPnl()), mc.meanPnl(),
            mc.worstPnl(), mc.bestPnl(), mc.var95(),
            cssClass(50 - mc.probabilityOfLoss()), mc.probabilityOfLoss(),
            cssClass(result.totalPnl()), result.totalPnl(),
            cssClass(mc.medianSharpe()), mc.medianSharpe(),
            hist
        ));
    }

    private String comparisonTab() {
        BacktestResult baseline = result;
        StringBuilder rows = new StringBuilder();
        rows.append("<tr><th>Metric</th>");
        rows.append("<th>").append(esc(baseline.strategyName())).append("</th>");
        for (String name : comparisonNames) {
            rows.append("<th>").append(esc(name)).append("</th>");
        }
        rows.append("</tr>\n");

        // Metric labels
        String[] labels = {"Total Return %","Sharpe","Sortino","Max DD %","Profit Factor","Win Rate %","Total Trades"};
        for (String label : labels) {
            rows.append("<tr><td>").append(label).append("</td>");
            rows.append(metricCell(baseline, label));
            for (BacktestResult cr : comparisonResults) {
                rows.append(metricCell(cr, label));
            }
            rows.append("</tr>\n");
        }

        return contentDiv("comparison", false, """
<div class="chart-container" style="overflow-x:auto">
<table>%s</table>
</div>""".formatted(rows.toString()));
    }

    private String metricCell(BacktestResult r, String label) {
        double val = metricValue(r, label);
        String cls = switch (label) {
            case "Max DD %" -> "negative";
            case "Total Trades" -> "";
            default -> val > 0 ? "positive" : val < 0 ? "negative" : "";
        };
        String fmt = metricFormat(label, val);
        return "<td class=\"" + cls + "\">" + fmt + "</td>";
    }

    private static double metricValue(BacktestResult r, String label) {
        return switch (label) {
            case "Total Return %" -> r.totalReturnPct();
            case "Sharpe" -> r.sharpeRatio();
            case "Sortino" -> r.sortinoRatio();
            case "Max DD %" -> r.maxDrawdownPct();
            case "Profit Factor" -> r.profitFactor();
            case "Win Rate %" -> r.winRatePct();
            case "Total Trades" -> (double) r.totalTrades();
            default -> 0;
        };
    }

    private static String metricFormat(String label, double val) {
        return switch (label) {
            case "Total Return %" -> String.format("%.2f%%", val);
            case "Sharpe", "Sortino", "Profit Factor" -> String.format("%.2f", val);
            case "Max DD %" -> String.format("%.2f%%", val);
            case "Win Rate %" -> String.format("%.1f%%", val);
            case "Total Trades" -> String.format("%d", (int) val);
            default -> String.format("%.2f", val);
        };
    }

    // ---------------------------------------------------------------
    //  HTML helpers
    // ---------------------------------------------------------------

    private static String contentDiv(String id, boolean active, String content) {
        String cls = active ? "tab-content active" : "tab-content";
        return "<div id=\"tab-" + id + "\" class=\"" + cls + "\">\n" + content + "\n</div>\n";
    }

    private String periodStr() {
        if (result.periodStart() == null || result.periodEnd() == null) return "?";
        return result.periodStart().toString().substring(0, 10)
            + " — " + result.periodEnd().toString().substring(0, 10);
    }

    private static String cssClass(double val) {
        return val > 0 ? "positive" : val < 0 ? "negative" : "";
    }

    private List<Double> computeDrawdownCurve() {
        List<Double> ec = result.equityCurve();
        if (ec == null || ec.isEmpty()) return List.of();
        List<Double> dd = new ArrayList<>(ec.size());
        double peak = Double.NEGATIVE_INFINITY;
        for (double e : ec) {
            if (e > peak) peak = e;
            dd.add(peak > 0 ? (peak - e) / peak * 100 : 0);
        }
        return dd;
    }

    private String buildHistogramJson(List<Double> sorted, int bins) {
        if (sorted == null || sorted.size() < 2) return "{labels:[],counts:[]}";
        double min = sorted.getFirst();
        double max = sorted.getLast();
        double range = max - min;
        if (range == 0) {
            return "{labels:['" + String.format("$%,.0f", min) + "'],counts:[" + sorted.size() + "]}";
        }
        double binWidth = range / bins;
        int[] counts = new int[bins];
        String[] labels = new String[bins];
        for (int i = 0; i < bins; i++) {
            double lo = min + i * binWidth;
            double hi = lo + binWidth;
            labels[i] = String.format("$%,.0f", (lo + hi) / 2);
        }
        for (double v : sorted) {
            int idx = (int) ((v - min) / binWidth);
            if (idx >= bins) idx = bins - 1;
            counts[idx]++;
        }
        StringBuilder sb = new StringBuilder("{labels:[");
        for (int i = 0; i < bins; i++) {
            if (i > 0) sb.append(',');
            sb.append("'").append(esc(labels[i])).append("'");
        }
        sb.append("],counts:[");
        for (int i = 0; i < bins; i++) {
            if (i > 0) sb.append(',');
            sb.append(counts[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonArray(List<Double> list) {
        return list.stream().map(d -> String.format("%.6f", d))
            .collect(Collectors.joining(",", "[", "]"));
    }

    private static String df(double v) { return String.format("%.5f", v); }
    private static String qf(double v) { return String.format("%.4f", v); }
    private static String pf(double v) { return String.format("%,.2f", v); }
    private static String esc(String s) { return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private static String escapeHtml(String s) { return esc(s); }
}
