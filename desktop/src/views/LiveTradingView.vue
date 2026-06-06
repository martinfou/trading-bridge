<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useControlPlane } from '../composables/useControlPlane'
import TradeChart from '../components/TradeChart.vue'
import TradeTable from '../components/TradeTable.vue'
import KpiStrip from '../components/KpiStrip.vue'
import EquityChart from '../components/EquityChart.vue'
import type { Bar, Trade } from '@/types/control-plane'

const { getControlSummary, killStrategy, getBars, getTrades, getEquityCurve } = useControlPlane()

const summary = ref<any>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const selectedRunId = ref<string | null>(null)
const activeTab = ref<'overview' | 'chart' | 'trades' | 'positions'>('overview')

// Telemetry polling timer
let pollTimer: any = null

async function fetchSummary() {
  try {
    const data = await getControlSummary()
    summary.value = data
    error.value = null
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// Filter runs to display only live & paper strategies
const liveRuns = computed(() => {
  if (!summary.value || !summary.value.runs) return []
  return summary.value.runs.filter((r: any) => r.mode === 'LIVE' || r.mode === 'PAPER')
})

const selectedRun = computed(() => {
  if (!selectedRunId.value || !liveRuns.value.length) return null
  return liveRuns.value.find((r: any) => r.runId === selectedRunId.value) || null
})

// Statistics computed from active strategies
const stats = computed(() => {
  const list = liveRuns.value
  const activeLive = list.filter((r: any) => r.mode === 'LIVE' && r.status === 'RUNNING').length
  const activePaper = list.filter((r: any) => r.mode === 'PAPER' && r.status === 'RUNNING').length
  
  let totalAllocated = 0
  let staleCount = 0
  let breachCount = 0

  list.forEach((r: any) => {
    if (r.configSnapshot && r.configSnapshot.capital) {
      totalAllocated += r.configSnapshot.capital
    }
    if (r.isStale) staleCount++
    if (r.dailyDdBreached) breachCount++
  })

  return { activeLive, activePaper, totalAllocated, staleCount, breachCount }
})

// Inspection detailed data
const inspectBars = ref<Bar[]>([])
const inspectTrades = ref<Trade[]>([])
const inspectEquity = ref<number[]>([])
const loadingInspect = ref(false)
const inspectError = ref<string | null>(null)

async function inspectRun(runId: string) {
  selectedRunId.value = runId
  loadingInspect.value = true
  inspectError.value = null
  activeTab.value = 'overview'
  
  try {
    const [barsData, tradesData, equityData] = await Promise.all([
      getBars(runId).catch(() => []),
      getTrades(runId).catch(() => []),
      getEquityCurve(runId).catch(() => [])
    ])
    inspectBars.value = barsData
    inspectTrades.value = tradesData
    inspectEquity.value = equityData
  } catch (e: any) {
    inspectError.value = 'Failed to load strategy telemetry details: ' + e.message
  } finally {
    loadingInspect.value = false
  }
}

async function triggerKill(runRecord: any) {
  const confirmed = confirm(`Are you sure you want to trigger the Kill Switch for strategy "${runRecord.strategyId}" (${runRecord.symbol})? This will immediately halt trading execution.`)
  if (!confirmed) return
  
  try {
    await killStrategy(runRecord.strategyId)
    alert(`Kill signal sent successfully to strategy deactivation registry.`)
    fetchSummary()
  } catch (e: any) {
    alert(`Deactivation failed: ${e.message}`)
  }
}

onMounted(() => {
  fetchSummary()
  pollTimer = setInterval(fetchSummary, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="view-container">
    <div class="header">
      <div class="title-section">
        <h2>Live Room 📡</h2>
        <p class="subtitle">Prop-shop real-time operations board</p>
      </div>
      <button class="btn btn-secondary" @click="fetchSummary" :disabled="loading">
        Refresh Telemetry
      </button>
    </div>

    <div v-if="error" class="banner error mb-4">{{ error }}</div>

    <!-- Active operational summary statistics -->
    <div class="stats-strip mb-6">
      <div class="stat-card">
        <span class="stat-label">Live Running</span>
        <span class="stat-value text-success">{{ stats.activeLive }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Paper Running</span>
        <span class="stat-value text-info">{{ stats.activePaper }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Total Allocation</span>
        <span class="stat-value">${{ stats.totalAllocated.toLocaleString() }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Stale Heartbeats</span>
        <span :class="['stat-value', stats.staleCount > 0 ? 'text-danger' : '']">{{ stats.staleCount }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Drawdown Paused</span>
        <span :class="['stat-value', stats.breachCount > 0 ? 'text-danger' : '']">{{ stats.breachCount }}</span>
      </div>
    </div>

    <!-- Active alert section if problems exist -->
    <div v-if="stats.staleCount > 0 || stats.breachCount > 0" class="banner warning mb-6">
      <strong>Operational Alerts Active:</strong> 
      <span v-if="stats.staleCount > 0">There are {{ stats.staleCount }} running processes showing stale telemetry heartbeats.</span>
      <span v-if="stats.breachCount > 0"> Drawdown threshold deactivations occurred on {{ stats.breachCount }} runs.</span>
    </div>

    <div v-if="loading && !summary" class="loading-state">
      <div class="spinner"></div>
      <p>Loading real-time operational telemetry…</p>
    </div>

    <div v-else-if="liveRuns.length === 0" class="empty-state">
      <div class="empty-icon">📡</div>
      <h3>No Live or Paper Strategies Running</h3>
      <p>Deploy a strategy to Live/Paper mode from the Strategies catalog or control plane to begin real-time operations monitoring.</p>
    </div>

    <div v-else class="live-grid mb-6">
      <div 
        v-for="run in liveRuns" 
        :key="run.runId" 
        :class="['strategy-card', { active: selectedRunId === run.runId, stale: run.isStale }]"
      >
        <div class="card-header">
          <div>
            <h4 class="strat-title">{{ run.strategyId }}</h4>
            <span class="symbol-badge">{{ run.symbol }}</span>
          </div>
          <span :class="['mode-badge', run.mode.toLowerCase()]">{{ run.mode }}</span>
        </div>

        <div class="card-body">
          <div class="metric-row">
            <span>Status</span>
            <span :class="['status-val', run.status.toLowerCase(), { stale: run.isStale }]">
              {{ run.isStale ? 'STALE' : run.status }}
            </span>
          </div>
          <div class="metric-row">
            <span>Daily Drawdown</span>
            <span :class="['metric-val', run.dailyDdBreached ? 'text-danger' : '']">
              {{ run.dailyDrawdownPct ? run.dailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}
            </span>
          </div>
          <div class="metric-row">
            <span>Max Daily Drawdown</span>
            <span>{{ run.maxDailyDrawdownPct ? run.maxDailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}</span>
          </div>
          <div class="metric-row">
            <span>Event Count</span>
            <span>{{ run.eventCount }}</span>
          </div>
        </div>

        <div class="card-footer">
          <button class="btn btn-secondary btn-sm flex-grow" @click="inspectRun(run.runId)">
            Inspect
          </button>
          <button class="btn btn-danger btn-sm" @click="triggerKill(run)">
            Kill
          </button>
        </div>
      </div>
    </div>

    <!-- Active inspect drill-down workspace -->
    <div v-if="selectedRun" class="inspect-workspace section">
      <div class="workspace-header">
        <div>
          <h3>Monitoring: {{ selectedRun.strategyId }}</h3>
          <p class="run-meta">ID: {{ selectedRun.runId }} | Symbol: {{ selectedRun.symbol }} | Mode: {{ selectedRun.mode }}</p>
        </div>
        <button class="btn-close" @click="selectedRunId = null">×</button>
      </div>

      <div class="tab-bar">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">
          Overview
        </button>
        <button :class="['tab', { active: activeTab === 'positions' }]" @click="activeTab = 'positions'">
          Open Positions
          <span class="tab-count">{{ selectedRun.positions ? selectedRun.positions.length : 0 }}</span>
        </button>
        <button :class="['tab', { active: activeTab === 'chart' }]" @click="activeTab = 'chart'">
          Price Chart
        </button>
        <button :class="['tab', { active: activeTab === 'trades' }]" @click="activeTab = 'trades'">
          Trades History
          <span class="tab-count">{{ inspectTrades.length }}</span>
        </button>
      </div>

      <div v-if="loadingInspect" class="loading-state py-6">
        <div class="spinner"></div>
        <p>Loading real-time inspection telemetry…</p>
      </div>

      <div v-else-if="inspectError" class="banner error my-4">{{ inspectError }}</div>

      <div v-else class="workspace-content">
        <!-- Overview tab -->
        <div v-if="activeTab === 'overview'" class="tab-panel">
          <div class="kpis-wrapper mb-6">
            <div class="kpi-box">
              <span class="kpi-lbl">Current Daily Drawdown</span>
              <span :class="['kpi-val', selectedRun.dailyDdBreached ? 'text-danger' : '']">
                {{ selectedRun.dailyDrawdownPct ? selectedRun.dailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}
              </span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Max Daily Drawdown</span>
              <span class="kpi-val">{{ selectedRun.maxDailyDrawdownPct ? selectedRun.maxDailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}</span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Allocated Capital</span>
              <span class="kpi-val">${{ selectedRun.configSnapshot?.capital?.toLocaleString() ?? '—' }}</span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Event Sequence</span>
              <span class="kpi-val">#{{ selectedRun.eventCount }}</span>
            </div>
          </div>

          <div class="equity-curve-section">
            <h4>Live Performance Curve</h4>
            <EquityChart v-if="inspectEquity.length" :data="inspectEquity" :height="260" :show-time-scale="true" />
            <p v-else class="no-data">Performance data curve not available.</p>
          </div>
        </div>

        <!-- Open Positions tab -->
        <div v-if="activeTab === 'positions'" class="tab-panel">
          <div v-if="!selectedRun.positions || selectedRun.positions.length === 0" class="empty-panel-state">
            <p>No active floating positions currently held by this strategy.</p>
          </div>
          <table v-else class="live-positions-table">
            <thead>
              <tr>
                <th>Symbol</th>
                <th>Side</th>
                <th>Quantity</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(p, i) in selectedRun.positions" :key="i">
                <td>{{ p.symbol }}</td>
                <td>
                  <span :class="['side-badge', p.side.toLowerCase()]">{{ p.side }}</span>
                </td>
                <td class="num">{{ p.quantity }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Price Chart tab -->
        <div v-if="activeTab === 'chart'" class="tab-panel">
          <TradeChart :bars="inspectBars" :trades="inspectTrades" :height="400" />
        </div>

        <!-- Trades History tab -->
        <div v-if="activeTab === 'trades'" class="tab-panel">
          <TradeTable :trades="inspectTrades" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view-container {
  max-width: 1280px;
  margin: 0 auto;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.title-section h2 {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.subtitle {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.stats-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 1rem;
}

.stat-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
  text-transform: uppercase;
  margin-bottom: 0.35rem;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
}

.live-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.25rem;
}

.strategy-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.15rem;
  display: flex;
  flex-direction: column;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.strategy-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.strategy-card.stale {
  border-color: var(--danger);
  opacity: 0.85;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.strat-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
}

.symbol-badge {
  font-size: 0.7rem;
  background: var(--bg-card);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  color: var(--text-secondary);
}

.mode-badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
}

.mode-badge.live {
  background: rgba(34, 197, 94, 0.1);
  color: var(--success);
  border: 1px solid rgba(34, 197, 94, 0.2);
}

.mode-badge.paper {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.2);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  margin-bottom: 1.25rem;
  font-size: 0.75rem;
}

.metric-row {
  display: flex;
  justify-content: space-between;
  color: var(--text-secondary);
}

.metric-val {
  font-weight: 500;
  color: var(--text-primary);
}

.status-val {
  font-weight: 700;
}

.status-val.running { color: var(--success); }
.status-val.paused { color: var(--warning); }
.status-val.stale { color: var(--danger); }

.card-footer {
  margin-top: auto;
  display: flex;
  gap: 0.5rem;
}

.btn {
  padding: 0.45rem 0.9rem;
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: background 0.15s;
}

.btn-secondary {
  background: #222;
  color: var(--text-primary);
}

.btn-secondary:hover { background: #333; }

.btn-danger {
  background: var(--danger);
  color: #fff;
}

.btn-danger:hover { background: #dc2626; }

.btn-sm {
  padding: 0.35rem 0.6rem;
  font-size: 0.75rem;
}

.flex-grow {
  flex-grow: 1;
}

.inspect-workspace {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1.5rem;
}

.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.workspace-header h3 {
  font-size: 1.1rem;
  font-weight: 600;
}

.run-meta {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: 1.5rem;
  cursor: pointer;
}

.tab-bar {
  display: flex;
  gap: 0.5rem;
  border-bottom: 1px solid var(--border);
  margin-bottom: 1.5rem;
}

.tab {
  background: none;
  border: none;
  padding: 0.6rem 1rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.tab:hover {
  color: var(--text-primary);
}

.tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.tab-count {
  font-size: 0.7rem;
  background: var(--bg-card);
  padding: 0.05rem 0.3rem;
  border-radius: 4px;
  margin-left: 0.25rem;
  color: var(--text-secondary);
}

.kpis-wrapper {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
}

.kpi-box {
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: 0.85rem;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
}

.kpi-lbl {
  font-size: 0.7rem;
  color: var(--text-secondary);
  margin-bottom: 0.25rem;
}

.kpi-val {
  font-size: 1.25rem;
  font-weight: 700;
}

.live-positions-table {
  width: 100%;
  border-collapse: collapse;
}

.live-positions-table th {
  text-align: left;
  background: var(--bg-card);
  padding: 0.6rem 0.85rem;
  color: var(--text-secondary);
  font-size: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.live-positions-table td {
  padding: 0.75rem 0.85rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.8rem;
}

.live-positions-table td.num {
  font-family: monospace;
}

.side-badge {
  padding: 0.1rem 0.35rem;
  border-radius: 3px;
  font-size: 0.7rem;
  font-weight: 700;
}

.side-badge.buy {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.side-badge.sell {
  background: rgba(239, 68, 68, 0.15);
  color: var(--danger);
}

.empty-panel-state {
  padding: 3rem;
  text-align: center;
  color: var(--text-secondary);
}

.no-data {
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.loading-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
}

.spinner {
  width: 28px;
  height: 28px;
  border: 3px solid rgba(255, 255, 255, 0.1);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 1rem;
}

.empty-icon {
  font-size: 2.5rem;
  margin-bottom: 1rem;
}

.mb-4 { margin-bottom: 1rem; }
.mb-6 { margin-bottom: 1.5rem; }
.py-6 { padding-top: 1.5rem; padding-bottom: 1.5rem; }

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  font-size: 0.8rem;
}

.banner.error {
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.2);
  color: var(--danger);
}

.banner.warning {
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid rgba(245, 158, 11, 0.2);
  color: var(--warning);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
