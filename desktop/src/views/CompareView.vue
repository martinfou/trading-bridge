<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { createChart, type IChartApi, type ISeriesApi, type LineData, ColorType, LineSeries } from 'lightweight-charts'
import type { RunSummary, RunResult, Trade } from '@/types/control-plane'

const router = useRouter()
const { listRuns, getRun, getTrades, getEquityCurve } = useControlPlane()

const runs = ref<RunSummary[]>([])
const selectedIds = ref<string[]>([])
const compareData = ref<Map<string, { run: RunResult; trades: Trade[]; equity: number[] }>>(new Map())
const loading = ref(true)
const loadingCompare = ref(false)
const viewError = ref<string | null>(null)

const chartContainer = ref<HTMLDivElement>()
let chart: IChartApi | null = null
const seriesMap = new Map<string, ISeriesApi<'Line'>>()

const curveColors = ['#d97706', '#22c55e', '#6366f1', '#ef4444']

onMounted(async () => {
  try {
    runs.value = await listRuns()
  } catch (e: any) {
    viewError.value = `Failed to load runs: ${e.message}`
  } finally {
    loading.value = false
  }
})

watch(selectedIds, async (ids) => {
  // Clean removed selections
  for (const [id] of compareData.value) {
    if (!ids.includes(id)) {
      compareData.value.delete(id)
    }
  }

  // Load new selections
  loadingCompare.value = true
  for (const id of ids) {
    if (!compareData.value.has(id)) {
      try {
        const [runResult, trades, equity] = await Promise.all([
          getRun(id),
          getTrades(id),
          getEquityCurve(id),
        ])
        compareData.value.set(id, { run: runResult, trades, equity })
      } catch {
        // Skip failed loads
      }
    }
  }

  // Rebuild chart
  renderChart()
  loadingCompare.value = false
}, { deep: true })

function toggleRun(id: string) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) {
    selectedIds.value.splice(idx, 1)
  } else if (selectedIds.value.length < 4) {
    selectedIds.value.push(id)
  }
}

function isSelected(id: string) {
  return selectedIds.value.includes(id)
}

function formatRunLabel(r: RunSummary): string {
  return `${r.strategyId} · ${r.symbol} · ${r.status}`
}

function formatDate(iso?: string): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('fr-CA', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

// ---- Equity curve chart ----
function renderChart() {
  if (!chartContainer.value) return

  const entries = Array.from(compareData.value.entries()).filter(
    ([, v]) => v.equity.length > 0,
  )
  if (entries.length === 0) {
    if (chart) { chart.remove(); chart = null; seriesMap.clear() }
    return
  }

  if (!chart) {
    chart = createChart(chartContainer.value, {
      height: 350,
      layout: {
        background: { type: ColorType.Solid, color: '#1a1a1a' },
        textColor: '#888',
      },
      grid: {
        vertLines: { color: '#222' },
        horzLines: { color: '#222' },
      },
      timeScale: {
        visible: true,
        borderColor: '#333',
        timeVisible: false,
      },
      rightPriceScale: { borderColor: '#333' },
      crosshair: {
        vertLine: { color: '#555', labelBackgroundColor: '#333' },
        horzLine: { color: '#555', labelBackgroundColor: '#333' },
      },
      handleScroll: true,
      handleScale: true,
    })
  }

  // Clear old series that no longer match
  const currentIds = new Set(entries.map(([id]) => id))
  for (const [id, series] of seriesMap) {
    if (!currentIds.has(id)) {
      chart?.removeSeries(series)
      seriesMap.delete(id)
    }
  }

  entries.forEach(([id, data], i) => {
    const color = curveColors[i % curveColors.length]
    let series = seriesMap.get(id)
    if (!series) {
      series = chart!.addSeries(LineSeries, {
        color,
        lineWidth: 2,
        crosshairMarkerVisible: true,
        crosshairMarkerRadius: 4,
        priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
        lastValueVisible: true,
        priceLineVisible: true,
        priceLineColor: '#444',
        title: data.run.strategyId,
      })
      seriesMap.set(id, series)
    }
    const lineData: LineData[] = data.equity.map((v, i) => ({
      time: i as any,
      value: v,
    }))
    series.setData(lineData)
  })

  chart.timeScale().fitContent()
}

function viewRun(runId: string) {
  router.push(`/results/${runId}`)
}
</script>

<template>
  <div class="view">
    <h1>Compare</h1>
    <p class="subtitle">Side-by-side comparison of up to 4 runs</p>

    <!-- Error -->
    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Loading runs list -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Loading runs…</p>
    </div>

    <!-- Runs selector -->
    <div v-else class="runs-selector">
      <h3>Select runs to compare</h3>
      <div class="run-list">
        <div
          v-for="r in runs"
          :key="r.runId"
          :class="['run-item', { selected: isSelected(r.runId) }]"
          @click="toggleRun(r.runId)"
        >
          <div class="run-checkbox">
            <span v-if="isSelected(r.runId)">✓</span>
          </div>
          <div class="run-item-info">
            <span class="run-item-strat">{{ r.strategyId }}</span>
            <span class="run-item-meta">
              {{ r.symbol }}
              <span :class="['status-dot', r.status === 'COMPLETED' ? 'ok' : r.status === 'FAILED' ? 'fail' : 'run']"></span>
              {{ r.status }}
            </span>
          </div>
          <span class="run-item-date">{{ formatDate(r.completedAt || r.startedAt) }}</span>
        </div>
      </div>
      <div v-if="runs.length === 0" class="no-runs">
        No runs found. Run a backtest from the Dashboard first.
      </div>
    </div>

    <!-- Selected runs indicator -->
    <div v-if="selectedIds.length > 0" class="selected-badge">
      {{ selectedIds.length }} run{{ selectedIds.length > 1 ? 's' : '' }} selected
      <span v-if="selectedIds.length < 4" class="badge-hint">(select up to 4)</span>
    </div>

    <!-- Compare section -->
    <div v-if="selectedIds.length > 0" class="compare-section">
      <div v-if="loadingCompare" class="loading-state">
        <div class="spinner"></div>
        <p>Loading comparison data…</p>
      </div>

      <template v-if="!loadingCompare && compareData.size > 0">
        <!-- KPI comparison table -->
        <div class="kpi-comparison">
          <table class="compare-table">
            <thead>
              <tr>
                <th>Metric</th>
                <th v-for="([id], i) in Array.from(compareData.entries())" :key="id" :style="{ color: curveColors[i] }">
                  {{ compareData.get(id)?.run.strategyId }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td class="metric-label">Symbol</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  {{ compareData.get(id)?.run.symbol || '—' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Status</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  <span :class="['status-badge', compareData.get(id)?.run.status === 'COMPLETED' ? 'ok' : 'fail']">
                    {{ compareData.get(id)?.run.status || '—' }}
                  </span>
                </td>
              </tr>
              <tr>
                <td class="metric-label">Total Return</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id"
                  :class="(compareData.get(id)?.run.result?.totalReturnPct ?? 0) >= 0 ? 'profit' : 'loss'">
                  {{ compareData.get(id)?.run.result?.totalReturnPct.toFixed(2) || '—' }}%
                </td>
              </tr>
              <tr>
                <td class="metric-label">Final Equity</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  ${{ compareData.get(id)?.run.result?.finalEquity?.toLocaleString() || '—' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Sharpe Ratio</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id"
                  :class="(compareData.get(id)?.run.result?.sharpeRatio ?? 0) >= 1 ? 'profit' : (compareData.get(id)?.run.result?.sharpeRatio ?? 0) < 0 ? 'loss' : ''">
                  {{ compareData.get(id)?.run.result?.sharpeRatio?.toFixed(2) || '—' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Profit Factor</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id"
                  :class="(compareData.get(id)?.run.result?.profitFactor ?? 1) >= 1.5 ? 'profit' : (compareData.get(id)?.run.result?.profitFactor ?? 1) < 1 ? 'loss' : ''">
                  {{ compareData.get(id)?.run.result?.profitFactor?.toFixed(2) || '—' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Max DD %</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id"
                  :class="(compareData.get(id)?.run.result?.maxDrawdownPct ?? 0) <= 15 ? 'profit' : (compareData.get(id)?.run.result?.maxDrawdownPct ?? 0) > 25 ? 'loss' : ''">
                  {{ compareData.get(id)?.run.result?.maxDrawdownPct?.toFixed(2) || '—' }}%
                </td>
              </tr>
              <tr>
                <td class="metric-label">Win Rate</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id"
                  :class="(compareData.get(id)?.run.result?.winRatePct ?? 0) >= 50 ? 'profit' : ''">
                  {{ compareData.get(id)?.run.result?.winRatePct?.toFixed(1) || '—' }}%
                </td>
              </tr>
              <tr>
                <td class="metric-label">Trades</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  {{ compareData.get(id)?.run.result?.totalTrades || '—' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Commission</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  ${{ compareData.get(id)?.run.result?.totalCommission?.toFixed(2) || '0.00' }}
                </td>
              </tr>
              <tr>
                <td class="metric-label">Slippage</td>
                <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                  ${{ compareData.get(id)?.run.result?.totalSlippage?.toFixed(2) || '0.00' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Equity curve chart -->
        <div class="chart-section">
          <h3>Equity Curves</h3>
          <div ref="chartContainer" class="compare-chart"></div>
        </div>

        <!-- Action row -->
        <div class="actions">
          <button
            v-for="([id]) in Array.from(compareData.entries())"
            :key="'view-'+id"
            class="action-btn"
            @click="viewRun(id)"
          >
            View {{ compareData.get(id)?.run.strategyId }} →
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }

h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
h3 { font-size: 0.9rem; margin-bottom: 0.75rem; color: var(--text-secondary); }

.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.banner.error { background: #2d1212; color: #fca5a5; border: 1px solid #7f1d1d; }

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 2rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.spinner {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.runs-selector {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem;
  margin-bottom: 1rem;
}

.run-list {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  max-height: 240px;
  overflow-y: auto;
}

.run-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.65rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}

.run-item:hover {
  background: var(--bg-primary);
  border-color: var(--border);
}

.run-item.selected {
  background: rgba(217, 119, 6, 0.08);
  border-color: var(--accent);
}

.run-checkbox {
  width: 1.1rem;
  height: 1.1rem;
  border: 2px solid var(--border);
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.6rem;
  color: var(--accent);
  flex-shrink: 0;
}

.run-item.selected .run-checkbox {
  border-color: var(--accent);
  background: rgba(217, 119, 6, 0.15);
}

.run-item-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}

.run-item-strat {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
}

.run-item-meta {
  font-size: 0.7rem;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.ok { background: var(--success); }
.status-dot.fail { background: var(--danger); }
.status-dot.run { background: var(--warning); }

.run-item-date {
  font-size: 0.7rem;
  color: var(--text-secondary);
  white-space: nowrap;
}

.no-runs {
  padding: 1.5rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.selected-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  background: rgba(217, 119, 6, 0.1);
  border: 1px solid var(--accent);
  border-radius: 6px;
  padding: 0.35rem 0.75rem;
  font-size: 0.8rem;
  color: var(--accent);
  margin-bottom: 1rem;
}

.badge-hint {
  color: var(--text-secondary);
  font-size: 0.7rem;
}

.compare-section {
  margin-bottom: 1.5rem;
}

.kpi-comparison {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  margin-bottom: 1rem;
}

.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8rem;
}

.compare-table th {
  text-align: left;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid var(--border);
}

.compare-table td {
  padding: 0.4rem 0.75rem;
  border-bottom: 1px solid var(--border);
  color: var(--text-primary);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
}

.compare-table tr:last-child td {
  border-bottom: none;
}

.compare-table td:first-child {
  font-family: inherit;
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.compare-table td.profit { color: var(--success); font-weight: 600; }
.compare-table td.loss { color: var(--danger); font-weight: 600; }

.status-badge {
  padding: 0.1rem 0.4rem;
  border-radius: 3px;
  font-size: 0.65rem;
  font-weight: 600;
}

.status-badge.ok { background: #064e3b; color: #6ee7b7; }
.status-badge.fail { background: #450a0a; color: #fca5a5; }

.chart-section {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem;
  margin-bottom: 1rem;
}

.compare-chart {
  border-radius: 8px;
  overflow: hidden;
}

.actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.action-btn {
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--accent);
  border-radius: 6px;
  padding: 0.4rem 1rem;
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.15s;
}

.action-btn:hover {
  background: var(--accent);
  color: #000;
}
</style>
