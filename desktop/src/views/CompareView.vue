<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { createChart, type IChartApi, type ISeriesApi, type LineData, ColorType, LineSeries } from 'lightweight-charts'
import type { RunSummary, RunResult, Trade, WeeklyStat, ReconciliationAnomaly } from '@/types/control-plane'

const router = useRouter()
const { listRuns, getRun, getTrades, getEquityCurve, getWeeklyStats, getAlignment } = useControlPlane()

const runs = ref<RunSummary[]>([])
const selectedIds = ref<string[]>([])
const compareData = ref<Map<string, { run: RunResult; trades: Trade[]; equity: number[] }>>(new Map())
const loading = ref(true)
const loadingCompare = ref(false)
const viewError = ref<string | null>(null)

const activeTab = ref('overview')
const weeklyStatsData = ref<Map<string, WeeklyStat[]>>(new Map())
const alignmentDetails = ref<Map<string, { anomalies: ReconciliationAnomaly[]; backtestOrders: any[]; liveOrders: any[] }>>(new Map())
const selectedAlignmentRunId = ref<string | null>(null)

const searchQuery = ref('')
const currentFilter = ref<'all' | 'backtest' | 'paper' | 'live'>('all')

const filteredRuns = computed(() => {
  let list = runs.value
  if (currentFilter.value !== 'all') {
    list = list.filter(r => {
      const cat = r.executionLabelMeta?.category?.toLowerCase() || r.mode?.toLowerCase() || ''
      return cat === currentFilter.value
    })
  }
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(r =>
      r.strategyId.toLowerCase().includes(q) ||
      r.symbol.toLowerCase().includes(q) ||
      (r.runId && r.runId.toLowerCase().includes(q))
    )
  }
  return list
})

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
      weeklyStatsData.value.delete(id)
      alignmentDetails.value.delete(id)
    }
  }

  // Load new selections
  loadingCompare.value = true
  for (const id of ids) {
    if (!compareData.value.has(id)) {
      try {
        const [runResult, trades, equity, weeklyStats, alignmentData] = await Promise.all([
          getRun(id),
          getTrades(id),
          getEquityCurve(id),
          getWeeklyStats(id).catch(() => []),
          getAlignment(id).catch(() => ({ anomalies: [], backtestOrders: [], liveOrders: [] })),
        ])
        compareData.value.set(id, { run: runResult, trades, equity })
        weeklyStatsData.value.set(id, weeklyStats)
        alignmentDetails.value.set(id, alignmentData)
      } catch {
        // Skip failed loads
      }
    }
  }

  if (ids.length > 0) {
    if (!selectedAlignmentRunId.value || !ids.includes(selectedAlignmentRunId.value)) {
      selectedAlignmentRunId.value = ids[0]
    }
  } else {
    selectedAlignmentRunId.value = null
  }

  // Rebuild chart
  await nextTick()
  renderChart()
  loadingCompare.value = false
}, { deep: true })

const allWeeks = computed(() => {
  const weeks = new Set<string>()
  for (const [, stats] of weeklyStatsData.value) {
    stats.forEach(s => weeks.add(s.weekId))
  }
  return Array.from(weeks).sort((a, b) => b.localeCompare(a))
})

function getRunsWithWeek(weekId: string) {
  const list: [string, { run: RunResult; weekStat: WeeklyStat }][] = []
  selectedIds.value.forEach(runId => {
    const runInfo = compareData.value.get(runId)
    const statsList = weeklyStatsData.value.get(runId)
    if (runInfo && statsList) {
      const weekStat = statsList.find(s => s.weekId === weekId)
      if (weekStat) {
        list.push([runId, { run: runInfo.run, weekStat }])
      }
    }
  })
  return list
}

const alignmentScore = computed(() => {
  const runId = selectedAlignmentRunId.value
  if (!runId) return 100
  const data = alignmentDetails.value.get(runId)
  if (!data || data.backtestOrders.length === 0) return 100

  const anomalyCount = data.anomalies.length
  const total = data.backtestOrders.length
  const score = ((total - anomalyCount) / total) * 100
  return Math.max(0, Math.round(score))
})

const alignmentColorClass = computed(() => {
  const score = alignmentScore.value
  if (score >= 95) return 'score-ok'
  if (score >= 90) return 'score-warn'
  return 'score-fail'
})

interface TimelineRow {
  id: string
  bt?: any
  live?: any
  anomaly?: any
}

const timelineRows = computed(() => {
  const runId = selectedAlignmentRunId.value
  if (!runId) return []
  const data = alignmentDetails.value.get(runId)
  if (!data) return []

  const rows: TimelineRow[] = []
  const matchedLiveIds = new Set<string>()

  data.backtestOrders.forEach((bt: any) => {
    const missingAnomaly = data.anomalies.find(
      (a: any) => a.type === 'MISSING_LIVE' && a.orderId === bt.id
    )

    if (missingAnomaly) {
      rows.push({
        id: `bt-${bt.id}`,
        bt,
        anomaly: missingAnomaly
      })
    } else {
      let matchedLive: any = null
      
      if (bt.correlationId) {
        matchedLive = data.liveOrders.find(
          (l: any) => l.correlationId === bt.correlationId
        )
      }
      
      if (!matchedLive) {
        const btTime = new Date(bt.filledAt || bt.createdAt).getTime()
        let bestDiff = Infinity
        data.liveOrders.forEach((l: any) => {
          if (!matchedLiveIds.has(l.id) && l.symbol === bt.symbol && l.side === bt.side) {
            const lTime = new Date(l.filledAt || l.createdAt).getTime()
            const diff = Math.abs(btTime - lTime)
            if (diff < 300000 && diff < bestDiff) {
              bestDiff = diff
              matchedLive = l
            }
          }
        })
      }

      if (matchedLive) {
        matchedLiveIds.add(matchedLive.id)
        const driftAnomaly = data.anomalies.find(
          (a: any) => (a.type === 'TIME_DRIFT' || a.type === 'PRICE_DRIFT') && a.orderId === matchedLive.id
        )
        
        rows.push({
          id: `match-${bt.id}-${matchedLive.id}`,
          bt,
          live: matchedLive,
          anomaly: driftAnomaly
        })
      } else {
        rows.push({
          id: `bt-missing-${bt.id}`,
          bt,
          anomaly: { type: 'MISSING_LIVE', message: 'Ordre présent en backtest mais non exécuté en live' }
        })
      }
    }
  })

  data.liveOrders.forEach((live: any) => {
    if (!matchedLiveIds.has(live.id)) {
      const ghostAnomaly = data.anomalies.find(
        (a: any) => a.type === 'GHOST_LIVE' && a.orderId === live.id
      )
      rows.push({
        id: `live-${live.id}`,
        live,
        anomaly: ghostAnomaly || { type: 'GHOST_LIVE', message: 'Trade fantôme exécuté en live sans signal backtest' }
      })
    }
  })

  rows.sort((a, b) => {
    const timeA = a.bt ? (a.bt.filledAt || a.bt.createdAt) : (a.live ? (a.live.filledAt || a.live.createdAt) : null)
    const timeB = b.bt ? (b.bt.filledAt || b.bt.createdAt) : (b.live ? (b.live.filledAt || b.live.createdAt) : null)
    const tA = timeA ? new Date(timeA).getTime() : 0
    const tB = timeB ? new Date(timeB).getTime() : 0
    const finalA = isNaN(tA) ? 0 : tA
    const finalB = isNaN(tB) ? 0 : tB
    return finalA - finalB
  })

  return rows
})

// Clean up chart when container is unmounted (e.g. selectedIds becomes empty)
watch(chartContainer, (newEl) => {
  if (!newEl && chart) {
    chart.remove()
    chart = null
    seriesMap.clear()
  }
})

onUnmounted(() => {
  if (chart) {
    chart.remove()
    chart = null
    seriesMap.clear()
  }
})

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
      <div class="runs-selector-header">
        <h3>Select runs to compare</h3>
        <input v-model="searchQuery" placeholder="Filter by strategy or symbol..." class="search-input" />
      </div>
      <div class="runs-selector-filters">
        <button :class="['filter-btn', { active: currentFilter === 'all' }]" @click="currentFilter = 'all'">All</button>
        <button :class="['filter-btn', { active: currentFilter === 'backtest' }]" @click="currentFilter = 'backtest'">Backtests</button>
        <button :class="['filter-btn', { active: currentFilter === 'paper' }]" @click="currentFilter = 'paper'">Paper</button>
        <button :class="['filter-btn', { active: currentFilter === 'live' }]" @click="currentFilter = 'live'">Live</button>
      </div>
      <div class="run-list">
        <div
          v-for="r in filteredRuns"
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
              <span v-if="r.executionLabelMeta" class="run-item-badge" :style="{ backgroundColor: r.executionLabelMeta.badgeBackgroundColor, color: r.executionLabelMeta.badgeTextColor }">
                {{ r.executionLabelMeta.displayName }}
              </span>
            </span>
          </div>
          <span class="run-item-date">{{ formatDate(r.completedAt || r.startedAt) }}</span>
        </div>
      </div>
      <div v-if="filteredRuns.length === 0" class="no-runs">
        No matching runs found.
      </div>
    </div>

    <!-- Selected runs indicator -->
    <div v-if="selectedIds.length > 0" class="selected-badge">
      {{ selectedIds.length }} run{{ selectedIds.length > 1 ? 's' : '' }} selected
      <span v-if="selectedIds.length < 4" class="badge-hint">(select up to 4)</span>
    </div>

    <!-- Compare section -->
    <div v-if="selectedIds.length > 0" class="compare-section">
      <div v-if="loadingCompare && compareData.size === 0" class="loading-state">
        <div class="spinner"></div>
        <p>Loading comparison data…</p>
      </div>

      <div v-show="compareData.size > 0" class="compare-content-wrapper" :class="{ 'is-loading-overlay': loadingCompare }">
        <div v-if="loadingCompare && compareData.size > 0" class="updating-overlay">
          <div class="spinner"></div>
          <p>Updating comparison…</p>
        </div>

        <!-- Tabs -->
        <div class="compare-tabs">
          <button :class="['tab-btn', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">Aperçu global</button>
          <button :class="['tab-btn', { active: activeTab === 'weekly' }]" @click="activeTab = 'weekly'">Par semaines</button>
          <button :class="['tab-btn', { active: activeTab === 'alignment' }]" @click="activeTab = 'alignment'">Alignement & Drift</button>
        </div>

        <!-- Tab: Overview -->
        <div v-show="activeTab === 'overview'" class="tab-content">
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
                    {{ compareData.get(id)?.run.result?.totalReturnPct?.toFixed(2) ?? '—' }}%
                  </td>
                </tr>
                <tr>
                  <td class="metric-label">Final Equity</td>
                  <td v-for="([id]) in Array.from(compareData.entries())" :key="id">
                    ${{ compareData.get(id)?.run.result?.finalEquity?.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '—' }}
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
        </div>

        <!-- Tab: Weekly Comparison -->
        <div v-if="activeTab === 'weekly'" class="tab-content">
          <div v-if="allWeeks.length === 0" class="no-data">
            Aucune donnée hebdomadaire disponible.
          </div>
          <div v-else class="kpi-comparison">
            <table class="compare-table">
              <thead>
                <tr>
                  <th>Semaine</th>
                  <th>Run / Stratégie</th>
                  <th>PnL</th>
                  <th>Trades</th>
                  <th>Win Rate</th>
                  <th>Profit Factor</th>
                  <th>Sharpe</th>
                  <th>Max DD %</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="week in allWeeks" :key="week">
                  <tr v-for="([runId, runData], idx) in getRunsWithWeek(week)" :key="week + '-' + runId">
                    <td v-if="idx === 0" :rowspan="getRunsWithWeek(week).length" class="week-label">
                      {{ week }}
                    </td>
                    <td :style="{ color: curveColors[selectedIds.indexOf(runId)] }">
                      {{ runData.run.strategyId }} ({{ runData.run.mode }})
                    </td>
                    <td :class="runData.weekStat.totalPnl >= 0 ? 'profit' : 'loss'">
                      ${{ runData.weekStat.totalPnl.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                    </td>
                    <td>
                      {{ runData.weekStat.totalTrades }} ({{ runData.weekStat.winningTrades }}V / {{ runData.weekStat.losingTrades }}D)
                    </td>
                    <td>
                      {{ runData.weekStat.winRatePct.toFixed(1) }}%
                    </td>
                    <td :class="runData.weekStat.profitFactor >= 1.5 ? 'profit' : runData.weekStat.profitFactor < 1 ? 'loss' : ''">
                      {{ runData.weekStat.profitFactor === 999 ? '∞' : runData.weekStat.profitFactor.toFixed(2) }}
                    </td>
                    <td :class="runData.weekStat.sharpeRatio >= 1 ? 'profit' : runData.weekStat.sharpeRatio < 0 ? 'loss' : ''">
                      {{ runData.weekStat.sharpeRatio.toFixed(2) }}
                    </td>
                    <td>
                      {{ runData.weekStat.maxDrawdownPct.toFixed(2) }}%
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Tab: Alignment & Drift -->
        <div v-if="activeTab === 'alignment'" class="tab-content">
          <div class="alignment-container">
            <div class="run-selector-row">
              <span class="label">Sélectionner un run :</span>
              <select v-model="selectedAlignmentRunId" class="run-select">
                <option v-for="id in selectedIds" :key="id" :value="id">
                  {{ compareData.get(id)?.run.strategyId }} ({{ compareData.get(id)?.run.symbol }}) · {{ compareData.get(id)?.run.mode }}
                </option>
              </select>
            </div>

            <div v-if="!selectedAlignmentRunId || !alignmentDetails.get(selectedAlignmentRunId)" class="no-data">
              Chargement ou aucune donnée disponible pour l'alignement.
            </div>

            <div v-else class="alignment-dashboard">
              <!-- Score d'alignement logique -->
              <div class="alignment-score-panel" :class="alignmentColorClass">
                <div class="score-header">
                  <span class="score-title">Score d'alignement logique</span>
                  <span class="score-value">{{ alignmentScore }}%</span>
                </div>
                <div class="score-bar-bg">
                  <div class="score-bar-fill" :style="{ width: alignmentScore + '%' }"></div>
                </div>
                <p class="score-desc">
                  {{ alignmentScore >= 95 ? 'Excellent : Le backtest et l\'exécution réelle concordent.' : alignmentScore >= 90 ? 'Alerte : Décalage léger détecté.' : 'Critique : Discordance de logique importante.' }}
                </p>
              </div>

              <!-- Split-Screen Timeline -->
              <div class="alignment-split-timeline">
                <div class="timeline-headers">
                  <div class="timeline-header bt-col">Théorique (Backtest)</div>
                  <div class="timeline-header connector-col"></div>
                  <div class="timeline-header live-col">Réel (Paper/Live)</div>
                </div>

                <div class="timeline-rows">
                  <div v-for="row in timelineRows" :key="row.id" class="timeline-row" :class="[row.anomaly ? 'has-anomaly' : 'perfect-match']">
                    <!-- Column Left: Backtest -->
                    <div class="timeline-col bt-col">
                      <div v-if="row.bt" class="trade-card bt-card">
                        <div class="card-title">
                          <span class="side-badge" :class="row.bt.side.toLowerCase()">{{ row.bt.side }}</span>
                          <span class="symbol">{{ row.bt.symbol }}</span>
                        </div>
                        <div class="card-price">${{ row.bt.price.toFixed(5) }}</div>
                        <div class="card-time">{{ formatDate(row.bt.filledAt || row.bt.createdAt) }}</div>
                        <div v-if="row.bt.correlationId" class="card-corr" :title="row.bt.correlationId">
                          Corr: {{ row.bt.correlationId.split('_').slice(-2).join('_') || row.bt.correlationId }}
                        </div>
                      </div>
                      <div v-else class="trade-card-placeholder empty-bt">
                        <span class="placeholder-label">—</span>
                      </div>
                    </div>

                    <!-- Column Middle: Connector -->
                    <div class="timeline-col connector-col">
                      <div v-if="row.anomaly" class="anomaly-connector" :class="row.anomaly.type.toLowerCase()">
                        <div class="connector-line"></div>
                        <span class="anomaly-badge" :title="row.anomaly.message">
                          {{ row.anomaly.type === 'TIME_DRIFT' ? `Δt: ${row.anomaly.deltaTimeMs / 1000}s` : row.anomaly.type === 'PRICE_DRIFT' ? `Δp: ${row.anomaly.deltaPrice.toFixed(5)}` : row.anomaly.type }}
                        </span>
                      </div>
                      <div v-else class="perfect-connector">
                        <div class="connector-line"></div>
                        <span class="perfect-badge">OK</span>
                      </div>
                    </div>

                    <!-- Column Right: Live -->
                    <div class="timeline-col live-col">
                      <div v-if="row.live" class="trade-card live-card">
                        <div class="card-title">
                          <span class="side-badge" :class="row.live.side.toLowerCase()">{{ row.live.side }}</span>
                          <span class="symbol">{{ row.live.symbol }}</span>
                        </div>
                        <div class="card-price">${{ row.live.price.toFixed(5) }}</div>
                        <div class="card-time">{{ formatDate(row.live.filledAt || row.live.createdAt) }}</div>
                        <div v-if="row.live.correlationId" class="card-corr" :title="row.live.correlationId">
                          Corr: {{ row.live.correlationId.split('_').slice(-2).join('_') || row.live.correlationId }}
                        </div>
                      </div>
                      <div v-else class="trade-card-placeholder empty-live">
                        <div class="black-hole">
                          <span class="warn-icon">⚠</span>
                          <span class="placeholder-label">Trou Noir (Non Exécuté)</span>
                        </div>
                      </div>
                    </div>
                  </div>
                  
                  <div v-if="timelineRows.length === 0" class="no-data">
                    Aucun trade enregistré pour l'alignement sur ce run.
                  </div>
                </div>
              </div>

              <!-- Mismatches table -->
              <div class="mismatches-section">
                <h3>Anomalies détectées ({{ alignmentDetails.get(selectedAlignmentRunId)?.anomalies.length || 0 }})</h3>
                <div v-if="(alignmentDetails.get(selectedAlignmentRunId)?.anomalies.length || 0) === 0" class="no-mismatches">
                  Aucune anomalie détectée sur ce run.
                </div>
                <table v-else class="compare-table anomaly-table">
                  <thead>
                    <tr>
                      <th>Type</th>
                      <th>Ordre</th>
                      <th>Message</th>
                      <th>Écart Prix</th>
                      <th>Écart Temps</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(anomaly, index) in alignmentDetails.get(selectedAlignmentRunId)?.anomalies" :key="index">
                      <td>
                        <span class="anomaly-type-badge" :class="anomaly.type.toLowerCase()">
                          {{ anomaly.type }}
                        </span>
                      </td>
                      <td><code>{{ anomaly.orderId || '—' }}</code></td>
                      <td>{{ anomaly.message }}</td>
                      <td>{{ anomaly.deltaPrice > 0 ? anomaly.deltaPrice.toFixed(5) : '—' }}</td>
                      <td>{{ anomaly.deltaTimeMs > 0 ? (anomaly.deltaTimeMs / 1000).toFixed(2) + 's' : '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

            </div>
          </div>
        </div>
      </div>
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

.runs-selector-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  gap: 1rem;
}

.runs-selector-header h3 {
  margin: 0;
}

.search-input {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 6px;
  padding: 0.35rem 0.75rem;
  font-size: 0.8rem;
  width: 250px;
  transition: border-color 0.15s;
}

.search-input:focus {
  outline: none;
  border-color: var(--accent);
}

.runs-selector-filters {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.filter-btn {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  border-radius: 4px;
  padding: 0.25rem 0.6rem;
  font-size: 0.75rem;
  cursor: pointer;
  transition: all 0.15s;
}

.filter-btn:hover {
  color: var(--text-primary);
  border-color: var(--text-secondary);
}

.filter-btn.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

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

.run-item-badge {
  display: inline-block;
  padding: 0.05rem 0.3rem;
  font-size: 0.6rem;
  font-weight: 700;
  border-radius: 4px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  margin-left: 0.3rem;
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

.compare-content-wrapper {
  position: relative;
  transition: opacity 0.2s;
}

.compare-content-wrapper.is-loading-overlay {
  opacity: 0.7;
  pointer-events: none;
}

.updating-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(26, 26, 46, 0.4);
  backdrop-filter: blur(1px);
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  color: var(--text-primary);
  font-size: 0.85rem;
  border-radius: 10px;
}

/* Tabs */
.compare-tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.5rem;
}

.tab-btn {
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  border-radius: 6px;
  padding: 0.4rem 1rem;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s;
}

.tab-btn:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.tab-btn.active {
  background: rgba(217, 119, 6, 0.12);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}

.tab-content {
  animation: fadeIn 0.2s ease-in-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Weekly Tab */
.week-label {
  vertical-align: middle;
  background: var(--bg-primary);
  font-weight: 700;
  text-align: center;
  border-right: 1px solid var(--border);
  font-size: 0.8rem;
  color: var(--text-primary) !important;
}

.no-data {
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
}

/* Alignment Tab */
.alignment-container {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.run-selector-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.5rem 1rem;
}

.run-selector-row .label {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.run-select {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 6px;
  padding: 0.35rem 0.75rem;
  font-size: 0.8rem;
  font-weight: 600;
  outline: none;
  cursor: pointer;
}

.run-select:focus {
  border-color: var(--accent);
}

/* Alignment Score */
.alignment-score-panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  transition: all 0.25s;
}

.alignment-score-panel.score-ok {
  border-color: rgba(34, 197, 94, 0.4);
  background: linear-gradient(135deg, var(--bg-card) 0%, rgba(34, 197, 94, 0.03) 100%);
  box-shadow: 0 0 15px rgba(34, 197, 94, 0.05);
}

.alignment-score-panel.score-warn {
  border-color: rgba(217, 119, 6, 0.4);
  background: linear-gradient(135deg, var(--bg-card) 0%, rgba(217, 119, 6, 0.03) 100%);
  box-shadow: 0 0 15px rgba(217, 119, 6, 0.05);
}

.alignment-score-panel.score-fail {
  border-color: rgba(239, 68, 68, 0.4);
  background: linear-gradient(135deg, var(--bg-card) 0%, rgba(239, 68, 68, 0.03) 100%);
  box-shadow: 0 0 15px rgba(239, 68, 68, 0.05);
}

.score-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.score-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.score-value {
  font-size: 1.5rem;
  font-weight: 800;
  font-family: 'JetBrains Mono', monospace;
}

.score-ok .score-value { color: var(--success); }
.score-warn .score-value { color: var(--warning); }
.score-fail .score-value { color: var(--danger); }

.score-bar-bg {
  height: 6px;
  background: var(--bg-primary);
  border-radius: 3px;
  overflow: hidden;
}

.score-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s ease-out;
}

.score-ok .score-bar-fill { background: var(--success); }
.score-warn .score-bar-fill { background: var(--warning); }
.score-fail .score-bar-fill { background: var(--danger); }

.score-desc {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin: 0;
}

/* Timeline Split Screen */
.alignment-split-timeline {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.timeline-headers {
  display: grid;
  grid-template-columns: 1fr 120px 1fr;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border);
  text-align: center;
  padding: 0.5rem 0;
}

.timeline-header {
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.timeline-rows {
  display: flex;
  flex-direction: column;
  padding: 1rem 0;
  max-height: 450px;
  overflow-y: auto;
  gap: 0.75rem;
}

.timeline-row {
  display: grid;
  grid-template-columns: 1fr 120px 1fr;
  align-items: center;
  padding: 0 0.5rem;
}

.timeline-col {
  display: flex;
  justify-content: center;
  width: 100%;
  box-sizing: border-box;
}

.bt-col { justify-content: flex-end; padding-right: 0.5rem; }
.live-col { justify-content: flex-start; padding-left: 0.5rem; }

/* Trade Cards */
.trade-card {
  width: 100%;
  max-width: 260px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  box-shadow: 0 2px 4px rgba(0,0,0,0.2);
}

.trade-card.bt-card {
  border-left: 3px solid #6366f1;
}

.trade-card.live-card {
  border-left: 3px solid var(--accent);
}

.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.side-badge {
  font-size: 0.6rem;
  font-weight: 700;
  padding: 0.05rem 0.25rem;
  border-radius: 4px;
  text-transform: uppercase;
}

.side-badge.buy { background: rgba(34, 197, 94, 0.15); color: var(--success); }
.side-badge.sell { background: rgba(239, 68, 68, 0.15); color: var(--danger); }

.card-title .symbol {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-primary);
}

.card-price {
  font-size: 0.8rem;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 700;
  color: var(--text-primary);
}

.card-time {
  font-size: 0.65rem;
  color: var(--text-secondary);
}

.card-corr {
  font-size: 0.55rem;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: monospace;
}

/* Placeholders */
.trade-card-placeholder {
  width: 100%;
  max-width: 260px;
  height: 64px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-bt .placeholder-label {
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.empty-live {
  background: rgba(239, 68, 68, 0.03);
  border-color: rgba(239, 68, 68, 0.3);
}

.black-hole {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.15rem;
}

.black-hole .warn-icon {
  color: var(--danger);
  font-size: 0.9rem;
}

.black-hole .placeholder-label {
  color: var(--danger);
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
}

/* Connectors */
.perfect-connector, .anomaly-connector {
  position: relative;
  width: 100%;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.connector-line {
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  z-index: 1;
}

.perfect-connector .connector-line {
  background: linear-gradient(90deg, #6366f1 0%, var(--success) 50%, var(--accent) 100%);
}

.anomaly-connector.time_drift .connector-line {
  background: linear-gradient(90deg, #6366f1 0%, var(--warning) 50%, var(--accent) 100%);
  border-top: 1px dashed var(--warning);
}

.anomaly-connector.price_drift .connector-line {
  background: linear-gradient(90deg, #6366f1 0%, var(--danger) 50%, var(--accent) 100%);
  border-top: 1px dashed var(--danger);
}

.anomaly-connector.missing_live .connector-line,
.anomaly-connector.ghost_live .connector-line {
  background: transparent;
}

.perfect-badge, .anomaly-badge {
  position: relative;
  z-index: 2;
  font-size: 0.6rem;
  font-weight: 700;
  padding: 0.1rem 0.35rem;
  border-radius: 10px;
  font-family: 'JetBrains Mono', monospace;
  box-shadow: 0 1px 3px rgba(0,0,0,0.4);
}

.perfect-badge {
  background: var(--success);
  color: #000;
}

.anomaly-connector.time_drift .anomaly-badge {
  background: var(--warning);
  color: #000;
}

.anomaly-connector.price_drift .anomaly-badge {
  background: var(--danger);
  color: #fff;
}

/* Mismatches section */
.mismatches-section {
  margin-top: 1rem;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem;
}

.no-mismatches {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.anomaly-table {
  margin-top: 0.5rem;
}

.anomaly-type-badge {
  display: inline-block;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  font-size: 0.6rem;
  font-weight: 700;
  text-transform: uppercase;
}

.anomaly-type-badge.missing_live { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
.anomaly-type-badge.ghost_live { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
.anomaly-type-badge.time_drift { background: rgba(217, 119, 6, 0.15); color: var(--warning); }
.anomaly-type-badge.price_drift { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
</style>
