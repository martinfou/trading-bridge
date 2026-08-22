<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import WfaTimeline from '@/components/WfaTimeline.vue'
import WfaParameterStability from '@/components/WfaParameterStability.vue'
import {
  Play,
  RotateCw,
  TrendingUp,
  CheckCircle,
  AlertTriangle,
  History,
  Sliders,
  Sparkles,
  Zap,
  Clock,
  Layers,
  ChevronRight
} from '@lucide/vue'

const { controlPlaneUrl } = useControlPlaneConfig()

// ── State ─────────────────────────────────────────────────────────────
const selectedStrategy = ref('LtCrossMomentum')
const selectedAssetClass = ref<'FUTURES' | 'EQUITY' | 'FOREX'>('FUTURES')
const selectedSymbol = ref('MES')
const selectedTimeframe = ref('H1')
const inSampleDays = ref(180)
const outOfSampleDays = ref(60)
const isAnchored = ref(false)
const initialCapital = ref(10000)

interface ParamRange {
  name: string
  min: number
  max: number
  step: number
}

const paramRanges = ref<ParamRange[]>([
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
const showHistory = ref(false)
const runStartTime = ref<number | null>(null)
const elapsedSeconds = ref(0)

let pollInterval: any = null
let timerInterval: any = null

// ── Multi-Asset Symbols ───────────────────────────────────────────────
const availableStrategies = [
  { id: 'LtCrossMomentum', name: 'LT Cross Momentum', description: 'Dual Moving Average Trend Following' },
  { id: 'LtBollingerSqueeze', name: 'LT Bollinger Squeeze', description: 'Volatility Breakout & Mean Reversion' },
  { id: 'LtEfficiencyRatio', name: 'LT Efficiency Ratio', description: 'Kaufman Adaptive Moving Average' },
  { id: 'LtPullbackEntry', name: 'LT Pullback Entry', description: 'Trend Pullback & Continuation' },
  { id: 'LtRSI3Momentum', name: 'LT RSI-3 Momentum', description: 'Short-Cycle RSI Momentum' },
  { id: 'LtRangeBreakout', name: 'LT Range Breakout', description: 'Multi-Day Range Expansion' },
  { id: 'LtSqueezeMomentum', name: 'LT Squeeze Momentum', description: 'TTM Squeeze Volatility Expansion' }
]

const symbolsByAssetClass = {
  FUTURES: [
    { symbol: 'MES', name: 'Micro E-mini S&P 500 ($5/pt)', badge: 'Futures' },
    { symbol: 'M2K', name: 'Micro Russell 2000 Small Cap ($5/pt)', badge: 'Futures' },
    { symbol: 'EMD', name: 'E-mini S&P MidCap 400 ($100/pt)', badge: 'Futures' },
    { symbol: 'MNQ', name: 'Micro E-mini Nasdaq 100 ($2/pt)', badge: 'Futures' }
  ],
  EQUITY: [
    { symbol: 'IWM', name: 'iShares Russell 2000 ETF (Small Cap)', badge: 'Equities' },
    { symbol: 'MDY', name: 'SPDR S&P MidCap 400 ETF (Mid Cap)', badge: 'Equities' },
    { symbol: 'AAPL', name: 'Apple Inc. (Large Cap)', badge: 'Equities' },
    { symbol: 'SPY', name: 'SPDR S&P 500 ETF Trust', badge: 'Equities' },
    { symbol: 'QQQ', name: 'Invesco QQQ Trust (Nasdaq 100)', badge: 'Equities' }
  ],
  FOREX: [
    { symbol: 'EUR_USD', name: 'Euro / US Dollar', badge: 'Forex' },
    { symbol: 'GBP_USD', name: 'British Pound / US Dollar', badge: 'Forex' },
    { symbol: 'USD_JPY', name: 'US Dollar / Japanese Yen', badge: 'Forex' },
    { symbol: 'AUD_USD', name: 'Australian Dollar / US Dollar', badge: 'Forex' }
  ]
}

const currentSymbols = computed(() => symbolsByAssetClass[selectedAssetClass.value] || [])

function onAssetClassSelect(asset: 'FUTURES' | 'EQUITY' | 'FOREX') {
  selectedAssetClass.value = asset
  const list = symbolsByAssetClass[asset]
  if (list && list.length > 0) {
    selectedSymbol.value = list[0].symbol
  }
}

// ── Presets ───────────────────────────────────────────────────────────
function applyPreset(preset: 'quick' | 'standard' | 'macro' | 'anchored') {
  if (preset === 'quick') {
    inSampleDays.value = 120
    outOfSampleDays.value = 30
    isAnchored.value = false
  } else if (preset === 'standard') {
    inSampleDays.value = 180
    outOfSampleDays.value = 60
    isAnchored.value = false
  } else if (preset === 'macro') {
    inSampleDays.value = 365
    outOfSampleDays.value = 90
    isAnchored.value = false
  } else if (preset === 'anchored') {
    inSampleDays.value = 180
    outOfSampleDays.value = 60
    isAnchored.value = true
  }
}

// ── Combinations Counter ──────────────────────────────────────────────
const totalCombinations = computed(() => {
  if (paramRanges.value.length === 0) return 0
  let count = 1
  for (const p of paramRanges.value) {
    if (p.step <= 0 || p.max < p.min) continue
    const steps = Math.floor((p.max - p.min) / p.step) + 1
    count *= Math.max(1, steps)
  }
  return count
})

function addParam() {
  paramRanges.value.push({ name: `param${paramRanges.value.length + 1}`, min: 5, max: 25, step: 5 })
}

function removeParam(index: number) {
  if (paramRanges.value.length > 1) {
    paramRanges.value.splice(index, 1)
  }
}

function resetStrategyParams() {
  if (selectedStrategy.value === 'LtCrossMomentum') {
    paramRanges.value = [
      { name: 'fastPeriod', min: 5, max: 20, step: 5 },
      { name: 'slowPeriod', min: 20, max: 60, step: 10 },
      { name: 'atrPeriod', min: 10, max: 20, step: 5 }
    ]
  } else if (selectedStrategy.value === 'LtBollingerSqueeze') {
    paramRanges.value = [
      { name: 'bbPeriod', min: 15, max: 30, step: 5 },
      { name: 'bbStdDev', min: 1.5, max: 2.5, step: 0.5 },
      { name: 'kcPeriod', min: 15, max: 30, step: 5 }
    ]
  } else {
    paramRanges.value = [
      { name: 'period1', min: 10, max: 30, step: 5 },
      { name: 'period2', min: 30, max: 70, step: 10 }
    ]
  }
}

// ── API & Polling ─────────────────────────────────────────────────────
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
  progressStatus.value = 'Initializing Walk-Forward Analysis Fold Calculation...'
  runStartTime.value = Date.now()
  elapsedSeconds.value = 0

  if (timerInterval) clearInterval(timerInterval)
  timerInterval = setInterval(() => {
    if (runStartTime.value) {
      elapsedSeconds.value = Math.floor((Date.now() - runStartTime.value) / 1000)
    }
  }, 1000)

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
    if (timerInterval) clearInterval(timerInterval)
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
          if (timerInterval) clearInterval(timerInterval)
          isRunning.value = false
          progressStatus.value = 'Completed'
          loadReport(wfaId)
          fetchPreviousRuns()
        } else if (prog.status === 'FAILED') {
          clearInterval(pollInterval)
          if (timerInterval) clearInterval(timerInterval)
          isRunning.value = false
          errorMessage.value = prog.errorMessage || 'WFA task execution failed'
          fetchPreviousRuns()
        } else {
          progressStatus.value = `Computing Fold Optimization... (${elapsedSeconds.value}s)`
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
      currentWfaId.value = wfaId
    }
  } catch (err) {
    console.error('Failed to load WFA report', err)
  }
}

function selectPreviousRun(run: any) {
  loadReport(run.wfaId)
  showHistory.value = false
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}m ${s.toString().padStart(2, '0')}s`
}

onMounted(() => {
  fetchPreviousRuns()
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<template>
  <div class="wfa-container">
    <!-- Top Sub-Header -->
    <header class="wfa-header">
      <div class="header-left">
        <div class="header-badge">
          <TrendingUp class="icon-sm text-accent" />
          <span>Walk-Forward Optimization</span>
        </div>
        <h2 class="header-title">Walk-Forward Analysis (WFA) Hub</h2>
        <p class="header-subtitle">
          Multi-Asset Overfitting Prevention, Walk-Forward Efficiency (WFE), and Parameter Stability Validation.
        </p>
      </div>

      <div class="header-actions">
        <button
          class="btn-secondary"
          @click="showHistory = !showHistory"
          :class="{ active: showHistory }"
        >
          <History class="icon-xs" />
          <span>Past Runs ({{ previousRuns.length }})</span>
        </button>
      </div>
    </header>

    <!-- Main Split-Screen Layout -->
    <div class="wfa-workspace">
      <!-- LEFT SIDEBAR: Configuration Panel -->
      <aside class="wfa-sidebar">
        <!-- Preset Selector -->
        <div class="sidebar-section">
          <span class="section-label">
            <Sparkles class="icon-xs text-amber" />
            Quick Presets
          </span>
          <div class="preset-pills">
            <button class="preset-pill" @click="applyPreset('quick')">Quick (6 Folds)</button>
            <button class="preset-pill active" @click="applyPreset('standard')">Standard (10 Folds)</button>
            <button class="preset-pill" @click="applyPreset('macro')">2-Yr Macro</button>
            <button class="preset-pill" @click="applyPreset('anchored')">Anchored</button>
          </div>
        </div>

        <!-- Asset Class Selector Tabs -->
        <div class="sidebar-section">
          <span class="section-label">Asset Class</span>
          <div class="asset-tabs">
            <button
              class="asset-tab futures"
              :class="{ active: selectedAssetClass === 'FUTURES' }"
              @click="onAssetClassSelect('FUTURES')"
            >
              CME Futures
            </button>
            <button
              class="asset-tab equity"
              :class="{ active: selectedAssetClass === 'EQUITY' }"
              @click="onAssetClassSelect('EQUITY')"
            >
              US Equities
            </button>
            <button
              class="asset-tab forex"
              :class="{ active: selectedAssetClass === 'FOREX' }"
              @click="onAssetClassSelect('FOREX')"
            >
              Forex
            </button>
          </div>
        </div>

        <!-- Target Instrument & Strategy -->
        <div class="sidebar-section form-grid">
          <div class="form-group">
            <label>Instrument</label>
            <select v-model="selectedSymbol" class="custom-select">
              <option
                v-for="sym in currentSymbols"
                :key="sym.symbol"
                :value="sym.symbol"
              >
                {{ sym.symbol }} — {{ sym.name }}
              </option>
            </select>
          </div>

          <div class="form-group">
            <label>Strategy</label>
            <select v-model="selectedStrategy" @change="resetStrategyParams" class="custom-select">
              <option
                v-for="strat in availableStrategies"
                :key="strat.id"
                :value="strat.id"
              >
                {{ strat.name }}
              </option>
            </select>
          </div>

          <div class="form-row-2">
            <div class="form-group">
              <label>Timeframe</label>
              <select v-model="selectedTimeframe" class="custom-select">
                <option value="H1">H1 (1-Hour)</option>
                <option value="Daily">D1 (Daily)</option>
                <option value="M15">M15 (15-Min)</option>
              </select>
            </div>
            <div class="form-group">
              <label>Initial Capital</label>
              <input
                v-model.number="initialCapital"
                type="number"
                step="5000"
                class="custom-input"
              />
            </div>
          </div>
        </div>

        <!-- Window Protocol -->
        <div class="sidebar-section">
          <span class="section-label">
            <Layers class="icon-xs" />
            Window Protocol
          </span>
          <div class="form-row-2">
            <div class="form-group">
              <label>In-Sample Days (IS)</label>
              <input
                v-model.number="inSampleDays"
                type="number"
                min="30"
                max="720"
                step="30"
                class="custom-input"
              />
            </div>
            <div class="form-group">
              <label>Out-of-Sample Days (OOS)</label>
              <input
                v-model.number="outOfSampleDays"
                type="number"
                min="15"
                max="180"
                step="15"
                class="custom-input"
              />
            </div>
          </div>

          <label class="checkbox-row mt-2">
            <input type="checkbox" v-model="isAnchored" class="custom-checkbox" />
            <span>Anchored Expanding Window (vs Sliding)</span>
          </label>
        </div>

        <!-- Parameter Grid Editor -->
        <div class="sidebar-section">
          <div class="section-header-row">
            <span class="section-label">
              <Sliders class="icon-xs" />
              Parameter Search Grid
            </span>
            <span class="combos-badge">{{ totalCombinations }} combos</span>
          </div>

          <div class="param-grid-list">
            <div
              v-for="(param, idx) in paramRanges"
              :key="idx"
              class="param-row-card"
            >
              <div class="param-header">
                <input
                  v-model="param.name"
                  type="text"
                  class="param-name-input"
                  placeholder="Param name"
                />
                <button
                  v-if="paramRanges.length > 1"
                  @click="removeParam(idx)"
                  class="btn-icon-del"
                  title="Remove parameter"
                >
                  ×
                </button>
              </div>

              <div class="param-controls">
                <div class="param-val-box">
                  <label>Min</label>
                  <input v-model.number="param.min" type="number" class="mini-input" />
                </div>
                <div class="param-val-box">
                  <label>Max</label>
                  <input v-model.number="param.max" type="number" class="mini-input" />
                </div>
                <div class="param-val-box">
                  <label>Step</label>
                  <input v-model.number="param.step" type="number" class="mini-input" />
                </div>
              </div>
            </div>
          </div>

          <div class="param-actions">
            <button class="btn-text" @click="addParam">+ Add Parameter</button>
            <button class="btn-text text-muted" @click="resetStrategyParams">Reset Defaults</button>
          </div>
        </div>

        <!-- Launch Button -->
        <div class="sidebar-footer-action">
          <button
            class="btn-launch"
            :disabled="isRunning"
            @click="startWfa"
          >
            <RotateCw v-if="isRunning" class="icon-sm spin" />
            <Play v-else class="icon-sm" />
            <span>{{ isRunning ? 'Optimizing Folds...' : 'Launch Walk-Forward Analysis' }}</span>
          </button>
        </div>
      </aside>

      <!-- RIGHT MAIN AREA: Report, Folds, Timeline & KPIs -->
      <main class="wfa-content">
        <!-- Error Banner -->
        <div v-if="errorMessage" class="banner error">
          <AlertTriangle class="icon-sm" />
          <span>{{ errorMessage }}</span>
        </div>

        <!-- Active Running State -->
        <div v-if="isRunning" class="running-card">
          <div class="running-header">
            <div class="spinner-pulse"></div>
            <div>
              <h3 class="running-title">Walk-Forward Analysis in Progress</h3>
              <p class="running-desc">{{ progressStatus }}</p>
            </div>
          </div>
          <div class="progress-bar-track">
            <div class="progress-bar-fill animated"></div>
          </div>
          <div class="running-stats">
            <span>Elapsed: <strong>{{ formatDuration(elapsedSeconds) }}</strong></span>
            <span>Target: <strong>{{ selectedSymbol }} ({{ selectedTimeframe }})</strong></span>
            <span>Grid: <strong>{{ totalCombinations }} iterations per fold</strong></span>
          </div>
        </div>

        <!-- Report Loaded State -->
        <div v-else-if="activeReport" class="report-container">
          <!-- Top Verdict Banner -->
          <div
            class="verdict-banner"
            :class="activeReport.robustnessVerdict === 'ROBUST' || activeReport.walkForwardEfficiency >= 0.6 ? 'robust' : 'warning'"
          >
            <div class="verdict-left">
              <CheckCircle
                v-if="activeReport.robustnessVerdict === 'ROBUST' || activeReport.walkForwardEfficiency >= 0.6"
                class="icon-md"
              />
              <AlertTriangle v-else class="icon-md" />
              <div>
                <h3 class="verdict-title">
                  {{ activeReport.robustnessVerdict === 'ROBUST' || activeReport.walkForwardEfficiency >= 0.6 ? 'ROBUST STRATEGY DETECTED' : 'OVERFITTING DETECTED (CAUTION)' }}
                </h3>
                <p class="verdict-desc">
                  WFE of {{ (activeReport.walkForwardEfficiency * 100).toFixed(1) }}% indicates out-of-sample performance holds up strongly against in-sample optimization.
                </p>
              </div>
            </div>
            <div class="verdict-badge">
              <span>WFE: {{ (activeReport.walkForwardEfficiency * 100).toFixed(0) }}%</span>
            </div>
          </div>

          <!-- KPI Cards Strip -->
          <div class="kpi-grid">
            <div class="kpi-card">
              <span class="kpi-label">OOS Sharpe Ratio</span>
              <span class="kpi-value text-emerald">{{ activeReport.overallOosSharpe.toFixed(2) }}</span>
              <span class="kpi-sub">Out-of-sample aggregate</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">Walk-Forward Efficiency</span>
              <span
                class="kpi-value"
                :class="activeReport.walkForwardEfficiency >= 0.6 ? 'text-emerald' : 'text-amber'"
              >
                {{ (activeReport.walkForwardEfficiency * 100).toFixed(1) }}%
              </span>
              <span class="kpi-sub">Threshold: &gt; 60%</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">Annualized Return</span>
              <span class="kpi-value text-cyan">{{ (activeReport.annualizedReturnPct ?? 0).toFixed(1) }}%</span>
              <span class="kpi-sub">Compound yearly growth</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">Max Drawdown</span>
              <span class="kpi-value text-rose">{{ (activeReport.maxDrawdownPct ?? 0).toFixed(1) }}%</span>
              <span class="kpi-sub">Worst peak-to-trough</span>
            </div>
          </div>

          <!-- Sliding Window Timeline Component -->
          <WfaTimeline :folds="activeReport.folds || []" class="mb-5" />

          <!-- Parameter Stability Heatmap Component -->
          <WfaParameterStability :folds="activeReport.folds || []" class="mb-5" />

          <!-- Folds Breakdown Table -->
          <div class="folds-table-card">
            <div class="card-header">
              <h4>Fold-by-Fold Detailed Breakdown</h4>
              <span class="text-xs text-muted">{{ (activeReport.folds || []).length }} evaluation folds</span>
            </div>

            <div class="table-scroll">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>Fold</th>
                    <th>In-Sample Period</th>
                    <th>Out-of-Sample Period</th>
                    <th class="text-right">IS Sharpe</th>
                    <th class="text-right">OOS Sharpe</th>
                    <th class="text-right">WFE</th>
                    <th>Optimal Parameters</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="f in activeReport.folds" :key="f.foldIndex">
                    <td class="font-bold">#{{ f.foldIndex + 1 }}</td>
                    <td class="text-muted font-mono">{{ f.inSampleStart.split('T')[0] }} → {{ f.inSampleEnd.split('T')[0] }}</td>
                    <td class="text-emerald font-mono">{{ f.outOfSampleStart.split('T')[0] }} → {{ f.outOfSampleEnd.split('T')[0] }}</td>
                    <td class="text-right font-mono text-cyan">{{ f.inSampleSharpe.toFixed(2) }}</td>
                    <td class="text-right font-mono text-emerald">{{ f.outOfSampleSharpe.toFixed(2) }}</td>
                    <td class="text-right font-mono" :class="f.wfe >= 0.6 ? 'text-emerald' : 'text-amber'">
                      {{ (f.wfe * 100).toFixed(0) }}%
                    </td>
                    <td>
                      <div class="param-tags">
                        <span
                          v-for="(val, k) in f.selectedParameters"
                          :key="k"
                          class="param-tag"
                        >
                          {{ k }}: <strong>{{ val }}</strong>
                        </span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- Empty Welcome State -->
        <div v-else class="welcome-card">
          <div class="welcome-icon">
            <TrendingUp class="icon-xl text-accent" />
          </div>
          <h3>Ready for Walk-Forward Optimization</h3>
          <p>
            Configure your strategy and parameter search grid on the left, then click
            <strong>Launch Walk-Forward Analysis</strong> to evaluate parameter stability and overfitting resistance across sliding multi-asset market regimes.
          </p>
          <div class="feature-bullets">
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>Multi-Asset support for CME Futures (MES, M2K), US Equities (IWM, MDY), & Forex</span>
            </div>
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>Walk-Forward Efficiency (WFE) score preventing curve-fitting</span>
            </div>
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>Parameter stability heatmaps identifying robust parameter islands</span>
            </div>
          </div>
        </div>
      </main>
    </div>

    <!-- Past Runs History Drawer Modal -->
    <div v-if="showHistory" class="modal-overlay" @click.self="showHistory = false">
      <div class="history-drawer">
        <div class="drawer-header">
          <div class="flex items-center gap-2">
            <History class="icon-sm text-accent" />
            <h3>Past Walk-Forward Analysis Runs</h3>
          </div>
          <button class="btn-close" @click="showHistory = false">×</button>
        </div>

        <div v-if="previousRuns.length === 0" class="drawer-empty">
          No previous WFA runs recorded in the event store.
        </div>

        <div v-else class="drawer-list">
          <div
            v-for="run in previousRuns"
            :key="run.wfaId"
            class="history-item"
            :class="{ active: currentWfaId === run.wfaId }"
            @click="selectPreviousRun(run)"
          >
            <div class="history-meta">
              <div class="history-title">
                <span class="strat-name">{{ run.strategyName }}</span>
                <span class="sym-badge">{{ run.symbol }}</span>
                <span class="asset-badge">{{ run.assetClass }}</span>
              </div>
              <span class="history-time">{{ new Date(run.createdAt).toLocaleString() }}</span>
            </div>
            <div class="history-metrics">
              <span>OOS Sharpe: <strong class="text-emerald">{{ (run.overallOosSharpe ?? 0).toFixed(2) }}</strong></span>
              <span>WFE: <strong :class="run.walkForwardEfficiency >= 0.6 ? 'text-emerald' : 'text-amber'">{{ ((run.walkForwardEfficiency ?? 0) * 100).toFixed(0) }}%</strong></span>
              <span class="status-tag" :class="run.status.toLowerCase()">{{ run.status }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wfa-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 70px);
  gap: 1rem;
}

/* Header */
.wfa-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.header-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: 0.25rem;
}

.header-title {
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.header-subtitle {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin-top: 0.2rem;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
}

/* Main Workspace */
.wfa-workspace {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 1.25rem;
  flex: 1;
  min-height: 0;
}

/* Sidebar */
.wfa-sidebar {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  overflow-y: auto;
}

.sidebar-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.section-label {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-secondary);
}

.section-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.combos-badge {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--info);
  background: rgba(56, 189, 248, 0.1);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  border: 1px solid rgba(56, 189, 248, 0.3);
}

.preset-pills {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4rem;
}

.preset-pill {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  padding: 0.35rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
  transition: all 0.15s;
}

.preset-pill:hover, .preset-pill.active {
  background: rgba(217, 119, 6, 0.15);
  border-color: var(--accent);
  color: var(--text-primary);
}

.asset-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0.3rem;
}

.asset-tab {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  padding: 0.4rem 0.25rem;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 500;
  cursor: pointer;
  text-align: center;
  transition: all 0.15s;
}

.asset-tab.futures.active {
  background: var(--asset-futures-bg);
  border-color: var(--asset-futures);
  color: #d8b4fe;
}

.asset-tab.equity.active {
  background: var(--asset-equity-bg);
  border-color: var(--asset-equity);
  color: #67e8f9;
}

.asset-tab.forex.active {
  background: var(--asset-forex-bg);
  border-color: var(--asset-forex);
  color: #fde047;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.form-group label {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.form-row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.custom-select, .custom-input {
  background: #0f0f0f;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.45rem 0.6rem;
  font-size: 0.8rem;
  color: var(--text-primary);
  outline: none;
  transition: border-color 0.15s;
}

.custom-select:focus, .custom-input:focus {
  border-color: var(--accent);
}

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  cursor: pointer;
}

/* Param Grid */
.param-grid-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.param-row-card {
  background: #0d0d0d;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.param-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.param-name-input {
  background: transparent;
  border: none;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-primary);
  outline: none;
  width: 80%;
}

.btn-icon-del {
  background: transparent;
  border: none;
  color: var(--danger);
  cursor: pointer;
  font-size: 1rem;
}

.param-controls {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0.3rem;
}

.param-val-box {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.param-val-box label {
  font-size: 0.65rem;
  color: var(--text-muted);
}

.mini-input {
  background: #141414;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 0.25rem 0.35rem;
  font-size: 0.75rem;
  color: var(--text-primary);
  font-family: monospace;
}

.param-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 0.25rem;
}

.btn-text {
  background: transparent;
  border: none;
  color: var(--accent);
  font-size: 0.75rem;
  cursor: pointer;
  padding: 0;
}

.btn-text.text-muted {
  color: var(--text-muted);
}

.btn-launch {
  width: 100%;
  background: var(--accent);
  border: none;
  border-radius: 6px;
  color: #fff;
  padding: 0.65rem 1rem;
  font-size: 0.85rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-launch:hover:not(:disabled) {
  background: var(--accent-hover);
}

.btn-launch:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Content Area */
.wfa-content {
  overflow-y: auto;
  padding-right: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

/* Running State */
.running-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.running-header {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.spinner-pulse {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 3px solid var(--accent);
  border-top-color: transparent;
  animation: spin 0.8s linear infinite;
}

.running-title {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.running-desc {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin-top: 0.2rem;
}

.progress-bar-track {
  width: 100%;
  height: 8px;
  background: #1c1c1c;
  border-radius: 4px;
  overflow: hidden;
}

.progress-bar-fill.animated {
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, var(--accent) 0%, #f59e0b 50%, var(--accent) 100%);
  animation: pulse-bg 1.5s ease infinite;
}

.running-stats {
  display: flex;
  gap: 1.5rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.running-stats strong {
  color: var(--text-primary);
}

/* Report View */
.report-container {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.verdict-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.25rem;
  border-radius: 8px;
}

.verdict-banner.robust {
  background: rgba(16, 185, 129, 0.15);
  border: 1px solid rgba(16, 185, 129, 0.4);
  color: #34d399;
}

.verdict-banner.warning {
  background: rgba(244, 63, 94, 0.15);
  border: 1px solid rgba(244, 63, 94, 0.4);
  color: #fb7185;
}

.verdict-left {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.verdict-title {
  font-size: 0.95rem;
  font-weight: 700;
  margin: 0;
}

.verdict-desc {
  font-size: 0.75rem;
  color: var(--text-primary);
  margin-top: 0.15rem;
}

.verdict-badge span {
  font-size: 0.85rem;
  font-weight: 700;
  background: rgba(0, 0, 0, 0.3);
  padding: 0.35rem 0.75rem;
  border-radius: 6px;
}

/* KPI Grid */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.75rem;
}

.kpi-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.kpi-label {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-muted);
}

.kpi-value {
  font-size: 1.4rem;
  font-weight: 700;
  font-family: monospace;
}

.kpi-sub {
  font-size: 0.68rem;
  color: var(--text-secondary);
}

/* Folds Table */
.folds-table-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.card-header h4 {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0;
}

.table-scroll {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.75rem;
}

.data-table th {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  font-weight: 500;
  text-align: left;
}

.data-table td {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.param-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.param-tag {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  font-size: 0.7rem;
}

/* Welcome Hero */
.welcome-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3rem 2rem;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
}

.welcome-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(217, 119, 6, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
}

.welcome-card h3 {
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.welcome-card p {
  font-size: 0.85rem;
  color: var(--text-secondary);
  max-width: 540px;
  line-height: 1.5;
}

.feature-bullets {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  text-align: left;
  margin-top: 1rem;
}

.bullet-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

/* Modal Drawer */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}

.history-drawer {
  width: 440px;
  background: #141414;
  border-left: 1px solid var(--border);
  height: 100vh;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.drawer-header h3 {
  font-size: 1rem;
  font-weight: 600;
  margin: 0;
}

.btn-close {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  font-size: 1.25rem;
  cursor: pointer;
}

.drawer-list {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.history-item {
  background: #1a1a1a;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.75rem;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  transition: all 0.15s;
}

.history-item:hover, .history-item.active {
  border-color: var(--accent);
  background: rgba(217, 119, 6, 0.08);
}

.history-meta {
  display: flex;
  justify-content: space-between;
  font-size: 0.75rem;
}

.history-title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.strat-name {
  font-weight: 600;
  color: var(--text-primary);
}

.sym-badge {
  font-size: 0.65rem;
  background: rgba(255, 255, 255, 0.05);
  padding: 0.1rem 0.3rem;
  border-radius: 3px;
}

.asset-badge {
  font-size: 0.65rem;
  background: var(--asset-futures-bg);
  color: var(--asset-futures);
  padding: 0.1rem 0.3rem;
  border-radius: 3px;
}

.history-time {
  font-size: 0.68rem;
  color: var(--text-muted);
}

.history-metrics {
  display: flex;
  justify-content: space-between;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.status-tag {
  font-size: 0.65rem;
  text-transform: uppercase;
  font-weight: 700;
}

.status-tag.completed {
  color: var(--success);
}

.btn-secondary {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-primary);
  padding: 0.45rem 0.8rem;
  font-size: 0.8rem;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-secondary:hover, .btn-secondary.active {
  border-color: var(--accent);
  color: var(--accent);
}

/* Utilities */
.icon-xl { width: 32px; height: 32px; }
.icon-md { width: 22px; height: 22px; flex-shrink: 0; }
.icon-sm { width: 16px; height: 16px; flex-shrink: 0; }
.icon-xs { width: 14px; height: 14px; flex-shrink: 0; }

.text-accent { color: var(--accent); }
.text-amber { color: #f59e0b; }
.text-emerald { color: #10b981; }
.text-cyan { color: #06b6d4; }
.text-rose { color: #f43f5e; }
.text-muted { color: var(--text-muted); }

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes pulse-bg {
  0%, 100% { opacity: 0.8; }
  50% { opacity: 1; }
}

.banner.error {
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.4);
  color: #fca5a5;
  padding: 0.75rem 1rem;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8rem;
}
</style>
