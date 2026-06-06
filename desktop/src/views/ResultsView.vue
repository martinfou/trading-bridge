<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { useRunWebSocket } from '@/composables/useRunWebSocket'
import type { RunResult, Trade, Bar } from '@/types/control-plane'
import KpiStrip from '@/components/KpiStrip.vue'
import EquityChart from '@/components/EquityChart.vue'
import TradeTable from '@/components/TradeTable.vue'
import TradeChart from '@/components/TradeChart.vue'
import MonteCarloChart from '@/components/MonteCarloChart.vue'

const route = useRoute()
const router = useRouter()
const { getRun, getTrades, getEquityCurve, getBars, getMonteCarlo } = useControlPlane()
const ws = useRunWebSocket()

const run = ref<RunResult | null>(null)
const trades = ref<Trade[]>([])
const equityCurve = ref<number[]>([])
const bars = ref<Bar[]>([])
const loadingBars = ref(false)
const barsError = ref<string | null>(null)
const loading = ref(true)
const viewError = ref<string | null>(null)
const activeTab = ref<'overview' | 'chart' | 'monte-carlo' | 'trades'>('overview')

const monteCarloStats = ref<any | null>(null)
const loadingMc = ref(false)
const mcError = ref<string | null>(null)

const mcPaths = ref<number[][]>([])

function stretchPath(path: number[], targetLength: number): number[] {
  if (path.length === 0 || targetLength === 0) return []
  if (path.length === 1) return Array(targetLength).fill(path[0])
  if (targetLength === 1) return [path[0]]

  const stretched: number[] = []
  const N = path.length - 1
  for (let j = 0; j < targetLength; j++) {
    const idx = Math.min(N, Math.floor(j * N / (targetLength - 1)))
    stretched.push(path[idx])
  }
  return stretched
}

async function loadMonteCarloData() {
  if (monteCarloStats.value || !runId) return
  loadingMc.value = true
  mcError.value = null
  try {
    monteCarloStats.value = await getMonteCarlo(runId, 1000, 3)
    
    // Generate Monte Carlo paths client-side for visualization (100 runs)
    if (trades.value.length > 0) {
      const initialCap = run.value?.configSnapshot?.capital || 1000.0
      const tradePnls = trades.value.map(t => t.pnl)
      const simulatedPaths: number[][] = []
      
      for (let r = 0; r < 100; r++) {
        const shuffled = [...tradePnls]
        for (let i = shuffled.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]]
        }
        
        const path: number[] = [initialCap]
        let eq = initialCap
        for (const pnl of shuffled) {
          eq += pnl
          path.push(eq)
        }
        simulatedPaths.push(path)
      }
      
      // Sort simulated paths by their final P&L
      simulatedPaths.sort((a, b) => a[a.length - 1] - b[b.length - 1])
      
      // Stretch paths to match the baseline equity curve length (time-line matching)
      const targetLength = equityCurve.value.length || (trades.value.length + 1)
      mcPaths.value = simulatedPaths.map(p => stretchPath(p, targetLength))
    }
  } catch (e: any) {
    mcError.value = `Failed to load Monte Carlo simulation: ${e.message}`
  } finally {
    loadingMc.value = false
  }
}

async function loadBarsData() {
  if (bars.value.length > 0 || !runId) return
  loadingBars.value = true
  barsError.value = null
  try {
    bars.value = await getBars(runId)
  } catch (e: any) {
    barsError.value = `Failed to load price bars: ${e.message}`
  } finally {
    loadingBars.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'chart') {
    loadBarsData()
  } else if (tab === 'monte-carlo') {
    loadMonteCarloData()
  }
})

const runId = typeof route.params.runId === 'string' ? route.params.runId : null

async function loadRun() {
  if (!runId) {
    loading.value = false
    return
  }

  loading.value = true
  viewError.value = null

  try {
    const [r, t, curve] = await Promise.all([
      getRun(runId),
      getTrades(runId),
      getEquityCurve(runId),
    ])
    run.value = r
    trades.value = t
    equityCurve.value = curve
  } catch (e: any) {
    viewError.value = `Failed to load run: ${e.message}`
  } finally {
    loading.value = false
  }

  // WebSocket for live updates if run is still running
  if (run.value?.status === 'RUNNING') {
    ws.connect(runId)
  }
}

function reRun() {
  if (!run.value) return
  const symbol = run.value.symbol.replace(/_/g, '/')
  router.push(`/dashboard?strategyId=${run.value.strategyId}&symbol=${symbol}`)
}

function formatDuration(startIso?: string, endIso?: string): string {
  if (!startIso) return '—'
  const start = new Date(startIso)
  const end = endIso ? new Date(endIso) : new Date()
  const ms = end.getTime() - start.getTime()
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ${sec % 60}s`
  const h = Math.floor(min / 60)
  return `${h}h ${min % 60}m`
}

function formatDate(iso?: string): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('fr-CA', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatDateOnly(iso?: string): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('fr-CA', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

onMounted(loadRun)

onUnmounted(() => {
  ws.disconnect()
})
</script>

<template>
  <div class="view">
    <!-- No run selected -->
    <template v-if="!runId">
      <h1>Results</h1>
      <p class="subtitle">Select a run from the Dashboard to see detailed results.</p>
      <div class="placeholder-card">
        <p>Run a backtest first, then come here to analyse the results in detail.</p>
      </div>
    </template>

    <!-- Loading -->
    <div v-else-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Loading run {{ runId }}…</p>
    </div>

    <!-- Error -->
    <div v-else-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Results -->
    <template v-else-if="run">
      <!-- Header -->
      <div class="result-header">
        <div>
          <h1>{{ run.strategyId }}</h1>
          <div class="result-meta">
            <span class="meta-chip symbol">{{ run.symbol }}</span>
            <span :class="['status-badge', run.status === 'COMPLETED' ? 'ok' : run.status === 'FAILED' ? 'fail' : 'running']">
              {{ run.status }}
            </span>
            <span class="meta-chip">{{ run.runId?.slice(0, 8) || '—' }}…</span>
          </div>
        </div>
        <div class="header-actions">
          <button class="action-btn" @click="reRun">↻ Re-run</button>
        </div>
      </div>

      <!-- Run info -->
      <div class="info-strip">
        <div class="info-item">
          <span class="info-label">Started</span>
          <span class="info-value">{{ formatDate(run.startedAt) }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">Duration</span>
          <span class="info-value">{{ formatDuration(run.startedAt, run.completedAt) }}</span>
        </div>
        <div v-if="run.result && run.result.periodStart && run.result.periodEnd" class="info-item">
          <span class="info-label">Backtest Period</span>
          <span class="info-value">{{ formatDateOnly(run.result.periodStart) }} – {{ formatDateOnly(run.result.periodEnd) }}</span>
        </div>
        <div v-if="run.result" class="info-item">
          <span class="info-label">Commission</span>
          <span class="info-value">${{ run.result.totalCommission?.toFixed(2) || '0.00' }}</span>
        </div>
        <div v-if="run.result" class="info-item">
          <span class="info-label">Slippage</span>
          <span class="info-value">${{ run.result.totalSlippage?.toFixed(2) || '0.00' }}</span>
        </div>
      </div>

      <!-- Tabs -->
      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">
          Overview
        </button>
        <button :class="['tab', { active: activeTab === 'chart' }]" @click="activeTab = 'chart'">
          Price Chart
        </button>
        <button :class="['tab', { active: activeTab === 'monte-carlo' }]" @click="activeTab = 'monte-carlo'">
          Monte Carlo
        </button>
        <button :class="['tab', { active: activeTab === 'trades' }]" @click="activeTab = 'trades'">
          Trades
          <span class="tab-count">{{ trades.length }}</span>
        </button>
      </div>

      <!-- Overview tab -->
      <template v-if="activeTab === 'overview'">
        <!-- KPIs -->
        <div v-if="run.result" class="section">
          <KpiStrip
            :sharpe="run.result.sharpeRatio ?? null"
            :profit-factor="run.result.profitFactor ?? null"
            :max-dd="run.result.maxDrawdownPct ?? null"
            :total-trades="run.result.totalTrades ?? null"
            :win-rate="run.result.winRatePct ?? null"
            :total-return="run.result.totalReturnPct ?? null"
            :final-equity="run.result.finalEquity ?? null"
          />
        </div>

        <!-- Equity Curve -->
        <div class="section chart-section">
          <h3>Equity Curve</h3>
          <EquityChart
            v-if="equityCurve.length > 0"
            :data="equityCurve"
            :period-start="run.result?.periodStart"
            :period-end="run.result?.periodEnd"
            :height="350"
            :show-time-scale="true"
          />
          <p v-else class="no-data">Equity curve not available.</p>
        </div>
      </template>

      <!-- Price Chart tab -->
      <div v-if="activeTab === 'chart'" class="section">
        <div v-if="loadingBars" class="loading-state">
          <div class="spinner"></div>
          <p>Loading price bars…</p>
        </div>
        <div v-else-if="barsError" class="banner error">{{ barsError }}</div>
        <template v-else>
          <TradeChart :bars="bars" :trades="trades" :height="450" />
        </template>
      </div>

      <!-- Monte Carlo tab -->
      <div v-if="activeTab === 'monte-carlo'" class="section">
        <div v-if="loadingMc" class="loading-state">
          <div class="spinner"></div>
          <p>Running Monte Carlo simulation…</p>
        </div>
        <div v-else-if="mcError" class="banner error">{{ mcError }}</div>
        <template v-else-if="monteCarloStats">
          <!-- Monte Carlo Stats Grid -->
          <div class="mc-stats-grid">
            <div class="mc-stat-card">
              <span class="mc-stat-label">95% VaR (P&L)</span>
              <span :class="['mc-stat-value', monteCarloStats.var95 >= 0 ? 'profit' : 'loss']">
                ${{ monteCarloStats.var95.toFixed(2) }}
              </span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Loss Probability</span>
              <span :class="['mc-stat-value', monteCarloStats.probabilityOfLoss > 5.0 ? 'warning' : 'safe']">
                {{ monteCarloStats.probabilityOfLoss.toFixed(1) }}%
              </span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Worst P&L</span>
              <span class="mc-stat-value loss">${{ monteCarloStats.worstPnl.toFixed(2) }}</span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Best P&L</span>
              <span class="mc-stat-value profit">${{ monteCarloStats.bestPnl.toFixed(2) }}</span>
            </div>
          </div>

          <div class="mc-percentiles-grid">
            <div class="mc-stat-card">
              <span class="mc-stat-label">Median Drawdown (50th)</span>
              <span class="mc-stat-value">{{ monteCarloStats.drawdownPercentiles[2]?.toFixed(2) }}%</span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Drawdown 95th</span>
              <span class="mc-stat-value warning">{{ monteCarloStats.drawdownPercentiles[4]?.toFixed(2) }}%</span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Median Sharpe (50th)</span>
              <span class="mc-stat-value">{{ monteCarloStats.sharpePercentiles[2]?.toFixed(2) }}</span>
            </div>
            <div class="mc-stat-card">
              <span class="mc-stat-label">Sharpe 5th</span>
              <span class="mc-stat-value safe">{{ monteCarloStats.sharpePercentiles[0]?.toFixed(2) }}</span>
            </div>
          </div>

          <!-- Monte Carlo Chart -->
          <div class="section chart-section">
            <h3>Monte Carlo Path Distribution (100 runs)</h3>
            <MonteCarloChart
              v-if="equityCurve.length > 0 && mcPaths.length > 0"
              :baseline="equityCurve"
              :paths="mcPaths"
              :period-start="run.result?.periodStart"
              :period-end="run.result?.periodEnd"
              :height="400"
            />
            <div class="chart-legend">
              <span class="legend-item"><span class="legend-dot baseline"></span>Baseline</span>
              <span class="legend-item"><span class="legend-dot p95"></span>95th percentile (Best)</span>
              <span class="legend-item"><span class="legend-dot p50"></span>50th percentile (Median)</span>
              <span class="legend-item"><span class="legend-dot p5"></span>5th percentile (Worst)</span>
            </div>
          </div>
        </template>
      </div>

      <!-- Trades tab -->
      <div v-if="activeTab === 'trades'" class="section">
        <TradeTable :trades="trades" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }

h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
h3 { font-size: 0.9rem; margin-bottom: 0.5rem; color: var(--text-secondary); }

.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }

.placeholder-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 2rem;
  color: var(--text-secondary);
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 3rem;
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

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.banner.error {
  background: #2d1212;
  color: #fca5a5;
  border: 1px solid #7f1d1d;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 0.75rem;
}

.result-meta {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-top: 0.25rem;
}

.meta-chip {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.15rem 0.5rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.meta-chip.symbol {
  color: var(--accent);
  border-color: var(--accent);
}

.status-badge {
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
}

.status-badge.ok { background: #064e3b; color: #6ee7b7; }
.status-badge.fail { background: #450a0a; color: #fca5a5; }
.status-badge.running { background: #1e3a5f; color: #93c5fd; }

.header-actions {
  display: flex;
  gap: 0.5rem;
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

.info-strip {
  display: flex;
  gap: 1rem;
  margin-bottom: 1.25rem;
  padding: 0.75rem 1rem;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  flex-wrap: wrap;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.info-label {
  font-size: 0.65rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.info-value {
  font-size: 0.85rem;
  color: var(--text-primary);
}

.tabs {
  display: flex;
  gap: 0.4rem;
  margin-bottom: 1rem;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.4rem 0.85rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.15s;
}

.tab:hover {
  border-color: #444;
  color: var(--text-primary);
}

.tab.active {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(217, 119, 6, 0.08);
}

.tab-count {
  background: var(--bg-primary);
  border-radius: 10px;
  padding: 0.05rem 0.45rem;
  font-size: 0.65rem;
  font-weight: 600;
}

.section {
  margin-bottom: 1.25rem;
}

.chart-section {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem;
}

.no-data {
  padding: 2rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.mc-stats-grid,
.mc-percentiles-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
  margin-bottom: 1.25rem;
}

.mc-stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.mc-stat-label {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.mc-stat-value {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-primary);
}

.mc-stat-value.profit {
  color: #10b981;
}

.mc-stat-value.loss {
  color: #ef4444;
}

.mc-stat-value.warning {
  color: #f59e0b;
}

.mc-stat-value.safe {
  color: #3b82f6;
}

.chart-legend {
  display: flex;
  gap: 1.5rem;
  margin-top: 0.75rem;
  justify-content: center;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.legend-dot {
  width: 12px;
  height: 4px;
  border-radius: 2px;
}

.legend-dot.baseline { background: #d97706; }
.legend-dot.p95 { background: #10b981; }
.legend-dot.p50 { background: #3b82f6; }
.legend-dot.p5 { background: #ef4444; }
</style>
