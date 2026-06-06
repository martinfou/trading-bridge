<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import type { RunResult } from '@/types/control-plane'
import BacktestForm from '@/components/BacktestForm.vue'
import KpiStrip from '@/components/KpiStrip.vue'
import EquityChart from '@/components/EquityChart.vue'

const router = useRouter()
const route = useRoute()
const { getRun, getEquityCurve } = useControlPlane()

interface ActiveRun {
  runId: string
  symbol: string
  status: string
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
    router.push(`/results/${selectedRunId.value}`)
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

onUnmounted(() => {
  pollTimers.value.forEach(timer => clearInterval(timer))
})
</script>

<template>
  <div class="view">
    <h1>Dashboard</h1>
    <p class="subtitle">Run backtests and view results</p>

    <BacktestForm
      @runs-start="onRunsStart"
      @error="onFormError"
      :preselected-strategy="preselectedStrategy"
      :preselected-symbol="preselectedSymbol"
    />

    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Active Runs Grid -->
    <div v-if="activeRuns.length > 0" class="runs-container">
      <h3>Active Backtests</h3>
      <div class="runs-grid">
        <div
          v-for="r in activeRuns"
          :key="r.runId"
          :class="['run-card', { selected: r.runId === selectedRunId }]"
          @click="selectedRunId = r.runId"
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
        Backtest failed for {{ selectedRun.symbol }}: {{ selectedRun.error || 'Unknown error' }}
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

        <div class="chart-section">
          <h3>Equity Curve</h3>
          <EquityChart :data="selectedRun.equityCurve" :height="250" />
        </div>

        <button class="details-btn" @click="viewFullResults">View Full Results →</button>
      </template>
    </template>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }

h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
h2 { font-size: 1.15rem; margin-bottom: 0.25rem; }
h3 { font-size: 0.9rem; margin-bottom: 0.5rem; color: var(--text-secondary); }

.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }

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
</style>
