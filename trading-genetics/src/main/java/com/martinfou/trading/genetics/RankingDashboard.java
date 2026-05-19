package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.WalkForwardOptimizer;
import com.martinfou.trading.backtest.MonteCarloSimulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates a StrategyQuant-style ranking dashboard HTML report that displays
 * the top strategies produced by the {@link GeneticEngine}, ranked by their
 * {@link RobustnessScore} and key performance metrics.
 *
 * <p>The report is fully self-contained (Chart.js via CDN, inline CSS/JS)
 * with interactive sorting, filtering by strategy type, and CSV export.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * List<RankingEntry> entries = buildEntries(...);
 * RankingDashboard dashboard = new RankingDashboard(entries);
 * dashboard.generate(reportDir.resolve("ranking.html"));
 * }</pre>
 */
public class RankingDashboard {

    /** Maximum entries to show by default. */
    public static final int DEFAULT_TOP_N = 50;

    private final List<RankingEntry> entries;

    /**
     * Creates a ranking dashboard from the given entries.
     * Entries are expected to already be sorted by rank (1 = best).
     *
     * @param entries ranked strategy entries, ordered best-first
     */
    public RankingDashboard(List<RankingEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    // ---------------------------------------------------------------
    //  Ranking Entry
    // ---------------------------------------------------------------

    /**
     * A single ranked entry in the dashboard.
     *
     * @param rank        ordinal rank (1 = best)
     * @param chromosome  the chromosome encoding the strategy
     * @param result      backtest result for this strategy
     * @param robustness  computed robustness score
     * @param genResult   generation result from the genetic engine (optional)
     */
    public record RankingEntry(
        int rank,
        Chromosome chromosome,
        BacktestResult result,
        RobustnessScore robustness,
        GeneticEngine.GenerationResult genResult
    ) {
        /**
         * Derives a human-readable strategy name from the chromosome's primary entry gene.
         */
        public String strategyName() {
            if (chromosome == null || chromosome.entryGenes().isEmpty()) return "Unknown";
            Gene primary = chromosome.entryGenes().get(0);
            return primary.indicatorType().name() + "_" + primary.period();
        }

        /**
         * Detects the approximate strategy type based on entry indicator mix.
         *
         * @return "Trend", "MeanRev", "Breakout", or "Other"
         */
        public String strategyType() {
            if (chromosome == null || chromosome.entryGenes().isEmpty()) return "Other";
            var types = chromosome.entryGenes().stream()
                .map(Gene::indicatorType)
                .collect(Collectors.toSet());

            boolean hasRsi = types.contains(Gene.IndicatorType.RSI);
            boolean hasSma = types.contains(Gene.IndicatorType.SMA)
                          || types.contains(Gene.IndicatorType.EMA);
            boolean hasAtr = types.contains(Gene.IndicatorType.ATR);
            boolean hasAdx = types.contains(Gene.IndicatorType.ADX);

            if (hasRsi && !hasSma && !hasAdx)  return "MeanRev";
            if (hasAdx || hasAtr)               return "Breakout";
            if (hasSma)                         return "Trend";
            return "Other";
        }
    }

    // ---------------------------------------------------------------
    //  Generation
    // ---------------------------------------------------------------

    /**
     * Generates the HTML dashboard as a string.
     *
     * @return complete HTML document
     */
    public String generate() {
        return buildHtml();
    }

    /**
     * Generates the HTML dashboard and writes it to a file.
     *
     * @param outputPath destination path for the HTML file
     * @return the absolute output path
     */
    public Path generate(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            String html = buildHtml();
            Files.writeString(outputPath, html);
            return outputPath.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate ranking dashboard", e);
        }
    }

    // ---------------------------------------------------------------
    //  HTML builder
    // ---------------------------------------------------------------

    private String buildHtml() {
        String tableRows = buildTableRows();
        String chartData = buildChartDataJson();
        String csvData = buildCsvData();
        String strategyTypesJson = buildStrategyTypesJson();

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Strategy Ranking Dashboard</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  background:#0f1923;color:#e0e6ed;padding:20px;min-height:100vh}

/* Header */
.header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;margin-bottom:16px}
.header h1{color:#00d4aa;font-size:24px}
.header h1 span{color:#8899aa;font-size:14px;font-weight:400}
.subtitle{color:#8899aa;font-size:13px;margin-bottom:20px}
.controls{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:16px}
.btn{padding:6px 14px;border-radius:6px;border:1px solid #2a4055;background:#1a2d3d;
  color:#8899aa;cursor:pointer;font-size:12px;transition:all 0.15s}
.btn:hover{background:#243b52;color:#c0d0e0}
.btn.active{background:#00d4aa;color:#0f1923;border-color:#00d4aa;font-weight:600}
.btn-topn{background:#1a2d3d;color:#e0e6ed;border:1px solid #2a4055;
  padding:6px 14px;border-radius:6px;cursor:pointer;font-size:12px}
.btn-topn.active{background:#00d4aa;color:#0f1923;font-weight:600}

/* Filter row */
.filter-bar{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px;align-items:center}
.filter-bar .label{color:#8899aa;font-size:12px;margin-right:6px}

/* Table */
.table-container{overflow-x:auto;margin-bottom:24px;border-radius:8px;border:1px solid #1a2d3d}
table{width:100%%;border-collapse:collapse;font-size:13px}
th{background:#1a2d3d;color:#8899aa;padding:10px 14px;text-align:left;
  font-size:11px;text-transform:uppercase;letter-spacing:0.3px;
  cursor:pointer;user-select:none;position:sticky;top:0;z-index:1}
th:hover{color:#e0e6ed}
th .sort-icon{color:#3a556a;margin-left:4px;font-size:10px}
th.sorted{color:#00d4aa}
td{padding:8px 14px;border-bottom:1px solid #1a2d3d;white-space:nowrap}
tr:hover td{background:#1e3347}
tr.hidden{display:none}

/* Rank badge */
.rank-badge{display:inline-block;width:24px;height:24px;line-height:24px;
  text-align:center;border-radius:50%%;font-size:11px;font-weight:700}
.rank-1{background:linear-gradient(135deg,#ffd700,#ffb300);color:#0f1923}
.rank-2{background:linear-gradient(135deg,#c0c0c0,#a0a0a0);color:#0f1923}
.rank-3{background:linear-gradient(135deg,#cd7f32,#b86b24);color:#fff}
.rank-other{background:#1a2d3d;color:#8899aa;font-weight:400}

/* Strategy name cell */
.strat-name{font-weight:600;color:#e0e6ed}
.strat-type{display:inline-block;font-size:10px;padding:2px 8px;
  border-radius:10px;margin-left:8px;font-weight:500}
.type-Trend{background:#1a3a2a;color:#00d4aa}
.type-MeanRev{background:#2a1a3a;color:#bb86fc}
.type-Breakout{background:#3a2a1a;color:#ffb74d}
.type-Other{background:#1a2d3d;color:#8899aa}

/* Color-coded values */
.val-pos{color:#00d4aa}
.val-neg{color:#ff6b6b}
.val-neutral{color:#e0e6ed}

/* Robustness bar */
.robustness-bar{display:inline-flex;align-items:center;gap:6px}
.robustness-bar .bar{width:60px;height:6px;background:#1a2d3d;border-radius:3px;overflow:hidden}
.robustness-bar .bar-fill{height:100%%;border-radius:3px;transition:width 0.4s}
.bar-excellent{background:#00d4aa}
.bar-good{background:#4caf50}
.bar-fair{background:#ffc107}
.bar-poor{background:#ff9800}
.bar-verypoor{background:#ff6b6b}

/* Charts */
.charts-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));
  gap:16px;margin-bottom:24px}
.chart-card{background:#1a2d3d;padding:16px;border-radius:8px}
.chart-card h3{color:#8899aa;font-size:13px;margin-bottom:12px;font-weight:500}
.chart-card canvas{max-height:260px;width:100%%}

/* Export */
.export-bar{display:flex;gap:8px;margin-bottom:20px}
.export-btn{background:#1a2d3d;color:#00d4aa;border:1px solid #00d4aa;
  padding:8px 18px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.15s}
.export-btn:hover{background:#00d4aa;color:#0f1923}

/* Summary strip */
.summary-strip{display:flex;gap:20px;flex-wrap:wrap;margin-bottom:20px}
.summary-item{display:flex;align-items:center;gap:6px;color:#8899aa;font-size:13px}
.summary-item .num{color:#e0e6ed;font-weight:600}
.summary-item .num.pos{color:#00d4aa}

/* Responsive */
@media(max-width:768px){
  .charts-grid{grid-template-columns:1fr}
  .header{flex-direction:column;align-items:flex-start;gap:8px}
}
</style>
</head>
<body>

<!-- Header -->
<div class="header">
  <h1>&#x1F3C6; Strategy Ranking Dashboard <span>| Total: %d</span></h1>
  <div class="controls">
    <label class="btn-topn" onclick="setTopN(20)">Top 20</label>
    <label class="btn-topn active" onclick="setTopN(50)">Top 50</label>
    <label class="btn-topn" onclick="setTopN(100)">Top 100</label>
  </div>
</div>
<div class="subtitle">Genetic engine ranking — sorted by composite Robustness Score</div>

<!-- Summary strip -->
<div class="summary-strip">
  <div class="summary-item">&#x1F4CA; Strategies: <span class="num" id="summaryCount">%d</span></div>
  <div class="summary-item">&#x2B06; Avg Sharpe: <span class="num pos" id="summarySharpe">%.2f</span></div>
  <div class="summary-item">&#x1F4B0; Avg PF: <span class="num pos" id="summaryPf">%.2f</span></div>
  <div class="summary-item">&#x1F53B; Avg Robustness: <span class="num pos" id="summaryRobustness">%.1f</span></div>
</div>

<!-- Filters -->
<div class="filter-bar">
  <span class="label">&#x1F3AF; Filter:</span>
  <div class="btn active" data-type="all" onclick="filterType('all')">All</div>
  <div class="btn" data-type="Trend" onclick="filterType('Trend')">Trend</div>
  <div class="btn" data-type="MeanRev" onclick="filterType('MeanRev')">MeanRev</div>
  <div class="btn" data-type="Breakout" onclick="filterType('Breakout')">Breakout</div>
  <span class="label" style="margin-left:16px">&#x2195; Sort:</span>
  <div class="btn active" data-sort="robustness" onclick="sortBy('robustness')">Robustness</div>
  <div class="btn" data-sort="sharpe" onclick="sortBy('sharpe')">Sharpe</div>
  <div class="btn" data-sort="pf" onclick="sortBy('pf')">Profit Factor</div>
  <div class="btn" data-sort="return" onclick="sortBy('return')">Return</div>
  <div class="btn" data-sort="dd" onclick="sortBy('dd')">Max DD</div>
</div>

<!-- Export -->
<div class="export-bar">
  <button class="export-btn" onclick="exportCsv()">&#x2193; Export CSV</button>
</div>

<!-- Table -->
<div class="table-container">
<table>
<thead><tr>
  <th onclick="sortBy('rank')" data-col="rank"># <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th>Strategy</th>
  <th onclick="sortBy('sharpe')" data-col="sharpe">Sharpe <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th onclick="sortBy('pf')" data-col="pf">PF <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th onclick="sortBy('dd')" data-col="dd">Max DD <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th onclick="sortBy('return')" data-col="return">Return <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th>WFOOS</th>
  <th onclick="sortBy('robustness')" data-col="robustness">Robustness <span class="sort-icon">&#x25B2;&#x25BC;</span></th>
  <th>Gen</th>
</tr></thead>
<tbody id="rankingBody">
%s
</tbody>
</table>
</div>

<!-- Charts -->
<div class="charts-grid">
  <div class="chart-card">
    <h3>Sharpe Ratio Distribution</h3>
    <canvas id="sharpeChart"></canvas>
  </div>
  <div class="chart-card">
    <h3>Robustness Score Gauge</h3>
    <canvas id="gaugeChart"></canvas>
  </div>
  <div class="chart-card">
    <h3>Strategy Type Breakdown</h3>
    <canvas id="pieChart"></canvas>
  </div>
</div>

<script>
// ============================================================
//  DATA
// ============================================================
const DATA = %s;
const STRATEGY_TYPES = %s;

// ============================================================
//  STATE
// ============================================================
let currentFilter = 'all';
let currentSort = 'robustness';
let sortAsc = false;
let topN = 50;

// ============================================================
//  TABLES
// ============================================================
function setTopN(n) {
  topN = n;
  document.querySelectorAll('.btn-topn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.btn-topn').forEach(b => {
    if (parseInt(b.textContent.replace('Top ','')) === n) b.classList.add('active');
  });
  renderTable();
}

function filterType(type) {
  currentFilter = type;
  document.querySelectorAll('.filter-bar .btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.filter-bar .btn').forEach(b => {
    if (b.getAttribute('data-type') === type) b.classList.add('active');
  });
  renderTable();
}

function sortBy(col) {
  if (currentSort === col) { sortAsc = !sortAsc; }
  else { currentSort = col; sortAsc = false; }
  document.querySelectorAll('.filter-bar .btn[data-sort]').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.filter-bar .btn[data-sort]').forEach(b => {
    if (b.getAttribute('data-sort') === col) b.classList.add('active');
  });
  renderTable();
}

function renderTable() {
  let filtered = DATA;
  if (currentFilter !== 'all') {
    filtered = DATA.filter(e => e.type === currentFilter);
  }

  // Sort
  filtered = [...filtered];
  filtered.sort((a, b) => {
    let va = getSortVal(a, currentSort);
    let vb = getSortVal(b, currentSort);
    return sortAsc ? va - vb : vb - va;
  });

  // Apply topN — update rank
  filtered = filtered.slice(0, topN);
  filtered.forEach((e, i) => e.displayRank = i + 1);

  let tbody = document.getElementById('rankingBody');
  tbody.innerHTML = filtered.map(e => rowHtml(e)).join('');

  updateSummary(filtered);
}

function getSortVal(e, col) {
  switch (col) {
    case 'robustness': return e.robustness;
    case 'sharpe': return e.sharpe;
    case 'pf': return e.pf;
    case 'return': return e.ret;
    case 'dd': return -e.dd;
    case 'rank': return e.displayRank || e.rank;
    default: return 0;
  }
}

function rowHtml(e) {
  const r = e.displayRank || e.rank;
  const rankCls = r === 1 ? 'rank-1' : r === 2 ? 'rank-2' : r === 3 ? 'rank-3' : 'rank-other';
  const sharpeCls = e.sharpe >= 1 ? 'val-pos' : e.sharpe > 0 ? 'val-neutral' : 'val-neg';
  const pfCls = e.pf >= 1.5 ? 'val-pos' : e.pf >= 1 ? 'val-neutral' : 'val-neg';
  const ddCls = e.dd <= 15 ? 'val-pos' : e.dd <= 30 ? 'val-neutral' : 'val-neg';
  const retCls = e.ret >= 0 ? 'val-pos' : 'val-neg';
  const barCls = barClass(e.robustness);
  const genStr = e.gen >= 0 ? (e.gen + 1) : '-';

  return `<tr>
    <td><span class="rank-badge ${rankCls}">${r}</span></td>
    <td><span class="strat-name">${esc(e.name)}</span> <span class="strat-type type-${e.type}">${e.type}</span></td>
    <td class="${sharpeCls}">${fmt2(e.sharpe)}</td>
    <td class="${pfCls}">${fmt2(e.pf)}</td>
    <td class="${ddCls}">${fmt1(e.dd)}%%</td>
    <td class="${retCls}">${fmt2(e.ret)}%%</td>
    <td class="val-neutral">${fmt2(e.wfoos)}</td>
    <td>
      <div class="robustness-bar">
        <span style="font-weight:600;color:${barColor(e.robustness)};width:32px">${fmt0(e.robustness)}</span>
        <div class="bar"><div class="bar-fill ${barCls}" style="width:${e.robustness}%%"></div></div>
        <span>${indicator(e.robustness)}</span>
      </div>
    </td>
    <td class="val-neutral">${genStr}</td>
  </tr>`;
}

function barClass(v) {
  if (v >= 85) return 'bar-excellent';
  if (v >= 70) return 'bar-good';
  if (v >= 50) return 'bar-fair';
  if (v >= 30) return 'bar-poor';
  return 'bar-verypoor';
}

function barColor(v) {
  if (v >= 85) return '#00d4aa';
  if (v >= 70) return '#4caf50';
  if (v >= 50) return '#ffc107';
  if (v >= 30) return '#ff9800';
  return '#ff6b6b';
}

function indicator(v) {
  if (v >= 70) return '&#x1F7E2;';
  if (v >= 50) return '&#x1F7E1;';
  if (v >= 30) return '&#x1F7E0;';
  return '&#x1F534;';
}

function updateSummary(filtered) {
  const n = filtered.length;
  document.getElementById('summaryCount').textContent = n;
  if (n === 0) return;
  const avgSharpe = filtered.reduce((s,e) => s + e.sharpe, 0) / n;
  const avgPf = filtered.reduce((s,e) => s + e.pf, 0) / n;
  const avgRob = filtered.reduce((s,e) => s + e.robustness, 0) / n;
  document.getElementById('summarySharpe').textContent = avgSharpe.toFixed(2);
  document.getElementById('summaryPf').textContent = avgPf.toFixed(2);
  document.getElementById('summaryRobustness').textContent = avgRob.toFixed(1);
}

// ============================================================
//  CSV EXPORT
// ============================================================
function exportCsv() {
  const rows = [['Rank','Strategy','Type','Sharpe','ProfitFactor','MaxDD%%','Return%%','WFOOS','Robustness','Gen']];
  DATA.forEach(e => {
    rows.push([e.rank, e.name, e.type, fmt2(e.sharpe), fmt2(e.pf), fmt1(e.dd), fmt2(e.ret), fmt2(e.wfoos), fmt0(e.robustness), e.gen >= 0 ? e.gen + 1 : '']);
  });
  const csv = rows.map(r => r.map(v => '"' + String(v).replace(/"/g,'""') + '"').join(',')).join('\\n');
  const blob = new Blob([csv], {type:'text/csv'});
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'ranking-export.csv'; a.click();
  URL.revokeObjectURL(url);
}

// ============================================================
//  CHARTS
// ============================================================
function fmt0(v) { return Math.round(v); }
function fmt1(v) { return v.toFixed(1); }
function fmt2(v) { return v.toFixed(2); }
function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

// --- CHARTS INIT ---
function initCharts() {
  const sharpeVals = DATA.map(e => e.sharpe);
  const minS = Math.min(...sharpeVals);
  const maxS = Math.max(...sharpeVals);
  const bins = 12;
  const binW = (maxS - minS) / bins || 1;
  const labels = [];
  const counts = new Array(bins).fill(0);
  for (let i = 0; i < bins; i++) {
    labels.push(((minS + i * binW + (i+1) * binW) / 2).toFixed(2));
  }
  sharpeVals.forEach(v => {
    let idx = Math.floor((v - minS) / binW);
    if (idx >= bins) idx = bins - 1;
    counts[idx]++;
  });

  new Chart(document.getElementById('sharpeChart'), {
    type:'bar',
    data:{labels,datasets:[{
      label:'Strategies',data:counts,
      backgroundColor:'rgba(0,212,170,0.5)',borderColor:'#00d4aa',borderWidth:1
    }]},
    options:{responsive:true,maintainAspectRatio:false,
      plugins:{legend:{display:false}},
      scales:{
        x:{ticks:{color:'#8899aa',maxRotation:45},grid:{color:'#1a2d3d'}},
        y:{ticks:{color:'#8899aa',stepSize:1},grid:{color:'#1a2d3d'}}
      }}
  });

  // Robustness gauge (doughnut)
  const robAvg = DATA.reduce((s,e) => s + e.robustness, 0) / (DATA.length || 1);
  new Chart(document.getElementById('gaugeChart'), {
    type:'doughnut',
    data:{
      labels:['Robustness','Remaining'],
      datasets:[{
        data:[robAvg, 100 - robAvg],
        backgroundColor:['#00d4aa','#1a2d3d'],
        borderWidth:0,
        cutout:'75%%'
      }]
    },
    options:{responsive:true,maintainAspectRatio:false,
      plugins:{
        legend:{display:false},
        tooltip:{callbacks:{label:ctx => 'Avg: ' + ctx.parsed.toFixed(1)}}
      }}
  });

  // Strategy type pie
  const typeCounts = {};
  DATA.forEach(e => { typeCounts[e.type] = (typeCounts[e.type] || 0) + 1; });
  const typeLabels = Object.keys(typeCounts);
  const typeData = typeLabels.map(k => typeCounts[k]);
  const typeColors = {'Trend':'#00d4aa','MeanRev':'#bb86fc','Breakout':'#ffb74d','Other':'#8899aa'};

  new Chart(document.getElementById('pieChart'), {
    type:'doughnut',
    data:{
      labels:typeLabels,
      datasets:[{
        data:typeData,
        backgroundColor:typeLabels.map(k => typeColors[k] || '#8899aa'),
        borderWidth:1,
        borderColor:'#0f1923'
      }]
    },
    options:{responsive:true,maintainAspectRatio:false,
      plugins:{
        legend:{
          position:'bottom',
          labels:{color:'#e0e6ed',padding:12,font:{size:11}}
        }
      }}
  });
}

// ============================================================
//  INIT
// ============================================================
document.addEventListener('DOMContentLoaded', () => {
  renderTable();
  initCharts();
});
</script>
</body>
</html>""".formatted(
            entries.size(),
            entries.size(),
            computeAvgSharpe(),
            computeAvgPf(),
            computeAvgRobustness(),
            tableRows,
            chartData,
            strategyTypesJson
        );
    }

    // ---------------------------------------------------------------
    //  Table rows
    // ---------------------------------------------------------------

    private String buildTableRows() {
        StringBuilder sb = new StringBuilder();
        for (RankingEntry e : entries) {
            sb.append(buildRow(e));
        }
        return sb.toString();
    }

    private String buildRow(RankingEntry e) {
        BacktestResult r = e.result();
        RobustnessScore rs = e.robustness();

        int genNum = e.genResult() != null ? e.genResult().generation() + 1 : -1;
        double sharpe = r != null ? r.sharpeRatio() : 0;
        double pf = r != null ? r.profitFactor() : 0;
        double dd = r != null ? r.maxDrawdownPct() : 0;
        double ret = r != null ? r.totalReturnPct() : 0;

        // We don't store raw WFOOS value in RobustnessScore directly;
        // use the wfoos sub-score as a proxy in the "WFOOS" column.
        double wfScore = rs != null ? rs.wfoos() : 0;

        String rankCls = e.rank() == 1 ? "rank-1" : e.rank() == 2 ? "rank-2"
                        : e.rank() == 3 ? "rank-3" : "rank-other";
        String sharpeCls = sharpe >= 1 ? "val-pos" : sharpe > 0 ? "val-neutral" : "val-neg";
        String pfCls = pf >= 1.5 ? "val-pos" : pf >= 1 ? "val-neutral" : "val-neg";
        String ddCls = dd <= 15 ? "val-pos" : dd <= 30 ? "val-neutral" : "val-neg";
        String retCls = ret >= 0 ? "val-pos" : "val-neg";

        double robScore = rs != null ? rs.overall() : 0;
        String barCls = robustnessCssClass(robScore);

        String genStr = genNum >= 0 ? String.valueOf(genNum) : "-";

        return "<tr>" +
            "<td><span class=\"rank-badge " + rankCls + "\">" + e.rank() + "</span></td>" +
            "<td><span class=\"strat-name\">" + esc(e.strategyName()) +
                "</span> <span class=\"strat-type type-" + e.strategyType() + "\">" + e.strategyType() + "</span></td>" +
            "<td class=\"" + sharpeCls + "\">" + fmt2(sharpe) + "</td>" +
            "<td class=\"" + pfCls + "\">" + fmt2(pf) + "</td>" +
            "<td class=\"" + ddCls + "\">" + fmt1(dd) + "%</td>" +
            "<td class=\"" + retCls + "\">" + fmt2(ret) + "%</td>" +
            "<td class=\"val-neutral\">" + fmt2(wfScore) + "</td>" +
            "<td><div class=\"robustness-bar\">" +
                "<span style=\"font-weight:600;color:" + robustnessBarColor(robScore) + ";width:32px\">" + fmt0(robScore) + "</span>" +
                "<div class=\"bar\"><div class=\"bar-fill " + barCls + "\" style=\"width:" + fmt0(robScore) + "%\"></div></div>" +
                "<span>" + robustnessIndicator(robScore) + "</span>" +
            "</div></td>" +
            "<td class=\"val-neutral\">" + genStr + "</td>" +
        "</tr>\n";
    }

    // ---------------------------------------------------------------
    //  Chart data JSON
    // ---------------------------------------------------------------

    private String buildChartDataJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            RankingEntry e = entries.get(i);
            BacktestResult r = e.result();
            RobustnessScore rs = e.robustness();
            double sharpe = r != null ? r.sharpeRatio() : 0;
            double pf = r != null ? r.profitFactor() : 0;
            double dd = r != null ? r.maxDrawdownPct() : 0;
            double ret = r != null ? r.totalReturnPct() : 0;
            double rob = rs != null ? rs.overall() : 0;
            double wf = rs != null ? rs.wfoos() : 0;
            int gen = e.genResult() != null ? e.genResult().generation() : -1;
            sb.append("{");
            sb.append("\"rank\":").append(e.rank()).append(",");
            sb.append("\"name\":\"").append(escJson(e.strategyName())).append("\",");
            sb.append("\"type\":\"").append(e.strategyType()).append("\",");
            sb.append("\"sharpe\":").append(sharpe).append(",");
            sb.append("\"pf\":").append(pf).append(",");
            sb.append("\"dd\":").append(dd).append(",");
            sb.append("\"ret\":").append(ret).append(",");
            sb.append("\"wfoos\":").append(wf).append(",");
            sb.append("\"robustness\":").append(rob).append(",");
            sb.append("\"gen\":").append(gen);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildStrategyTypesJson() {
        // Build a JSON map of type -> count
        Map<String, Long> counts = entries.stream()
            .collect(Collectors.groupingBy(RankingEntry::strategyType, Collectors.counting()));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  CSV data
    // ---------------------------------------------------------------

    private String buildCsvData() {
        StringBuilder sb = new StringBuilder();
        sb.append("Rank,Strategy,Type,Sharpe,ProfitFactor,MaxDD%%,Return%%,WFOOS,Robustness,Gen\n");
        for (RankingEntry e : entries) {
            BacktestResult r = e.result();
            RobustnessScore rs = e.robustness();
            double sharpe = r != null ? r.sharpeRatio() : 0;
            double pf = r != null ? r.profitFactor() : 0;
            double dd = r != null ? r.maxDrawdownPct() : 0;
            double ret = r != null ? r.totalReturnPct() : 0;
            double rob = rs != null ? rs.overall() : 0;
            double wf = rs != null ? rs.wfoos() : 0;
            int gen = e.genResult() != null ? e.genResult().generation() + 1 : 0;
            sb.append(e.rank()).append(",");
            sb.append(csvEsc(e.strategyName())).append(",");
            sb.append(e.strategyType()).append(",");
            sb.append(fmt2(sharpe)).append(",");
            sb.append(fmt2(pf)).append(",");
            sb.append(fmt1(dd)).append(",");
            sb.append(fmt2(ret)).append(",");
            sb.append(fmt2(wf)).append(",");
            sb.append(fmt0(rob)).append(",");
            sb.append(gen).append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Summary helpers
    // ---------------------------------------------------------------

    private double computeAvgSharpe() {
        if (entries.isEmpty()) return 0;
        return entries.stream()
            .mapToDouble(e -> e.result() != null ? e.result().sharpeRatio() : 0)
            .average().orElse(0);
    }

    private double computeAvgPf() {
        if (entries.isEmpty()) return 0;
        return entries.stream()
            .mapToDouble(e -> e.result() != null ? e.result().profitFactor() : 0)
            .average().orElse(0);
    }

    private double computeAvgRobustness() {
        if (entries.isEmpty()) return 0;
        return entries.stream()
            .mapToDouble(e -> e.robustness() != null ? e.robustness().overall() : 0)
            .average().orElse(0);
    }

    // ---------------------------------------------------------------
    //  Formatting helpers
    // ---------------------------------------------------------------

    private static String fmt0(double v) { return String.valueOf((int) Math.round(v)); }
    private static String fmt1(double v) { return String.format("%.1f", v); }
    private static String fmt2(double v) { return String.format("%.2f", v); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    private static String csvEsc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String robustnessCssClass(double score) {
        if (score >= 85) return "bar-excellent";
        if (score >= 70) return "bar-good";
        if (score >= 50) return "bar-fair";
        if (score >= 30) return "bar-poor";
        return "bar-verypoor";
    }

    private static String robustnessBarColor(double score) {
        if (score >= 85) return "#00d4aa";
        if (score >= 70) return "#4caf50";
        if (score >= 50) return "#ffc107";
        if (score >= 30) return "#ff9800";
        return "#ff6b6b";
    }

    private static String robustnessIndicator(double score) {
        if (score >= 70) return "\uD83D\uDFE2";  // green circle
        if (score >= 50) return "\uD83D\uDFE1";  // yellow circle
        if (score >= 30) return "\uD83D\uDFE0";  // orange circle
        return "\uD83D\uDD34";                     // red circle
    }
}
