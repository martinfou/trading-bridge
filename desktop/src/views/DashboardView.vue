<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { useRunWebSocket } from '@/composables/useRunWebSocket'
import type { RunResult } from '@/types/control-plane'
import BacktestForm from '@/components/BacktestForm.vue'
import KpiStrip from '@/components/KpiStrip.vue'
import EquityChart from '@/components/EquityChart.vue'

const router = useRouter()
const route = useRoute()
const { getRun, getEquityCurve } = useControlPlane()
const ws = useRunWebSocket()

const result = ref<RunResult | null>(null)
const equityCurve = ref<number[]>([])
const viewError = ref<string | null>(null)
const polling = ref(false)
const preselectedStrategy = ref<string | undefined>(
  typeof route.query.strategyId === 'string' ? route.query.strategyId : undefined,
)
const preselectedSymbol = ref<string | undefined>(
  typeof route.query.symbol === 'string' ? route.query.symbol : undefined,
)
let pollTimer: ReturnType<typeof setInterval> | null = null

async function onRunStart(runId: string) {
  viewError.value = null
  result.value = null
  equityCurve.value = []

  ws.connect(runId)
  pollTimer = setInterval(async () => {
    try {
      const r = await getRun(runId)
      if (r.status === 'COMPLETED' || r.status === 'FAILED') {
        result.value = r
        polling.value = false
        if (pollTimer) clearInterval(pollTimer)
        if (r.status === 'COMPLETED') {
          const curve = await getEquityCurve(runId)
          equityCurve.value = curve
        }
      }
    } catch {
      // keep polling
    }
  }, 1500)
  polling.value = true
}

function onFormError(msg: string) {
  viewError.value = msg
}

function viewFullResults() {
  if (result.value) {
    router.push(`/results/${result.value.runId}`)
  }
}
</script>

<template>
  <div class="view">
    <h1>Dashboard</h1>
    <p class="subtitle">Run backtests and view results</p>

    <BacktestForm
      @run-start="onRunStart"
      @error="onFormError"
      :preselected-strategy="preselectedStrategy"
      :preselected-symbol="preselectedSymbol"
    />

    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <div v-if="polling && !result" class="banner info">
      Running backtest<span class="dots"><span>.</span><span>.</span><span>.</span></span>
    </div>

    <template v-if="result && result.result">
      <div class="result-header">
        <h2>Results</h2>
        <div class="result-meta">
          <span>{{ result.strategyId }}</span>
          <span>{{ result.symbol }}</span>
          <span :class="['status-badge', result.status === 'COMPLETED' ? 'ok' : 'fail']">
            {{ result.status }}
          </span>
        </div>
      </div>

      <KpiStrip
        :sharpe="result.result.sharpeRatio ?? null"
        :profit-factor="result.result.profitFactor ?? null"
        :max-dd="result.result.maxDrawdownPct ?? null"
        :total-trades="result.result.totalTrades ?? null"
        :win-rate="result.result.winRatePct ?? null"
        :total-return="result.result.totalReturnPct ?? null"
        :final-equity="result.result.finalEquity ?? null"
      />

      <div class="chart-section">
        <h3>Equity Curve</h3>
        <EquityChart :data="equityCurve" :height="250" />
      </div>

      <button class="details-btn" @click="viewFullResults">View Full Results →</button>
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
</style>
