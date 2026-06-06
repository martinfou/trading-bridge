<script setup lang="ts">
import { ref, computed, onUnmounted, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Key } from '@lucide/vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { RunResult } from '@/types/control-plane'
import BacktestForm from '@/components/BacktestForm.vue'
import KpiStrip from '@/components/KpiStrip.vue'
import EquityChart from '@/components/EquityChart.vue'

const router = useRouter()
const route = useRoute()
const { getRun, getEquityCurve, getHistoricalDataStatus, downloadHistoricalData, deleteHistoricalData } = useControlPlane()

interface ActiveRun {
  runId: string
  symbol: string
  status: string
  mode?: string
  result: RunResult | null
  equityCurve: number[]
  error: string | null
}

const activeRuns = ref<ActiveRun[]>([])
const selectedRunId = ref<string>('')
const viewError = ref<string | null>(null)
const polling = ref(false)

const preselectedStrategy = ref<string | undefined>(
  typeof route.query.strategyId === 'string' ? route.query.strategyId : undefined,
)
const preselectedSymbol = ref<string | undefined>(
  typeof route.query.symbol === 'string' ? route.query.symbol : undefined,
)

const pollTimers = ref<ReturnType<typeof setInterval>[]>([])

// Computed properties to bind to detailed view (KpiStrip and EquityChart)
const selectedRun = computed(() => {
  return activeRuns.value.find(r => r.runId === selectedRunId.value) || null
})

function onRunsStart(started: Array<{ symbol: string, runId: string }>) {
  viewError.value = null
  
  // Clear any existing polling timers
  pollTimers.value.forEach(timer => clearInterval(timer))
  pollTimers.value = []
  
  activeRuns.value = started.map(item => ({
    runId: item.runId,
    symbol: item.symbol,
    status: 'RUNNING',
    result: null,
    equityCurve: [],
    error: null,
  }))
  
  selectedRunId.value = started[0].runId
  polling.value = true
  
  started.forEach(item => {
    pollRun(item.runId)
  })
}

function pollRun(runId: string) {
  const timer = setInterval(async () => {
    try {
      const r = await getRun(runId)
      const active = activeRuns.value.find(item => item.runId === runId)
      if (!active) {
        clearInterval(timer)
        return
      }
      active.status = r.status
      if (r.mode) {
        active.mode = r.mode
      }
      if (r.status === 'COMPLETED' || r.status === 'FAILED') {
        clearInterval(timer)
        
        // Remove from active pollTimers list
        const idx = pollTimers.value.indexOf(timer)
        if (idx >= 0) pollTimers.value.splice(idx, 1)
        
        active.result = r
        if (r.status === 'COMPLETED') {
          active.equityCurve = await getEquityCurve(runId)
        } else if (r.status === 'FAILED') {
          active.error = r.error || 'Unknown error'
        }
        
        // Force Vue reactivity update
        activeRuns.value = [...activeRuns.value]
        
        // If all runs have finished, set polling to false
        if (pollTimers.value.length === 0) {
          polling.value = false
        }
      }
    } catch {
      // keep polling
    }
  }, 1000)
  pollTimers.value.push(timer)
}

function onFormError(msg: string) {
  viewError.value = msg
}

function viewFullResults() {
  if (selectedRunId.value) {
    const active = activeRuns.value.find(item => item.runId === selectedRunId.value)
    if (active && (active.mode === 'PAPER' || active.mode === 'LIVE')) {
      router.push({ path: '/live-trading', query: { runId: selectedRunId.value } })
    } else {
      router.push(`/results/${selectedRunId.value}`)
    }
  }
}

function handleRunCardClick(run: ActiveRun) {
  if (run.mode === 'PAPER' || run.mode === 'LIVE') {
    router.push({ path: '/live-trading', query: { runId: run.runId } })
  } else {
    selectedRunId.value = run.runId
  }
}

function formatDate(isoStr?: string) {
  if (!isoStr) return ''
  try {
    const d = new Date(isoStr)
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
  } catch {
    return isoStr
  }
}

function getRunModeLabel(run: ActiveRun): string {
  const mode = run.result?.mode || (run.result as any)?.configSnapshot?.mode || 'BACKTEST'
  if (mode === 'PAPER') return 'Paper trading'
  if (mode === 'LIVE') return 'Live trading'
  return 'Backtest'
}

// Historical Data Manager state and methods
const showDataManager = ref(false)
const dataTimeframe = ref<'h1' | 'm1'>('h1')
const dataStatus = ref<any[]>([])
const activeDownloads = ref<string[]>([])
const activeTasks = ref<any[]>([])
const loadError = ref<string | null>(null)
const actionLoading = ref(false)

const selectedPair = ref('eurusd')
const selectedYear = ref(new Date().getFullYear())
const selectedTf = ref<'h1' | 'm1'>('h1')
const downloadMode = ref<'single' | 'range' | 'all'>('single')
const selectedStartYear = ref(2006)
const selectedEndYear = ref(new Date().getFullYear())

const pairsList = ['eurusd', 'gbpusd', 'gbpjpy', 'usdcad', 'usdjpy', 'audusd', 'nzdusd', 'usdchf']
const yearsList = computed(() => {
  const current = new Date().getFullYear()
  const years = []
  for (let y = current; y >= 2006; y--) {
    years.push(y)
  }
  return years
})

async function refreshDataStatus() {
  try {
    loadError.value = null
    const res = await getHistoricalDataStatus(dataTimeframe.value)
    dataStatus.value = res.status || []
    activeDownloads.value = res.activeDownloads || []
    activeTasks.value = res.activeTasks || []
    if (activeTasks.value.length > 0) {
      showDataManager.value = true
    }
  } catch (err: any) {
    loadError.value = err.message
  }
}

const groupedStatus = computed(() => {
  const map: Record<string, Record<number, any>> = {}
  pairsList.forEach(p => {
    map[p] = {}
  })
  dataStatus.value.forEach(item => {
    const p = item.pair.toLowerCase()
    if (map[p]) {
      map[p][item.year] = item
    }
  })
  return map
})

async function triggerDownload(sync = false) {
  if (actionLoading.value) return
  actionLoading.value = true
  try {
    dataTimeframe.value = selectedTf.value
    let params: any
    if (sync) {
      params = { syncMode: true, tf: selectedTf.value }
    } else {
      if (downloadMode.value === 'all') {
        params = {
          pair: selectedPair.value,
          startYear: 2006,
          endYear: new Date().getFullYear(),
          tf: selectedTf.value
        }
      } else if (downloadMode.value === 'range') {
        params = {
          pair: selectedPair.value,
          startYear: selectedStartYear.value,
          endYear: selectedEndYear.value,
          tf: selectedTf.value
        }
      } else {
        params = {
          pair: selectedPair.value,
          year: selectedYear.value,
          tf: selectedTf.value
        }
      }
    }
    await downloadHistoricalData(params)
    refreshDataStatus()
  } catch (err: any) {
    loadError.value = err.message
  } finally {
    actionLoading.value = false
  }
}

async function triggerDelete(pair: string, year: number) {
  if (!confirm(`Are you sure you want to delete historical data for ${pair.toUpperCase()} ${year} (${dataTimeframe.value.toUpperCase()})?`)) {
    return
  }
  actionLoading.value = true
  try {
    await deleteHistoricalData({ pair, year, tf: dataTimeframe.value })
    refreshDataStatus()
  } catch (err: any) {
    loadError.value = err.message
  } finally {
    actionLoading.value = false
  }
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

let statusPollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  refreshDataStatus()
  statusPollTimer = setInterval(refreshDataStatus, 5000)
})

onUnmounted(() => {
  pollTimers.value.forEach(timer => clearInterval(timer))
  if (statusPollTimer) clearInterval(statusPollTimer)
})
</script>

<template>
  <div class="view">
    <div class="dashboard-header" style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 1.5rem;">
      <div>
        <h1 style="margin-bottom: 0.25rem;">Dashboard</h1>
        <p class="subtitle" style="margin-bottom: 0;">Run backtests and view results</p>
      </div>
      <button class="setup-broker-btn" @click="router.push('/live-trading?setupBroker=true')">
        <Key class="btn-icon" />
        <span>Setup Broker</span>
      </button>
    </div>

    <BacktestForm
      @runs-start="onRunsStart"
      @error="onFormError"
      :preselected-strategy="preselectedStrategy"
      :preselected-symbol="preselectedSymbol"
    />

    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Active Runs Grid -->
    <div v-if="activeRuns.length > 0" class="runs-container">
      <h3>Active Runs</h3>
      <div class="runs-grid">
        <div
          v-for="r in activeRuns"
          :key="r.runId"
          :class="['run-card', { selected: r.runId === selectedRunId }]"
          @click="handleRunCardClick(r)"
        >
          <div class="run-card-header">
            <span class="run-card-symbol">{{ r.symbol }}</span>
            <span :class="['run-card-status', r.status.toLowerCase()]">{{ r.status }}</span>
          </div>
          <div class="run-card-body">
            <span v-if="r.status === 'RUNNING'" class="spinner small"></span>
            <span v-else-if="r.status === 'FAILED'" class="error-msg">Failed</span>
            <span v-else-if="r.result && r.result.result" class="return-pnl" :class="r.result.result.totalReturnPct >= 0 ? 'profit' : 'loss'">
              {{ r.result.result.totalReturnPct >= 0 ? '+' : '' }}{{ r.result.result.totalReturnPct.toFixed(2) }}%
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Detailed Run Results -->
    <template v-if="selectedRun">
      <div v-if="selectedRun.status === 'FAILED'" class="banner error">
        {{ getRunModeLabel(selectedRun) }} failed for {{ selectedRun.symbol }}: {{ selectedRun.error || 'Unknown error' }}
      </div>

      <template v-if="selectedRun.result && selectedRun.result.result">
        <div class="result-header">
          <h2>Results for {{ selectedRun.symbol }}</h2>
          <div class="result-meta">
            <span>{{ selectedRun.result.strategyId }}</span>
            <span v-if="selectedRun.result.result.periodStart && selectedRun.result.result.periodEnd" class="period-text">
              ({{ formatDate(selectedRun.result.result.periodStart) }} – {{ formatDate(selectedRun.result.result.periodEnd) }})
            </span>
            <span :class="['status-badge', selectedRun.status === 'COMPLETED' ? 'ok' : 'fail']">
              {{ selectedRun.status }}
            </span>
          </div>
        </div>

        <KpiStrip
          :sharpe="selectedRun.result.result.sharpeRatio ?? null"
          :profit-factor="selectedRun.result.result.profitFactor ?? null"
          :max-dd="selectedRun.result.result.maxDrawdownPct ?? null"
          :total-trades="selectedRun.result.result.totalTrades ?? null"
          :win-rate="selectedRun.result.result.winRatePct ?? null"
          :total-return="selectedRun.result.result.totalReturnPct ?? null"
          :final-equity="selectedRun.result.result.finalEquity ?? null"
        />

        <div class="results-actions" style="margin-top: 1rem; margin-bottom: 0.5rem; display: flex; justify-content: flex-end;">
          <button class="details-btn" @click="viewFullResults">View Full Results →</button>
        </div>

        <div class="chart-section">
          <h3>Equity Curve</h3>
          <EquityChart :data="selectedRun.equityCurve" :height="250" />
        </div>
      </template>
    </template>
    <!-- Historical Data Manager Accordion -->
    <div class="data-manager-card">
      <div class="data-manager-header" @click="showDataManager = !showDataManager">
        <div class="header-title">
          <span class="header-icon">📂</span>
          <h3>Historical Data Manager</h3>
          <span v-if="activeDownloads.length > 0" class="badge running-badge">
            {{ activeDownloads.length }} active syncing
          </span>
        </div>
        <span class="toggle-arrow">{{ showDataManager ? '▲' : '▼' }}</span>
      </div>

      <div v-if="showDataManager" class="data-manager-body">
        <div v-if="loadError" class="banner error">{{ loadError }}</div>

        <!-- Active Downloads List -->
        <div v-if="activeTasks.length > 0" class="active-downloads-section">
          <h4>Active Ingestion Processes</h4>
          <div class="active-tasks-list">
            <div v-for="task in activeTasks" :key="task.key" class="task-progress-card">
              <div class="task-info">
                <span class="task-name">{{ task.key.toUpperCase() }}</span>
                <span class="task-action">{{ task.currentAction }}</span>
                <span class="task-pct">{{ task.progress }}%</span>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" :style="{ width: task.progress + '%' }"></div>
              </div>
            </div>
          </div>
        </div>

        <!-- Timeframe & Controls Header -->
        <div class="controls-row">
          <div class="tf-selector">
            <button
              :class="['tf-btn', { active: dataTimeframe === 'h1' }]"
              @click="dataTimeframe = 'h1'; refreshDataStatus()"
            >
              H1 Data
            </button>
            <button
              :class="['tf-btn', { active: dataTimeframe === 'm1' }]"
              @click="dataTimeframe = 'm1'; refreshDataStatus()"
            >
              M1 Data
            </button>
          </div>

          <div class="action-buttons">
            <button class="btn secondary" @click="refreshDataStatus">🔄 Refresh</button>
            <button
              class="btn primary"
              :disabled="actionLoading"
              @click="triggerDownload(true)"
            >
              ⚡ Sync Current Year
            </button>
          </div>
        </div>

        <!-- Matrix Status View -->
        <div class="matrix-container">
          <table class="matrix-table">
            <thead>
              <tr>
                <th>Pair</th>
                <th v-for="year in yearsList" :key="year" class="year-header">
                  {{ year.toString().slice(2) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pair in pairsList" :key="pair">
                <td class="pair-name">{{ pair.toUpperCase().replace('_', '') }}</td>
                <td
                  v-for="year in yearsList"
                  :key="year"
                  class="matrix-cell"
                >
                  <div
                    v-if="groupedStatus[pair] && groupedStatus[pair][year]"
                    :class="[
                      'status-indicator',
                      {
                        complete: groupedStatus[pair][year].csvExists && groupedStatus[pair][year].barsExists,
                        partial: groupedStatus[pair][year].csvExists !== groupedStatus[pair][year].barsExists,
                        missing: !groupedStatus[pair][year].csvExists && !groupedStatus[pair][year].barsExists,
                        syncing: activeDownloads.includes(pair + '-' + year + '-' + dataTimeframe)
                      }
                    ]"
                    :title="`${pair.toUpperCase()} ${year} (${dataTimeframe.toUpperCase()})\nCSV: ${groupedStatus[pair][year].csvExists ? formatBytes(groupedStatus[pair][year].csvSize) : 'None'}\nBARS: ${groupedStatus[pair][year].barsExists ? formatBytes(groupedStatus[pair][year].barsSize) : 'None'}\nClick to Delete`"
                    @click="groupedStatus[pair][year].csvExists || groupedStatus[pair][year].barsExists ? triggerDelete(pair, year) : null"
                  ></div>
                  <div v-else class="status-indicator missing"></div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Download Specific Dataset Form -->
        <div class="download-form-section">
          <h4>Download Dataset</h4>
          <div class="download-fields">
            <div class="field">
              <label>Currency Pair</label>
              <select v-model="selectedPair">
                <option v-for="p in pairsList" :key="p" :value="p">
                  {{ p.toUpperCase() }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>Mode</label>
              <select v-model="downloadMode">
                <option value="single">Single Year</option>
                <option value="range">Year Range</option>
                <option value="all">All History</option>
              </select>
            </div>
            <div class="field" v-if="downloadMode === 'single'">
              <label>Year</label>
              <select v-model="selectedYear">
                <option v-for="y in yearsList" :key="y" :value="y">
                  {{ y }}
                </option>
              </select>
            </div>
            <div class="field" v-if="downloadMode === 'range'">
              <label>Start Year</label>
              <select v-model="selectedStartYear">
                <option v-for="y in yearsList" :key="y" :value="y">
                  {{ y }}
                </option>
              </select>
            </div>
            <div class="field" v-if="downloadMode === 'range'">
              <label>End Year</label>
              <select v-model="selectedEndYear">
                <option v-for="y in yearsList" :key="y" :value="y">
                  {{ y }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>Granularity</label>
              <select v-model="selectedTf">
                <option value="h1">H1</option>
                <option value="m1">M1</option>
              </select>
            </div>
            <button
              class="btn primary download-btn"
              :disabled="actionLoading"
              @click="triggerDownload(false)"
            >
              📥 Start Ingestion
            </button>
          </div>
        </div>

      </div>
    </div>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }

h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
h2 { font-size: 1.15rem; margin-bottom: 0.25rem; }
h3 { font-size: 0.9rem; margin-bottom: 0.5rem; color: var(--text-secondary); }

.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }

.setup-broker-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--accent);
  border-radius: 6px;
  padding: 0.5rem 1rem;
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.setup-broker-btn:hover {
  background: var(--accent);
  color: #000;
}

.btn-icon {
  width: 1rem;
  height: 1rem;
}

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.banner.error { background: #2d1212; color: #fca5a5; border: 1px solid #7f1d1d; }
.banner.info { background: #0f1d2d; color: #93c5fd; border: 1px solid #1e3a5f; }

.dots span { animation: blink 1.4s infinite; }
.dots span:nth-child(2) { animation-delay: 0.2s; }
.dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 100% { opacity: 0; } 50% { opacity: 1; } }

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.result-meta {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.status-badge {
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-size: 0.7rem;
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

.details-btn {
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--accent);
  border-radius: 6px;
  padding: 0.5rem 1.25rem;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s;
}

.details-btn:hover {
  background: var(--accent);
  color: #000;
}

/* Active Runs Grid CSS */
.runs-container {
  margin-bottom: 1.5rem;
}

.runs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 0.75rem;
  margin-top: 0.5rem;
}

.run-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.75rem;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.run-card:hover {
  border-color: var(--accent);
  background: var(--bg-secondary);
}

.run-card.selected {
  border-color: var(--accent);
  background: rgba(217, 119, 6, 0.05);
  box-shadow: 0 0 0 2px rgba(217, 119, 6, 0.2);
}

.run-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.run-card-symbol {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
}

.run-card-status {
  font-size: 0.65rem;
  text-transform: uppercase;
  font-weight: 700;
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
}

.run-card-status.running {
  background: rgba(245, 158, 11, 0.15);
  color: #fbbf24;
}

.run-card-status.completed {
  background: rgba(34, 197, 94, 0.15);
  color: #4ade80;
}

.run-card-status.failed {
  background: rgba(239, 68, 68, 0.15);
  color: #fca5a5;
}

.run-card-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 20px;
}

.return-pnl {
  font-size: 1rem;
  font-weight: 600;
  font-family: 'JetBrains Mono', monospace;
}

.return-pnl.profit {
  color: var(--success);
}

.return-pnl.loss {
  color: var(--danger);
}

.error-msg {
  font-size: 0.75rem;
  color: var(--danger);
}

.spinner.small {
  width: 0.85rem;
  height: 0.85rem;
  border: 1.5px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

/* Historical Data Manager Accordion Styles */
.data-manager-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  margin-top: 2rem;
  overflow: hidden;
  transition: border-color 0.2s;
}

.data-manager-card:hover {
  border-color: #333;
}

.data-manager-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem;
  cursor: pointer;
  background: var(--bg-secondary);
  user-select: none;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.header-title h3 {
  font-size: 1rem;
  color: var(--text-primary);
  margin: 0;
}

.badge {
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
}

.running-badge {
  background: rgba(245, 158, 11, 0.15);
  color: #fbbf24;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.toggle-arrow {
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.data-manager-body {
  padding: 1.25rem;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.controls-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
}

.tf-selector {
  display: flex;
  background: var(--bg-primary);
  padding: 0.25rem;
  border-radius: 6px;
  border: 1px solid var(--border);
}

.tf-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  padding: 0.4rem 1rem;
  border-radius: 4px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.tf-btn.active {
  background: var(--bg-card);
  color: var(--text-primary);
}

.action-buttons {
  display: flex;
  gap: 0.75rem;
}

.btn {
  padding: 0.5rem 1rem;
  font-size: 0.8rem;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn.primary {
  background: var(--accent);
  border: 1px solid var(--accent);
  color: #000;
}

.btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.btn.secondary {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-primary);
}

.btn.secondary:hover {
  background: var(--bg-secondary);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Matrix Table */
.matrix-container {
  overflow-x: auto;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8rem;
  text-align: center;
}

.matrix-table th,
.matrix-table td {
  padding: 0.6rem 0.4rem;
  border: 1px solid var(--border);
}

.matrix-table th {
  background: var(--bg-card);
  color: var(--text-secondary);
  font-weight: 600;
}

.year-header {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.75rem;
}

.pair-name {
  font-weight: 700;
  color: var(--text-primary);
  text-align: left;
  padding-left: 0.75rem;
  width: 90px;
}

.matrix-cell {
  width: 38px;
  vertical-align: middle;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin: 0 auto;
  transition: all 0.15s;
}

.status-indicator.complete {
  background: var(--success);
  box-shadow: 0 0 4px var(--success);
  cursor: pointer;
}

.status-indicator.complete:hover {
  transform: scale(1.3);
  background: var(--danger);
  box-shadow: 0 0 6px var(--danger);
}

.status-indicator.partial {
  background: var(--warning);
  box-shadow: 0 0 4px var(--warning);
  cursor: pointer;
}

.status-indicator.partial:hover {
  transform: scale(1.3);
  background: var(--danger);
  box-shadow: 0 0 6px var(--danger);
}

.status-indicator.missing {
  background: #262626;
}

.status-indicator.syncing {
  background: #3b82f6;
  animation: pulse 1s infinite;
  box-shadow: 0 0 6px #3b82f6;
}

/* Download Form Section */
.download-form-section {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
}

.download-form-section h4 {
  font-size: 0.85rem;
  margin-bottom: 0.75rem;
  color: var(--text-secondary);
}

.download-fields {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.field label {
  font-size: 0.7rem;
  color: var(--text-secondary);
  text-transform: uppercase;
  font-weight: 600;
}

.field select {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 6px;
  padding: 0.45rem 1rem;
  font-size: 0.8rem;
  outline: none;
}

.download-btn {
  margin-left: auto;
}

/* Active Downloads List */
.active-downloads-section {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
}

.active-downloads-section h4 {
  font-size: 0.85rem;
  margin-bottom: 0.75rem;
  color: var(--text-secondary);
}

.active-tasks-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.task-progress-card {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.task-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.8rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.task-name {
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  color: var(--accent);
}

.task-action {
  color: var(--text-secondary);
}

.task-pct {
  font-weight: 600;
  font-family: 'JetBrains Mono', monospace;
}

.progress-bar-bg {
  width: 100%;
  height: 6px;
  background: #262626;
  border-radius: 3px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
  border-radius: 3px;
  transition: width 0.3s ease;
}
</style>
