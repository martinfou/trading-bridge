<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import { useControlPlane } from '@/composables/useControlPlane'
import { useCostPresets } from '@/composables/useCostPresets'
import type { InstrumentDefinition, Strategy } from '@/types/control-plane'
import WfaTimeline from '@/components/WfaTimeline.vue'
import WfaParameterStability from '@/components/WfaParameterStability.vue'
import StrategySelector from '@/components/StrategySelector.vue'
import {
  Play,
  RotateCw,
  TrendingUp,
  CheckCircle,
  AlertTriangle,
  History,
  Sliders,
  Sparkles,
  Layers
} from '@lucide/vue'

const { controlPlaneUrl } = useControlPlaneConfig()
const { getInstruments } = useControlPlane()
const { getPresetForSymbol } = useCostPresets()

// ── Standard Timeframes & Year Ranges ─────────────────────────────────
const TIMEFRAME_OPTIONS = [
  { value: 'M1', label: 'M1 (1-Min)' },
  { value: 'M5', label: 'M5 (5-Min)' },
  { value: 'M15', label: 'M15 (15-Min)' },
  { value: 'M30', label: 'M30 (30-Min)' },
  { value: 'H1', label: 'H1 (1-Hour)' },
  { value: 'H4', label: 'H4 (4-Hour)' },
  { value: 'D1', label: 'D1 (Daily)' },
  { value: 'W1', label: 'W1 (Weekly)' }
]

const YEAR_RANGE_OPTIONS = [
  { value: '2024-2025', label: '2024–2025 (2 Yrs)' },
  { value: '2023-2025', label: '2023–2025 (3 Yrs)' },
  { value: '2022-2025', label: '2022–2025 (4 Yrs)' },
  { value: '2020-2025', label: '2020–2025 (6 Yrs)' },
  { value: '2015-2025', label: '2015–2025 (10 Yrs)' },
  { value: '2025-2026', label: '2025–2026 (Recent)' }
]

function normalizeTimeframe(tf?: string | null): string {
  if (!tf) return 'H1'
  const clean = tf.trim().toUpperCase()
  if (clean === 'DAILY' || clean === '1D') return 'D1'
  if (clean === 'WEEKLY' || clean === '1W') return 'W1'
  if (TIMEFRAME_OPTIONS.some(o => o.value === clean)) return clean
  return 'H1'
}

// ── State ─────────────────────────────────────────────────────────────
const selectedStrategy = ref('LtCrossMomentum')
const selectedAssetClass = ref<'FUTURES' | 'EQUITY' | 'FOREX'>('FUTURES')
const selectedSymbol = ref('MES')
const selectedTimeframe = ref('H1')
const selectedYearSpec = ref('2023-2025')
const inSampleDays = ref(180)
const outOfSampleDays = ref(60)
const isAnchored = ref(false)
const initialCapital = ref(10000)
const lotSize = ref(1)
const commissionPerTrade = ref(0.62)
const slippagePct = ref(0.00005)

function applyInstrumentDefaults(sym: string) {
  const preset = getPresetForSymbol(sym)
  initialCapital.value = preset.capital
  lotSize.value = preset.lotSize
  commissionPerTrade.value = preset.commissionPerTrade
  slippagePct.value = preset.slippagePct
}

watch(selectedSymbol, (newSym) => {
  applyInstrumentDefaults(newSym)
})

interface ParamRange {
  name: string
  label?: string
  category?: string
  defaultValue?: number
  min: number
  max: number
  step: number
}

const paramRanges = ref<ParamRange[]>([
  { name: 'fastPeriod', label: 'Fast Period', category: 'SIGNAL', defaultValue: 20, min: 10, max: 30, step: 10 },
  { name: 'slowPeriod', label: 'Slow Period', category: 'SIGNAL', defaultValue: 100, min: 60, max: 140, step: 40 }
])

const discoveredCatalogParams = ref<Array<ParamRange & { activeByDefault?: boolean }>>([])
const isLoadingParams = ref(false)

const isRunning = ref(false)
const currentWfaId = ref<string | null>(null)
const progressStatus = ref<string>('')
const errorMessage = ref<string | null>(null)
const activeReport = ref<any>(null)
const previousRuns = ref<any[]>([])
const showHistory = ref(false)
const runStartTime = ref<number | null>(null)
const elapsedSeconds = ref(0)

const rawUniverse = ref<InstrumentDefinition[]>([])

let pollInterval: any = null
let timerInterval: any = null

// ── Multi-Asset Symbols ───────────────────────────────────────────────
const symbolsByAssetClass = computed(() => {
  const futures = rawUniverse.value
    .filter(i => i.assetClass.toUpperCase() === 'FUTURES')
    .map(i => ({ symbol: i.symbol, name: i.name, badge: 'Futures' }))

  const equity = rawUniverse.value
    .filter(i => i.assetClass.toUpperCase() === 'EQUITIES' || i.assetClass.toUpperCase() === 'EQUITY')
    .map(i => ({ symbol: i.symbol, name: i.name, badge: 'Equities' }))

  const forex = rawUniverse.value
    .filter(i => i.assetClass.toUpperCase() === 'FOREX' || i.assetClass.toUpperCase() === 'COMMODITIES')
    .map(i => ({ symbol: i.symbol, name: i.name, badge: 'Forex' }))

  return {
    FUTURES: futures.length > 0 ? futures : [
      { symbol: 'MGC', name: 'Micro Gold ($10/oz)', badge: 'Futures' },
      { symbol: 'GC', name: 'Gold Futures ($100/oz)', badge: 'Futures' },
      { symbol: 'MES', name: 'Micro E-mini S&P 500 ($5/pt)', badge: 'Futures' },
      { symbol: 'M2K', name: 'Micro Russell 2000 Small Cap ($5/pt)', badge: 'Futures' },
      { symbol: 'EMD', name: 'E-mini S&P MidCap 400 ($100/pt)', badge: 'Futures' },
      { symbol: 'MNQ', name: 'Micro E-mini Nasdaq 100 ($2/pt)', badge: 'Futures' }
    ],
    EQUITY: equity.length > 0 ? equity : [
      { symbol: 'IWM', name: 'iShares Russell 2000 ETF (Small Cap)', badge: 'Equities' },
      { symbol: 'MDY', name: 'SPDR S&P MidCap 400 ETF (Mid Cap)', badge: 'Equities' },
      { symbol: 'AAPL', name: 'Apple Inc. (Large Cap)', badge: 'Equities' },
      { symbol: 'SPY', name: 'SPDR S&P 500 ETF Trust', badge: 'Equities' },
      { symbol: 'QQQ', name: 'Invesco QQQ Trust (Nasdaq 100)', badge: 'Equities' }
    ],
    FOREX: forex.length > 0 ? forex : [
      { symbol: 'EUR_USD', name: 'Euro / US Dollar', badge: 'Forex' },
      { symbol: 'GBP_USD', name: 'British Pound / US Dollar', badge: 'Forex' },
      { symbol: 'USD_JPY', name: 'US Dollar / Japanese Yen', badge: 'Forex' },
      { symbol: 'AUD_USD', name: 'Australian Dollar / US Dollar', badge: 'Forex' },
      { symbol: 'XAU_USD', name: 'Spot Gold / US Dollar', badge: 'Forex' }
    ]
  }
})

const currentSymbols = computed(() => symbolsByAssetClass.value[selectedAssetClass.value] || [])

function inferAssetClassForSymbol(sym: string): 'FUTURES' | 'EQUITY' | 'FOREX' {
  const s = (sym || '').toUpperCase()
  if (symbolsByAssetClass.value.FUTURES.some(i => i.symbol === s) || ['MGC', 'GC', 'MES', 'MNQ', 'M2K', 'EMD', 'MYM', 'MCL', 'ES', 'NQ'].includes(s)) {
    return 'FUTURES'
  }
  if (symbolsByAssetClass.value.EQUITY.some(i => i.symbol === s) || ['AAPL', 'SPY', 'QQQ', 'IWM', 'MDY', 'NVDA', 'TSLA', 'PLTR', 'IJR', 'VB', 'SCHA', 'SOXL'].includes(s)) {
    return 'EQUITY'
  }
  return 'FOREX'
}

function onAssetClassSelect(asset: 'FUTURES' | 'EQUITY' | 'FOREX') {
  selectedAssetClass.value = asset
  const list = symbolsByAssetClass.value[asset]
  if (list && list.length > 0) {
    selectedSymbol.value = list[0].symbol
  }
}

async function loadUniverse() {
  try {
    rawUniverse.value = await getInstruments()
    const list = symbolsByAssetClass.value[selectedAssetClass.value]
    if (list && list.length > 0 && !list.some(s => s.symbol === selectedSymbol.value)) {
      selectedSymbol.value = list[0].symbol
    }
  } catch (err) {
    console.error('Failed to load universe in WfaView:', err)
  }
}

// ── Presets & Live Fold Preview ───────────────────────────────────────
const activePreset = computed(() => {
  if (isAnchored.value && inSampleDays.value === 180 && outOfSampleDays.value === 60) return 'anchored'
  if (!isAnchored.value && inSampleDays.value === 90 && outOfSampleDays.value === 30) return 'intraday'
  if (!isAnchored.value && inSampleDays.value === 180 && outOfSampleDays.value === 60) return 'standard'
  if (!isAnchored.value && inSampleDays.value === 365 && outOfSampleDays.value === 90) return 'macro'
  return ''
})

function applyPreset(preset: 'intraday' | 'standard' | 'macro' | 'anchored') {
  if (preset === 'intraday') {
    inSampleDays.value = 90
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

const estimatedSpanDays = computed(() => {
  const parts = selectedYearSpec.value.split('-').map(Number)
  if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
    return Math.max(365, (parts[1] - parts[0] + 1) * 365)
  }
  return 730
})

const estimatedFolds = computed(() => {
  const isD = Math.max(15, inSampleDays.value || 180)
  const oosD = Math.max(7, outOfSampleDays.value || 60)
  const remaining = estimatedSpanDays.value - isD
  if (remaining < oosD) return 1
  return Math.max(1, Math.floor(remaining / oosD))
})

// ── Combinations Counter & Parameter Introspection ────────────────────
function stepsForParam(p: ParamRange): number {
  if (p.step <= 0 || p.max < p.min) return 1
  return Math.max(1, Math.round((p.max - p.min) / p.step) + 1)
}

const totalCombinations = computed(() => {
  if (paramRanges.value.length === 0) return 0
  let count = 1
  for (const p of paramRanges.value) {
    count *= stepsForParam(p)
  }
  return count
})

const inactiveCatalogParams = computed(() => {
  const activeNames = new Set(paramRanges.value.map(p => p.name))
  return discoveredCatalogParams.value.filter(p => !activeNames.has(p.name))
})

async function fetchStrategyParameters(strategyId: string) {
  if (!strategyId) return
  isLoadingParams.value = true
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/strategies/${encodeURIComponent(strategyId)}/parameters`)
    if (res.ok) {
      const data = await res.json()
      const list = Array.isArray(data.parameters) ? data.parameters : []
      if (list.length > 0) {
        discoveredCatalogParams.value = list.map((p: any) => ({
          name: p.name,
          label: p.label || p.name,
          category: p.category || 'SIGNAL',
          defaultValue: p.defaultValue,
          min: Number(p.min),
          max: Number(p.max),
          step: Number(p.step),
          activeByDefault: Boolean(p.activeByDefault)
        }))
        const defaults = discoveredCatalogParams.value.filter(p => p.activeByDefault)
        const chosen = (defaults.length > 0 ? defaults : discoveredCatalogParams.value.slice(0, 2)).map(p => ({
          name: p.name,
          label: p.label,
          category: p.category,
          defaultValue: p.defaultValue,
          min: p.min,
          max: p.max,
          step: p.step
        }))
        paramRanges.value = chosen
        return
      }
    }
  } catch (err) {
    console.error('Failed to introspect strategy parameters:', err)
  } finally {
    isLoadingParams.value = false
  }
  // Fallback if server endpoint unreachable
  paramRanges.value = [
    { name: 'slScale', label: 'Stop-Loss Distance Scale (×)', category: 'RISK', defaultValue: 1.0, min: 0.75, max: 1.25, step: 0.25 },
    { name: 'tpScale', label: 'Take-Profit Distance Scale (×)', category: 'RISK', defaultValue: 1.0, min: 0.75, max: 1.5, step: 0.25 }
  ]
}

function addCatalogParam(p: ParamRange) {
  if (paramRanges.value.some(existing => existing.name === p.name)) return
  paramRanges.value.push({
    name: p.name,
    label: p.label,
    category: p.category,
    defaultValue: p.defaultValue,
    min: p.min,
    max: p.max,
    step: p.step
  })
}

function addCustomParam() {
  const nextInactive = inactiveCatalogParams.value[0]
  if (nextInactive) {
    addCatalogParam(nextInactive)
    return
  }
  paramRanges.value.push({
    name: 'warmupBars',
    label: 'Signal Warmup Filter (Bars)',
    category: 'FILTER',
    defaultValue: 20,
    min: 10,
    max: 40,
    step: 15
  })
}

function removeParam(index: number) {
  if (paramRanges.value.length > 1) {
    paramRanges.value.splice(index, 1)
  }
}

function resetStrategyParams() {
  fetchStrategyParameters(selectedStrategy.value)
}

function onWfaStrategySelected(strat: Strategy) {
  selectedStrategy.value = strat.id
  errorMessage.value = null

  const candidateSym = strat.defaultSymbol
    || (strat.recommendedSymbols && strat.recommendedSymbols.length > 0 ? strat.recommendedSymbols[0] : null)
  if (candidateSym) {
    const inferredClass = inferAssetClassForSymbol(candidateSym)
    selectedAssetClass.value = inferredClass
    selectedSymbol.value = candidateSym
    applyInstrumentDefaults(candidateSym)
  }

  if (strat.timeframeSuitability && strat.timeframeSuitability.length > 0) {
    const normTf = normalizeTimeframe(strat.timeframeSuitability[0])
    selectedTimeframe.value = normTf
    if (['M1', 'M5', 'M15', 'M30'].includes(normTf)) {
      inSampleDays.value = 90
      outOfSampleDays.value = 30
    }
  } else {
    // Also infer timeframe from generated strategy naming convention e.g. MgcM30Trend53434
    const tfMatch = strat.id.match(/(M1|M5|M15|M30|H1|H4|D1|W1)/i)
    if (tfMatch) {
      const normTf = normalizeTimeframe(tfMatch[1])
      selectedTimeframe.value = normTf
      if (['M1', 'M5', 'M15', 'M30'].includes(normTf)) {
        inSampleDays.value = 90
        outOfSampleDays.value = 30
      }
    }
  }

  fetchStrategyParameters(strat.id)
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
    timeframe: normalizeTimeframe(selectedTimeframe.value),
    yearSpec: selectedYearSpec.value,
    inSampleDays: inSampleDays.value,
    outOfSampleDays: outOfSampleDays.value,
    isAnchored: isAnchored.value,
    initialCapital: initialCapital.value,
    lotSize: lotSize.value,
    commissionPerTrade: commissionPerTrade.value,
    slippagePct: slippagePct.value,
    parameterRanges: paramRanges.value.map(p => ({
      name: p.name,
      min: p.min,
      max: p.max,
      step: p.step
    }))
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
      const data = await res.json()
      const wfe = data.wfe ?? data.walkForwardEfficiency ?? 0
      const oosSharpe = data.oosSharpe ?? data.overallOosSharpe ?? 0
      const annualizedReturn = data.oosReturnPct ?? data.annualizedReturnPct ?? 0
      const maxDd = data.oosMaxDrawdownPct ?? data.maxDrawdownPct ?? 0

      activeReport.value = {
        ...data,
        wfe,
        oosSharpe,
        annualizedReturn,
        maxDd,
        robustnessVerdict: (wfe >= 0.6 && oosSharpe >= 1.0) ? 'ROBUST' : (wfe >= 0.5 ? 'MODERATE' : 'OVERFITTED'),
        folds: (data.folds || []).map((f: any, idx: number) => {
          const isSharpe = f.inSampleSharpe ?? f.isSharpe ?? 0
          const foldOosSharpe = f.outOfSampleSharpe ?? f.oosSharpe ?? 0
          const foldWfe = f.wfe ?? (isSharpe > 0 ? foldOosSharpe / isSharpe : 0)
          return {
            foldIndex: f.foldIndex ?? f.index ?? idx,
            inSampleStart: f.inSampleStart || f.isStart || '',
            inSampleEnd: f.inSampleEnd || f.isEnd || '',
            outOfSampleStart: f.outOfSampleStart || f.oosStart || '',
            outOfSampleEnd: f.outOfSampleEnd || f.oosEnd || '',
            inSampleSharpe: isSharpe,
            outOfSampleSharpe: foldOosSharpe,
            wfe: foldWfe,
            selectedParameters: f.selectedParameters || f.chosenParameters || {}
          }
        })
      }
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

function formatDate(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '—'
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  applyInstrumentDefaults(selectedSymbol.value)
  loadUniverse()
  fetchStrategyParameters(selectedStrategy.value)
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
        <!-- 1. Strategy & Target Instrument -->
        <div class="sidebar-section">
          <span class="section-label">1. Strategy &amp; Market Target</span>

          <div class="form-group">
            <label>Strategy (Auto-detects symbol, timeframe &amp; DNA parameters)</label>
            <StrategySelector
              v-model="selectedStrategy"
              @change="onWfaStrategySelected"
            />
          </div>

          <div class="asset-tabs mt-1">
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

          <div class="form-group mt-1">
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

          <div class="form-row-2 mt-1">
            <div class="form-group">
              <label>Timeframe (M1–W1)</label>
              <select v-model="selectedTimeframe" class="custom-select">
                <option
                  v-for="tf in TIMEFRAME_OPTIONS"
                  :key="tf.value"
                  :value="tf.value"
                >
                  {{ tf.label }}
                </option>
              </select>
            </div>
            <div class="form-group">
              <label>Historical Window</label>
              <select v-model="selectedYearSpec" class="custom-select">
                <option
                  v-for="yr in YEAR_RANGE_OPTIONS"
                  :key="yr.value"
                  :value="yr.value"
                >
                  {{ yr.label }}
                </option>
              </select>
            </div>
          </div>

          <!-- Tiered Cost & Sizing Row -->
          <div class="form-row-3 mt-1">
            <div class="form-group">
              <label>Capital ($)</label>
              <input
                v-model.number="initialCapital"
                type="number"
                step="5000"
                class="custom-input"
              />
            </div>
            <div class="form-group">
              <label>{{ selectedAssetClass === 'FUTURES' ? 'Contracts' : (selectedAssetClass === 'EQUITY' ? 'Shares' : 'Lots') }}</label>
              <input
                v-model.number="lotSize"
                type="number"
                step="0.1"
                min="0.01"
                class="custom-input"
              />
            </div>
            <div class="form-group">
              <label>Comm ($)</label>
              <input
                v-model.number="commissionPerTrade"
                type="number"
                step="0.1"
                min="0"
                class="custom-input"
              />
            </div>
          </div>
        </div>

        <!-- 2. Window Protocol & Presets -->
        <div class="sidebar-section">
          <div class="section-header-row">
            <span class="section-label">
              <Layers class="icon-xs text-amber" />
              2. Walk-Forward Window Protocol
            </span>
          </div>

          <div class="preset-pills">
            <button
              class="preset-pill"
              :class="{ active: activePreset === 'intraday' }"
              @click="applyPreset('intraday')"
            >
              Intraday (90d / 30d)
            </button>
            <button
              class="preset-pill"
              :class="{ active: activePreset === 'standard' }"
              @click="applyPreset('standard')"
            >
              Standard (180d / 60d)
            </button>
            <button
              class="preset-pill"
              :class="{ active: activePreset === 'macro' }"
              @click="applyPreset('macro')"
            >
              Macro (365d / 90d)
            </button>
            <button
              class="preset-pill"
              :class="{ active: activePreset === 'anchored' }"
              @click="applyPreset('anchored')"
            >
              Anchored Expanding
            </button>
          </div>

          <div class="form-row-2 mt-1">
            <div class="form-group">
              <label>In-Sample Train (Days)</label>
              <input
                v-model.number="inSampleDays"
                type="number"
                min="15"
                max="720"
                step="15"
                class="custom-input"
              />
            </div>
            <div class="form-group">
              <label>Out-of-Sample Test (Days)</label>
              <input
                v-model.number="outOfSampleDays"
                type="number"
                min="7"
                max="180"
                step="15"
                class="custom-input"
              />
            </div>
          </div>

          <label class="checkbox-row mt-1">
            <input type="checkbox" v-model="isAnchored" class="custom-checkbox" />
            <span>Anchored Expanding Window (vs Rolling Sliding)</span>
          </label>

          <!-- Live Fold Preview Banner -->
          <div class="fold-preview-strip">
            <Sparkles class="icon-xs text-accent" />
            <span>
              Est. <strong>~{{ estimatedFolds }} {{ isAnchored ? 'Anchored' : 'Sliding' }} Folds</strong>
              ({{ inSampleDays }}d IS → {{ outOfSampleDays }}d OOS) ·
              <strong>~{{ estimatedFolds * totalCombinations }}</strong> total backtests
            </span>
          </div>
        </div>

        <!-- 3. Parameter Search Grid -->
        <div class="sidebar-section">
          <div class="section-header-row">
            <span class="section-label">
              <Sliders class="icon-xs" />
              3. Parameter Search Grid
            </span>
            <span class="combos-badge">{{ totalCombinations }} combos / fold</span>
          </div>

          <div class="param-grid-list">
            <div
              v-for="(param, idx) in paramRanges"
              :key="param.name + '-' + idx"
              class="param-row-card"
            >
              <div class="param-header">
                <div class="param-title-group">
                  <span class="param-human-label">{{ param.label || param.name }}</span>
                  <span class="param-code-pill">{{ param.name }}</span>
                  <span v-if="param.defaultValue !== undefined" class="param-default-pill">
                    def: {{ param.defaultValue }}
                  </span>
                </div>
                <button
                  v-if="paramRanges.length > 1"
                  @click="removeParam(idx)"
                  class="btn-icon-del"
                  title="Remove parameter from grid"
                >
                  ×
                </button>
              </div>

              <div class="param-controls">
                <div class="param-val-box">
                  <label>Min</label>
                  <input v-model.number="param.min" type="number" step="any" class="mini-input" />
                </div>
                <div class="param-val-box">
                  <label>Max</label>
                  <input v-model.number="param.max" type="number" step="any" class="mini-input" />
                </div>
                <div class="param-val-box">
                  <label>Step ({{ stepsForParam(param) }}×)</label>
                  <input v-model.number="param.step" type="number" step="any" class="mini-input" />
                </div>
              </div>
            </div>
          </div>

          <!-- One-Click Discovered Strategy Parameters -->
          <div v-if="inactiveCatalogParams.length > 0" class="available-params-box">
            <span class="avail-label">+ Add strategy parameter:</span>
            <div class="avail-chips">
              <button
                v-for="avail in inactiveCatalogParams"
                :key="avail.name"
                class="avail-chip"
                @click="addCatalogParam(avail)"
              >
                + {{ avail.label || avail.name }}
              </button>
            </div>
          </div>

          <div class="param-actions">
            <button class="btn-text" @click="addCustomParam">+ Add Parameter</button>
            <button class="btn-text text-muted" @click="resetStrategyParams">Reset Strategy Defaults</button>
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
            <span>{{ isRunning ? 'Optimizing Walk-Forward Folds...' : 'Launch Walk-Forward Analysis' }}</span>
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
            <span>Target: <strong>{{ selectedSymbol }} ({{ selectedTimeframe }} · {{ selectedYearSpec }})</strong></span>
            <span>Grid: <strong>{{ totalCombinations }} iterations per fold</strong></span>
          </div>
        </div>

        <!-- Report Loaded State -->
        <div v-else-if="activeReport" class="report-container">
          <!-- Top Verdict Banner -->
          <div
            class="verdict-banner"
            :class="activeReport.robustnessVerdict === 'ROBUST' || activeReport.wfe >= 0.6 ? 'robust' : 'warning'"
          >
            <div class="verdict-left">
              <CheckCircle
                v-if="activeReport.robustnessVerdict === 'ROBUST' || activeReport.wfe >= 0.6"
                class="icon-md"
              />
              <AlertTriangle v-else class="icon-md" />
              <div>
                <h3 class="verdict-title">
                  {{ activeReport.robustnessVerdict === 'ROBUST' || activeReport.wfe >= 0.6 ? 'ROBUST STRATEGY DETECTED' : 'OVERFITTING DETECTED (CAUTION)' }}
                </h3>
                <p class="verdict-desc">
                  WFE of {{ (activeReport.wfe * 100).toFixed(1) }}% indicates out-of-sample performance
                  {{ activeReport.wfe >= 0.6 ? 'holds up strongly against' : 'diverges from' }} in-sample optimization.
                </p>
              </div>
            </div>
            <div class="verdict-badge">
              <span>WFE: {{ (activeReport.wfe * 100).toFixed(0) }}%</span>
            </div>
          </div>

          <!-- KPI Cards Strip -->
          <div class="kpi-grid">
            <div class="kpi-card">
              <span class="kpi-label">OOS Sharpe Ratio</span>
              <span class="kpi-value text-emerald">{{ (activeReport.oosSharpe ?? 0).toFixed(2) }}</span>
              <span class="kpi-sub">Out-of-sample aggregate</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">Walk-Forward Efficiency</span>
              <span
                class="kpi-value"
                :class="activeReport.wfe >= 0.6 ? 'text-emerald' : 'text-amber'"
              >
                {{ (activeReport.wfe * 100).toFixed(1) }}%
              </span>
              <span class="kpi-sub">Threshold: &gt; 60%</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">OOS Total Return</span>
              <span class="kpi-value text-cyan">{{ (activeReport.annualizedReturn ?? 0).toFixed(1) }}%</span>
              <span class="kpi-sub">Stitched OOS equity growth</span>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">OOS Max Drawdown</span>
              <span class="kpi-value text-rose">{{ (activeReport.maxDd ?? 0).toFixed(1) }}%</span>
              <span class="kpi-sub">Worst OOS peak-to-trough</span>
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
            Select any built-in or evolutionary strategy on the left—its native instrument, timeframe (<code>M1</code>–<code>W1</code>),
            cost preset, and real optimizable DNA parameters are configured automatically.
          </p>
          <div class="feature-bullets">
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>All 8 standard timeframes (<code>M1</code>, <code>M5</code>, <code>M15</code>, <code>M30</code>, <code>H1</code>, <code>H4</code>, <code>D1</code>, <code>W1</code>) &amp; multi-year BarStore integration</span>
            </div>
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>Automatic strategy DNA &amp; field introspection—no manual parameter name guessing</span>
            </div>
            <div class="bullet-item">
              <CheckCircle class="icon-xs text-emerald" />
              <span>Walk-Forward Efficiency (WFE) score &amp; parameter stability heatmaps across sliding market regimes</span>
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
              <span class="history-time">{{ formatDate(run.createdAt) }}</span>
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
  grid-template-columns: 390px 1fr;
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
  gap: 0.85rem;
  overflow-y: auto;
  overflow-x: hidden;
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
  font-size: 0.74rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.03em;
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
  padding: 0.15rem 0.45rem;
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
  padding: 0.38rem 0.45rem;
  border-radius: 5px;
  font-size: 0.72rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.preset-pill:hover, .preset-pill.active {
  background: rgba(217, 119, 6, 0.16);
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
  font-size: 0.74rem;
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
  gap: 0.25rem;
  min-width: 0;
}

.form-group label {
  font-size: 0.72rem;
  color: var(--text-secondary);
}

.form-row-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
}

.form-row-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.45rem;
}

.mt-1 {
  margin-top: 0.25rem;
}

.custom-select, .custom-input {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: #0f0f0f;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.42rem 0.55rem;
  font-size: 0.78rem;
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
  font-size: 0.74rem;
  color: var(--text-secondary);
  cursor: pointer;
}

.fold-preview-strip {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  background: rgba(217, 119, 6, 0.08);
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: 5px;
  padding: 0.42rem 0.6rem;
  font-size: 0.72rem;
  color: var(--text-secondary);
  line-height: 1.35;
}

.fold-preview-strip strong {
  color: var(--text-primary);
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
  padding: 0.55rem;
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  min-width: 0;
}

.param-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.4rem;
}

.param-title-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.35rem;
  min-width: 0;
}

.param-human-label {
  font-size: 0.76rem;
  font-weight: 600;
  color: var(--text-primary);
}

.param-code-pill {
  font-size: 0.65rem;
  font-family: monospace;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.1);
  border: 1px solid rgba(56, 189, 248, 0.25);
  padding: 0.05rem 0.32rem;
  border-radius: 3px;
}

.param-default-pill {
  font-size: 0.65rem;
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.04);
  padding: 0.05rem 0.32rem;
  border-radius: 3px;
}

.btn-icon-del {
  background: transparent;
  border: none;
  color: var(--danger);
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
  padding: 0 0.2rem;
}

.param-controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.4rem;
}

.param-val-box {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}

.param-val-box label {
  font-size: 0.65rem;
  color: var(--text-muted);
  white-space: nowrap;
}

.mini-input {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: #141414;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.3rem 0.4rem;
  font-size: 0.76rem;
  color: var(--text-primary);
  font-family: monospace;
}

.mini-input:focus {
  border-color: var(--accent);
  outline: none;
}

.available-params-box {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  margin-top: 0.15rem;
}

.avail-label {
  font-size: 0.68rem;
  color: var(--text-muted);
}

.avail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.3rem;
}

.avail-chip {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-secondary);
  font-size: 0.68rem;
  padding: 0.2rem 0.45rem;
  cursor: pointer;
  transition: all 0.15s;
}

.avail-chip:hover {
  border-color: var(--accent);
  color: var(--text-primary);
  background: rgba(217, 119, 6, 0.12);
}

.param-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 0.2rem;
}

.btn-text {
  background: transparent;
  border: none;
  color: var(--accent);
  font-size: 0.74rem;
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
  padding: 0.68rem 1rem;
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
