<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import WfaTimeline from '@/components/WfaTimeline.vue'
import WfaParameterStability from '@/components/WfaParameterStability.vue'
import { Play, RotateCw, CheckCircle2, AlertCircle, TrendingUp, Layers, Sliders } from '@lucide/vue'

const { controlPlaneUrl } = useControlPlaneConfig()

const selectedStrategy = ref('LtCrossMomentum')
const selectedAssetClass = ref('FUTURES')
const selectedSymbol = ref('MES')
const selectedTimeframe = ref('H1')
const inSampleDays = ref(180)
const outOfSampleDays = ref(60)
const isAnchored = ref(false)
const initialCapital = ref(10000)

const paramRanges = ref([
  { name: 'fastPeriod', min: 5, max: 20, step: 5 },
  { name: 'slowPeriod', min: 20, max: 60, step: 10 },
  { name: 'atrPeriod', min: 10, max: 20, step: 5 }
])

const isRunning = ref(false)
const currentWfaId = ref<string | null>(null)
const progressStatus = ref<string>('')
const errorMessage = ref<string | null>(null)
const activeReport = ref<any>(null)
const previousRuns = ref<any[]>([])

let pollInterval: any = null

const assetClasses = [
  { label: 'CME Micro/Mini Futures (MES, M2K, EMD, MNQ)', value: 'FUTURES' },
  { label: 'US Equities / Small-Mid Cap (IWM, MDY, AAPL)', value: 'EQUITY' },
  { label: 'Forex (EUR/USD, GBP/USD, USD/JPY)', value: 'FOREX' }
]

function onAssetClassChange() {
  if (selectedAssetClass.value === 'FUTURES') {
    selectedSymbol.value = 'MES'
  } else if (selectedAssetClass.value === 'EQUITY') {
    selectedSymbol.value = 'IWM'
  } else {
    selectedSymbol.value = 'EUR_USD'
  }
}

async function fetchPreviousRuns() {
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/runs/walk-forward?limit=20`)
    if (res.ok) {
      previousRuns.value = await res.json()
    }
  } catch (err) {
    console.error('Failed to load previous WFA runs', err)
  }
}

async function startWfa() {
  errorMessage.value = null
  activeReport.value = null
  isRunning.value = true
  progressStatus.value = 'Initializing Walk-Forward Analysis...'

  const payload = {
    strategyName: selectedStrategy.value,
    symbol: selectedSymbol.value,
    assetClass: selectedAssetClass.value,
    timeframe: selectedTimeframe.value,
    inSampleDays: inSampleDays.value,
    outOfSampleDays: outOfSampleDays.value,
    isAnchored: isAnchored.value,
    initialCapital: initialCapital.value,
    parameterRanges: paramRanges.value
  }

  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/runs/walk-forward`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })

    if (!res.ok) {
      const errData = await res.json().catch(() => ({}))
      throw new Error(errData.error || `Server returned ${res.status}`)
    }

    const data = await res.json()
    currentWfaId.value = data.wfaId
    progressStatus.value = 'Running In-Sample & Out-of-Sample Folds...'
    startPolling(data.wfaId)
  } catch (err: any) {
    isRunning.value = false
    errorMessage.value = err.message || 'Failed to start WFA run'
  }
}

function startPolling(wfaId: string) {
  if (pollInterval) clearInterval(pollInterval)
  pollInterval = setInterval(async () => {
    try {
      const res = await fetch(`${controlPlaneUrl.value}/api/runs/walk-forward/${wfaId}`)
      if (res.ok) {
        const prog = await res.json()
        if (prog.status === 'COMPLETED') {
          clearInterval(pollInterval)
          isRunning.value = false
          progressStatus.value = 'Completed'
          loadReport(wfaId)
          fetchPreviousRuns()
        } else if (prog.status === 'FAILED') {
          clearInterval(pollInterval)
          isRunning.value = false
          errorMessage.value = prog.errorMessage || 'WFA task execution failed'
          fetchPreviousRuns()
        } else {
          progressStatus.value = `Running fold calculation...`
        }
      }
    } catch (err) {
      console.error('Failed polling progress', err)
    }
  }, 1000)
}

async function loadReport(wfaId: string) {
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/runs/walk-forward/${wfaId}/report`)
    if (res.ok) {
      activeReport.value = await res.json()
    }
  } catch (err) {
    console.error('Failed to load WFA report', err)
  }
}

onMounted(() => {
  fetchPreviousRuns()
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})
</script>

<template>
  <div class="wfa-view p-6 space-y-6 max-w-7xl mx-auto text-slate-200">
    <div class="flex items-center justify-between border-b border-slate-800 pb-4">
      <div>
        <h1 class="text-2xl font-bold text-slate-100 flex items-center gap-2.5">
          <TrendingUp class="w-6 h-6 text-emerald-400" />
          Walk-Forward Analysis (WFA) Hub
        </h1>
        <p class="text-xs text-slate-400 mt-1">
          Multi-Asset Overfitting Prevention & Parameter Stability Validation (Futures MES/M2K/EMD/MNQ, Equities & Forex)
        </p>
      </div>
      <button
        @click="startWfa"
        :disabled="isRunning"
        class="flex items-center gap-2 px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium rounded-lg text-sm shadow-md transition"
      >
        <Play v-if="!isRunning" class="w-4 h-4" />
        <RotateCw v-else class="w-4 h-4 animate-spin" />
        {{ isRunning ? 'Optimizing Folds...' : 'Launch Walk-Forward Analysis' }}
      </button>
    </div>

    <!-- Configuration Card -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
      <div class="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg space-y-4">
        <h2 class="text-sm font-semibold text-slate-100 flex items-center gap-2">
          <Layers class="w-4 h-4 text-blue-400" />
          Asset & Strategy Specification
        </h2>
        <div>
          <label class="block text-xs font-medium text-slate-400 mb-1">Asset Class</label>
          <select
            v-model="selectedAssetClass"
            @change="onAssetClassChange"
            class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-slate-200 focus:border-blue-500 focus:outline-none"
          >
            <option v-for="ac in assetClasses" :key="ac.value" :value="ac.value">{{ ac.label }}</option>
          </select>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs font-medium text-slate-400 mb-1">Symbol / Instrument</label>
            <input
              v-model="selectedSymbol"
              type="text"
              class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-slate-200"
            />
          </div>
          <div>
            <label class="block text-xs font-medium text-slate-400 mb-1">Timeframe</label>
            <select
              v-model="selectedTimeframe"
              class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-slate-200"
            >
              <option value="H1">H1 (1-Hour)</option>
              <option value="D1">D1 (Daily)</option>
              <option value="M15">M15 (15-Min)</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block text-xs font-medium text-slate-400 mb-1">Strategy Name</label>
          <input
            v-model="selectedStrategy"
            type="text"
            class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-slate-200"
          />
        </div>
      </div>

      <!-- Window Configuration -->
      <div class="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg space-y-4">
        <h2 class="text-sm font-semibold text-slate-100 flex items-center gap-2">
          <Sliders class="w-4 h-4 text-purple-400" />
          Walk-Forward Window Protocol
        </h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs font-medium text-slate-400 mb-1">In-Sample Days (IS)</label>
            <input
              v-model.number="inSampleDays"
              type="number"
              class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-slate-200"
            />
          </div>
          <div>
            <label class="block text-xs font-medium text-slate-400 mb-1">Out-of-Sample Days (OOS)</label>
            <input
              v-model.number="outOfSampleDays"
              type="number"
              class="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-slate-200"
            />
          </div>
        </div>
        <div class="flex items-center gap-2 pt-2">
          <input
            id="anchored"
            v-model="isAnchored"
            type="checkbox"
            class="rounded bg-slate-950 border-slate-800 text-blue-500"
          />
          <label for="anchored" class="text-xs text-slate-300">
            Anchored Expanding Window (vs Sliding Window)
          </label>
        </div>
      </div>

      <!-- Grid Search Parameters -->
      <div class="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg space-y-4">
        <h2 class="text-sm font-semibold text-slate-100 flex items-center gap-2">
          <Sliders class="w-4 h-4 text-cyan-400" />
          Parameter Search Grid
        </h2>
        <div class="space-y-2 text-xs font-mono">
          <div
            v-for="(p, idx) in paramRanges"
            :key="idx"
            class="flex items-center justify-between bg-slate-950 p-2 rounded-lg border border-slate-800"
          >
            <span class="text-slate-300 font-bold">{{ p.name }}</span>
            <span class="text-slate-400">[{{ p.min }} .. {{ p.max }}] step {{ p.step }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Active Run Status or Error Banner -->
    <div v-if="isRunning" class="bg-blue-950/40 border border-blue-800/80 rounded-xl p-4 flex items-center gap-3">
      <RotateCw class="w-5 h-5 text-blue-400 animate-spin" />
      <div>
        <div class="text-sm font-semibold text-blue-200">{{ progressStatus }}</div>
        <div class="text-xs text-blue-400">Concurrency lock active (max 1 WFA job). Running parallel fold simulations...</div>
      </div>
    </div>

    <div v-if="errorMessage" class="bg-red-950/40 border border-red-800/80 rounded-xl p-4 flex items-center gap-3 text-red-300 text-sm">
      <AlertCircle class="w-5 h-5 text-red-400" />
      <span>{{ errorMessage }}</span>
    </div>

    <!-- Active Report Summary Scorecard -->
    <div v-if="activeReport" class="space-y-6">
      <div class="grid grid-cols-2 md:grid-cols-5 gap-4">
        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div class="text-xs text-slate-400">Walk-Forward Efficiency</div>
          <div :class="activeReport.wfe >= 0.6 ? 'text-emerald-400' : 'text-amber-400'" class="text-2xl font-bold font-mono mt-1">
            {{ (activeReport.wfe * 100).toFixed(1) }}%
          </div>
          <div class="text-[10px] text-slate-500 mt-1">Target &ge; 60%</div>
        </div>
        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div class="text-xs text-slate-400">Global OOS Sharpe</div>
          <div class="text-2xl font-bold font-mono text-cyan-400 mt-1">
            {{ activeReport.oosSharpe.toFixed(2) }}
          </div>
        </div>
        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div class="text-xs text-slate-400">OOS Max Drawdown</div>
          <div class="text-2xl font-bold font-mono text-rose-400 mt-1">
            {{ activeReport.oosMaxDrawdownPct.toFixed(1) }}%
          </div>
        </div>
        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div class="text-xs text-slate-400">OOS Profit Factor</div>
          <div class="text-2xl font-bold font-mono text-emerald-400 mt-1">
            {{ activeReport.oosProfitFactor.toFixed(2) }}
          </div>
        </div>
        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div class="text-xs text-slate-400">Total OOS Trades</div>
          <div class="text-2xl font-bold font-mono text-slate-200 mt-1">
            {{ activeReport.oosTradesCount }}
          </div>
        </div>
      </div>

      <!-- Timeline and Stability -->
      <WfaTimeline :folds="activeReport.folds" />
      <WfaParameterStability :folds="activeReport.folds" />
    </div>

    <!-- Previous Runs History Table -->
    <div v-if="previousRuns.length > 0" class="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg">
      <h3 class="text-sm font-semibold text-slate-100 mb-4">Historical Walk-Forward Analyses</h3>
      <div class="overflow-x-auto">
        <table class="w-full text-xs text-left border-collapse">
          <thead>
            <tr class="border-b border-slate-800 text-slate-400">
              <th class="py-2.5 px-3">Date</th>
              <th class="py-2.5 px-3">Strategy</th>
              <th class="py-2.5 px-3">Asset / Symbol</th>
              <th class="py-2.5 px-3 text-right">OOS Sharpe</th>
              <th class="py-2.5 px-3 text-right">WFE</th>
              <th class="py-2.5 px-3 text-center">Status</th>
              <th class="py-2.5 px-3 text-right">Action</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-800/60 font-mono">
            <tr v-for="r in previousRuns" :key="r.wfaId" class="hover:bg-slate-800/30">
              <td class="py-2.5 px-3 text-slate-400">{{ r.createdAt ? r.createdAt.substring(0, 16).replace('T', ' ') : '' }}</td>
              <td class="py-2.5 px-3 text-slate-200 font-sans">{{ r.strategyName }}</td>
              <td class="py-2.5 px-3 text-slate-300">{{ r.assetClass }} ({{ r.symbol }})</td>
              <td class="py-2.5 px-3 text-right text-cyan-400 font-bold">{{ r.oosSharpe ? r.oosSharpe.toFixed(2) : '-' }}</td>
              <td class="py-2.5 px-3 text-right" :class="r.wfe >= 0.6 ? 'text-emerald-400' : 'text-amber-400'">
                {{ r.wfe ? (r.wfe * 100).toFixed(0) + '%' : '-' }}
              </td>
              <td class="py-2.5 px-3 text-center">
                <span
                  class="px-2 py-0.5 rounded-full text-[10px]"
                  :class="r.status === 'COMPLETED' ? 'bg-emerald-950 text-emerald-400 border border-emerald-800' : 'bg-red-950 text-red-400 border border-red-800'"
                >
                  {{ r.status }}
                </span>
              </td>
              <td class="py-2.5 px-3 text-right">
                <button
                  @click="loadReport(r.wfaId)"
                  class="text-blue-400 hover:text-blue-300 font-sans font-medium"
                >
                  View Report
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
