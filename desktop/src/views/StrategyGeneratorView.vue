<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import { useStatusBar } from '@/composables/useStatusBar'
import EquityChart from '@/components/EquityChart.vue'

interface DatabankCandidate {
  id: string
  symbol: string
  timeframe: string
  yearSpec: string
  archetype: string
  direction: string
  entryOrderType: string
  tier: string
  compositeScore: number
  totalTrades: number
  isTrades: number
  oosTrades: number
  netProfit: number
  isNetProfit: number
  oosNetProfit: number
  totalReturnPct: number
  profitFactor: number
  isProfitFactor: number
  oosProfitFactor: number
  sharpeRatio: number
  isSharpeRatio: number
  oosSharpeRatio: number
  winRatePct: number
  maxDrawdownPct: number
  retDdRatio: number
  rSquared: number
  mcVar95: number
  mcLossProbPct: number
  generationFound: number
  createdAt: string
  promoted: boolean
  javaFilePath: string
  longSignalSummary: string
  shortSignalSummary: string
  entrySummary: string
  exitSummary: string
  indicators: string[]
  equityCurve: number[]
  oosStartIndex: number
  javaSourceCode: string
}

interface GeneratorStatus {
  running: boolean
  jobId: string | null
  state: string
  message: string
  progressPct: number
  currentGeneration: number
  maxGenerations: number
  evaluatedCount: number
  totalToEvaluate: number
  evalSpeedPerSec: number
  passedCount: number
  bestScoreFound: number
  databankCount: number
  totalBarsLoaded: number
  isBarsCount: number
  oosBarsCount: number
}

const router = useRouter()
const { controlPlaneUrl } = useControlPlaneConfig()
const { setStatus } = useStatusBar()

// Form configuration
const symbol = ref('MGC')
const timeframe = ref('M30')
const yearSpec = ref('2025-2026')
const oosSplitPct = ref(30)
const searchMode = ref('GENETIC')
const archetype = ref('ALL')
const direction = ref('BOTH')
const entryOrderType = ref('ALL')
const populationSize = ref(24)
const generations = ref(5)

const minTrades = ref(12)
const minProfitFactor = ref(1.15)
const minOosProfitFactor = ref(1.02)
const minSharpe = ref(0.40)
const maxDrawdownPct = ref(25.0)
const initialCapital = ref(10000)
const lotSize = ref(1.0)
const commissionPerTrade = ref(0.62)
const slippagePct = ref(0.00005)

const allBuildingBlocks = [
  { id: 'DONCHIAN_BREAKOUT', label: 'Donchian Channel Breakout', desc: 'HighestHigh / LowestLow N-bar channel breakouts' },
  { id: 'MA_CROSSOVER', label: 'EMA / SMA Crossover', desc: 'Fast EMA vs Slow SMA trend alignment' },
  { id: 'PRICE_VS_MA', label: 'Price vs EMA Filter', desc: 'Directional trend filter above/below EMA' },
  { id: 'SUPERTREND', label: 'SuperTrend Volatility Band', desc: 'ATR-scaled dynamic trend channel' },
  { id: 'BOLLINGER_BANDS', label: 'Bollinger Bands', desc: 'Volatility squeeze breakouts & mean-reversion dips' },
  { id: 'RSI_FILTER', label: 'RSI Oscillator', desc: 'Momentum confirmation & oversold/overbought extremes' },
  { id: 'ADX_REGIME', label: 'ADX Trend Regime', desc: 'Directional movement strength filter' },
  { id: 'ATR_EXPANSION', label: 'ATR Volatility Expansion', desc: 'Fast ATR > Slow ATR breakout acceleration' },
  { id: 'DAILY_HIGH_LOW', label: 'Daily High / Low Structure', desc: 'Session reference high/low progression' },
  { id: 'MOMENTUM_ROC', label: 'ATR-Normalized Momentum ROC', desc: 'N-bar price impulse measured in ATR units' },
]

const selectedBlocks = ref<string[]>(allBuildingBlocks.map(b => b.id))

const instruments = ref<string[]>([
  'MGC', 'GC', 'MES', 'MNQ', 'M2K', 'EMD',
  'EUR_USD', 'GBP_USD', 'USD_JPY', 'GBP_JPY', 'AUD_USD', 'USD_CAD', 'USD_CHF', 'NZD_USD', 'XAU_USD'
])

const status = ref<GeneratorStatus>({
  running: false,
  jobId: null,
  state: 'IDLE',
  message: 'Ready to mine strategies.',
  progressPct: 0,
  currentGeneration: 0,
  maxGenerations: 0,
  evaluatedCount: 0,
  totalToEvaluate: 0,
  evalSpeedPerSec: 0,
  passedCount: 0,
  bestScoreFound: 0,
  databankCount: 0,
  totalBarsLoaded: 0,
  isBarsCount: 0,
  oosBarsCount: 0,
})

const databank = ref<DatabankCandidate[]>([])
const selectedCandidateId = ref<string | null>(null)
const inspectorTab = ref<'overview' | 'rules' | 'code'>('overview')
const tierFilter = ref<string>('ALL')
const searchQuery = ref<string>('')
const actionBusyId = ref<string | null>(null)
const copiedCode = ref(false)
const showConfigPanel = ref(true)

let pollTimer: any = null

const selectedCandidate = computed(() => {
  if (!databank.value.length) return null
  if (selectedCandidateId.value) {
    const found = databank.value.find(c => c.id === selectedCandidateId.value)
    if (found) return found
  }
  return databank.value[0]
})

const filteredDatabank = computed(() => {
  return databank.value.filter(c => {
    if (tierFilter.value !== 'ALL' && c.tier !== tierFilter.value) return false
    if (searchQuery.value.trim()) {
      const q = searchQuery.value.trim().toLowerCase()
      return (
        c.id.toLowerCase().includes(q) ||
        c.symbol.toLowerCase().includes(q) ||
        c.archetype.toLowerCase().includes(q) ||
        c.longSignalSummary.toLowerCase().includes(q)
      )
    }
    return true
  })
})

function applyPreset(preset: 'mgc' | 'mnq' | 'mes' | 'eurusd' | 'gbpjpy') {
  if (preset === 'mgc') {
    symbol.value = 'MGC'
    timeframe.value = 'M30'
    yearSpec.value = '2025-2026'
    archetype.value = 'BREAKOUT'
    entryOrderType.value = 'STOP'
    initialCapital.value = 10000
    lotSize.value = 1.0
    commissionPerTrade.value = 0.62
  } else if (preset === 'mnq') {
    symbol.value = 'MNQ'
    timeframe.value = 'M15'
    yearSpec.value = '2025-2026'
    archetype.value = 'MOMENTUM'
    entryOrderType.value = 'ALL'
    initialCapital.value = 10000
    lotSize.value = 1.0
    commissionPerTrade.value = 0.62
  } else if (preset === 'mes') {
    symbol.value = 'MES'
    timeframe.value = 'H1'
    yearSpec.value = '2024-2026'
    archetype.value = 'ALL'
    entryOrderType.value = 'ALL'
    initialCapital.value = 10000
    lotSize.value = 1.0
    commissionPerTrade.value = 0.62
  } else if (preset === 'eurusd') {
    symbol.value = 'EUR_USD'
    timeframe.value = 'H1'
    yearSpec.value = '2023-2025'
    archetype.value = 'TREND_FOLLOWING'
    entryOrderType.value = 'MARKET'
    initialCapital.value = 10000
    lotSize.value = 0.1
    commissionPerTrade.value = 0.70
  } else if (preset === 'gbpjpy') {
    symbol.value = 'GBP_JPY'
    timeframe.value = 'H1'
    yearSpec.value = '2023-2025'
    archetype.value = 'BREAKOUT'
    entryOrderType.value = 'STOP'
    initialCapital.value = 10000
    lotSize.value = 0.1
    commissionPerTrade.value = 0.70
  }
}

function toggleBlock(id: string) {
  const idx = selectedBlocks.value.indexOf(id)
  if (idx >= 0) {
    if (selectedBlocks.value.length > 1) {
      selectedBlocks.value.splice(idx, 1)
    }
  } else {
    selectedBlocks.value.push(id)
  }
}

async function fetchUniverse() {
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/universe`)
    if (res.ok) {
      const list = await res.json()
      if (Array.isArray(list) && list.length > 0) {
        const syms = list.map((item: any) => item.symbol).filter(Boolean)
        const merged = Array.from(new Set([...instruments.value, ...syms]))
        instruments.value = merged
      }
    }
  } catch (err) {
    // keep default list
  }
}

async function fetchDatabankAndStatus() {
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/generator/databank`)
    if (res.ok) {
      const data = await res.json()
      if (data.status) {
        status.value = data.status
      }
      if (Array.isArray(data.candidates)) {
        databank.value = data.candidates
        if (!selectedCandidateId.value && databank.value.length > 0) {
          selectedCandidateId.value = databank.value[0].id
        }
      }
    }
  } catch (err) {
    // ignore transient poll errors
  }
}

async function startGenerator() {
  try {
    const payload = {
      symbol: symbol.value,
      timeframe: timeframe.value,
      yearSpec: yearSpec.value,
      oosSplitPct: oosSplitPct.value,
      searchMode: searchMode.value,
      archetype: archetype.value,
      direction: direction.value,
      entryOrderType: entryOrderType.value,
      populationSize: populationSize.value,
      generations: generations.value,
      buildingBlocks: selectedBlocks.value,
      minTrades: minTrades.value,
      minProfitFactor: minProfitFactor.value,
      minOosProfitFactor: minOosProfitFactor.value,
      minSharpe: minSharpe.value,
      maxDrawdownPct: maxDrawdownPct.value,
      initialCapital: initialCapital.value,
      lotSize: lotSize.value,
      commissionPerTrade: commissionPerTrade.value,
      slippagePct: slippagePct.value,
    }
    const res = await fetch(`${controlPlaneUrl.value}/api/generator/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      setStatus(err.error || 'Failed to start Strategy Generator', 'error')
      return
    }
    setStatus(`Started evolutionary mining on ${symbol.value} (${timeframe.value})`, 'info')
    await fetchDatabankAndStatus()
  } catch (err: any) {
    setStatus(err.message || 'Network error starting generator', 'error')
  }
}

async function stopGenerator() {
  try {
    await fetch(`${controlPlaneUrl.value}/api/generator/stop`, { method: 'POST' })
    setStatus('Stopping Strategy Generator after current batch...', 'info')
    await fetchDatabankAndStatus()
  } catch (err) {
    // ignore
  }
}

async function promoteCandidate(candidate: DatabankCandidate) {
  actionBusyId.value = candidate.id
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/generator/databank/${candidate.id}/promote`, {
      method: 'POST',
    })
    if (res.ok) {
      setStatus(`Promoted ${candidate.id} to Strategy Catalog & wrote Java class!`, 'info')
      await fetchDatabankAndStatus()
    } else {
      const err = await res.json().catch(() => ({}))
      setStatus(err.error || 'Failed to promote strategy', 'error')
    }
  } finally {
    actionBusyId.value = null
  }
}

async function runFullBacktest(candidate: DatabankCandidate) {
  actionBusyId.value = candidate.id
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/generator/databank/${candidate.id}/backtest`, {
      method: 'POST',
    })
    if (res.ok) {
      const data = await res.json()
      if (data.runId) {
        setStatus(`Launched full backtest for ${candidate.id}`, 'info')
        router.push(`/results/${data.runId}`)
      }
    } else {
      const err = await res.json().catch(() => ({}))
      setStatus(err.error || 'Failed to launch full backtest', 'error')
    }
  } finally {
    actionBusyId.value = null
  }
}

async function deleteCandidate(id: string) {
  await fetch(`${controlPlaneUrl.value}/api/generator/databank/${id}`, { method: 'DELETE' })
  if (selectedCandidateId.value === id) {
    selectedCandidateId.value = null
  }
  await fetchDatabankAndStatus()
}

async function clearAllCandidates() {
  await fetch(`${controlPlaneUrl.value}/api/generator/databank`, { method: 'DELETE' })
  selectedCandidateId.value = null
  await fetchDatabankAndStatus()
}

function copyJavaCode() {
  if (!selectedCandidate.value?.javaSourceCode) return
  navigator.clipboard.writeText(selectedCandidate.value.javaSourceCode)
  copiedCode.value = true
  setTimeout(() => {
    copiedCode.value = false
  }, 2000)
}

onMounted(async () => {
  await fetchUniverse()
  await fetchDatabankAndStatus()
  pollTimer = setInterval(fetchDatabankAndStatus, 900)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="generator-view">
    <!-- Top Header + Quick Institutional Presets -->
    <header class="page-header">
      <div>
        <div class="title-row">
          <h1>Strategy Generator</h1>
          <span class="engine-badge">SQX NATIVE ENGINE</span>
        </div>
        <p class="subtitle">
          Evolutionary multi-asset strategy discovery with In-Sample / Out-of-Sample partitioning, ATR risk normalization &amp; 1-click Java codegen
        </p>
      </div>
      <div class="preset-bar">
        <span class="preset-lbl">Quick Presets:</span>
        <button class="preset-btn gold" @click="applyPreset('mgc')">⚡ Gold MGC M30</button>
        <button class="preset-btn blue" @click="applyPreset('mnq')">📈 Nasdaq MNQ M15</button>
        <button class="preset-btn emerald" @click="applyPreset('mes')">🏛️ S&amp;P MES H1</button>
        <button class="preset-btn purple" @click="applyPreset('eurusd')">💱 EUR/USD H1</button>
        <button class="preset-btn amber" @click="applyPreset('gbpjpy')">🔥 GBP/JPY H1</button>
        <button class="toggle-cfg-btn" @click="showConfigPanel = !showConfigPanel">
          {{ showConfigPanel ? '▲ Hide Builder' : '▼ Show Builder' }}
        </button>
      </div>
    </header>

    <!-- 3-Column Configuration Matrix -->
    <div v-if="showConfigPanel" class="config-grid">
      <!-- Card 1: Market, Data & Partitioning -->
      <section class="cfg-card">
        <div class="cfg-card-header">
          <span class="step-num">1</span>
          <h3>Market, Timeframe &amp; IS/OOS Split</h3>
        </div>
        <div class="form-grid-2">
          <div class="field">
            <label>Instrument / Symbol</label>
            <select v-model="symbol" :disabled="status.running">
              <option v-for="sym in instruments" :key="sym" :value="sym">{{ sym }}</option>
            </select>
          </div>
          <div class="field">
            <label>Candle Timeframe</label>
            <select v-model="timeframe" :disabled="status.running">
              <option value="M5">M5 (5 Minutes)</option>
              <option value="M15">M15 (15 Minutes)</option>
              <option value="M30">M30 (30 Minutes)</option>
              <option value="H1">H1 (1 Hour)</option>
              <option value="H4">H4 (4 Hours)</option>
              <option value="D1">D1 (Daily)</option>
            </select>
          </div>
          <div class="field">
            <label>Historical Window</label>
            <select v-model="yearSpec" :disabled="status.running">
              <option value="2025-2026">2025–2026 (Recent Regime)</option>
              <option value="2024-2026">2024–2026 (3-Year Cycle)</option>
              <option value="2022-2026">2022–2026 (5-Year Multi-Regime)</option>
              <option value="2020-2026">2020–2026 (Full Macro Cycle)</option>
              <option value="2023-2025">2023–2025 (FX Multi-Year)</option>
              <option value="2025">2025 (Single Year)</option>
              <option value="all">All Available Years</option>
            </select>
          </div>
          <div class="field">
            <label>IS / OOS Partition</label>
            <select v-model.number="oosSplitPct" :disabled="status.running">
              <option :value="20">80% In-Sample / 20% OOS</option>
              <option :value="30">70% In-Sample / 30% OOS (SQX Std)</option>
              <option :value="40">60% In-Sample / 40% OOS (Strict)</option>
            </select>
          </div>
          <div class="field">
            <label>Initial Capital ($)</label>
            <input type="number" v-model.number="initialCapital" step="1000" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Size (Lots / Contracts)</label>
            <input type="number" v-model.number="lotSize" step="0.01" :disabled="status.running" />
          </div>
        </div>
      </section>

      <!-- Card 2: Evolution Mode & Building Blocks -->
      <section class="cfg-card">
        <div class="cfg-card-header">
          <span class="step-num">2</span>
          <h3>Evolution Engine &amp; Building Blocks</h3>
        </div>
        <div class="form-grid-3">
          <div class="field">
            <label>Search Engine</label>
            <select v-model="searchMode" :disabled="status.running">
              <option value="GENETIC">Genetic Evolution (GA)</option>
              <option value="RANDOM">Random Monte-Carlo Mining</option>
            </select>
          </div>
          <div class="field">
            <label>Strategy Archetype</label>
            <select v-model="archetype" :disabled="status.running">
              <option value="ALL">All Archetypes</option>
              <option value="BREAKOUT">Volatility Breakout</option>
              <option value="TREND_FOLLOWING">Trend Following</option>
              <option value="MOMENTUM">Momentum Impulse</option>
              <option value="PULLBACK">Trend Pullback</option>
              <option value="MEAN_REVERSION">Mean Reversion</option>
            </select>
          </div>
          <div class="field">
            <label>Entry Order Type</label>
            <select v-model="entryOrderType" :disabled="status.running">
              <option value="ALL">Stop Breakout + Market</option>
              <option value="STOP">Stop Pending Breakout</option>
              <option value="MARKET">Market on Bar Close</option>
            </select>
          </div>
          <div class="field">
            <label>Direction</label>
            <select v-model="direction" :disabled="status.running">
              <option value="BOTH">Long &amp; Short (Symmetric)</option>
              <option value="LONG_ONLY">Long Only</option>
              <option value="SHORT_ONLY">Short Only</option>
            </select>
          </div>
          <div class="field">
            <label>Population Size</label>
            <input type="number" v-model.number="populationSize" min="10" max="200" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Generations</label>
            <input type="number" v-model.number="generations" min="1" max="50" :disabled="status.running" />
          </div>
        </div>

        <div class="blocks-title">Active Signal &amp; Filter Building Blocks ({{ selectedBlocks.length }}/{{ allBuildingBlocks.length }})</div>
        <div class="blocks-chip-grid">
          <button
            v-for="b in allBuildingBlocks"
            :key="b.id"
            type="button"
            :class="['block-chip', { active: selectedBlocks.includes(b.id) }]"
            :title="b.desc"
            :disabled="status.running"
            @click="toggleBlock(b.id)"
          >
            <span class="chip-dot"></span>
            {{ b.label }}
          </button>
        </div>
      </section>

      <!-- Card 3: Institutional Acceptance Gate -->
      <section class="cfg-card">
        <div class="cfg-card-header">
          <span class="step-num">3</span>
          <h3>Acceptance Gate (IS + Blind OOS Filters)</h3>
        </div>
        <div class="form-grid-2">
          <div class="field">
            <label>Min Total Trades</label>
            <input type="number" v-model.number="minTrades" min="1" max="500" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Min Overall Profit Factor</label>
            <input type="number" v-model.number="minProfitFactor" step="0.05" min="0.5" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Min Blind OOS Profit Factor</label>
            <input type="number" v-model.number="minOosProfitFactor" step="0.05" min="0.5" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Min Sharpe Ratio</label>
            <input type="number" v-model.number="minSharpe" step="0.1" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Max Drawdown (%)</label>
            <input type="number" v-model.number="maxDrawdownPct" step="1" min="2" max="80" :disabled="status.running" />
          </div>
          <div class="field">
            <label>Commission ($ / trade)</label>
            <input type="number" v-model.number="commissionPerTrade" step="0.05" min="0" :disabled="status.running" />
          </div>
        </div>
        <div class="gate-summary-box">
          <div><strong>Search Budget:</strong> {{ populationSize * generations }} candidate strategies</div>
          <div><strong>Validation:</strong> In-Sample + Unseen OOS + 80-run Monte Carlo</div>
        </div>
      </section>
    </div>

    <!-- Live Telemetry & Execution Strip -->
    <div class="telemetry-strip">
      <div class="telemetry-actions">
        <button
          v-if="!status.running"
          class="btn-start-mining"
          @click="startGenerator"
        >
          🧬 Start Strategy Mining ({{ populationSize * generations }} Candidates)
        </button>
        <button
          v-else
          class="btn-stop-mining"
          @click="stopGenerator"
        >
          ⏹ Stop Mining
        </button>
      </div>

      <div class="telemetry-progress">
        <div class="progress-meta">
          <span class="status-msg">
            <span v-if="status.running" class="pulse-dot"></span>
            {{ status.message }}
          </span>
          <span class="pct-lbl">{{ status.progressPct }}%</span>
        </div>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: `${status.progressPct}%` }"></div>
        </div>
      </div>

      <div class="telemetry-kpis">
        <div class="tkpi">
          <span class="tkpi-lbl">Generation</span>
          <span class="tkpi-val">{{ status.currentGeneration }} / {{ status.maxGenerations }}</span>
        </div>
        <div class="tkpi">
          <span class="tkpi-lbl">Evaluated</span>
          <span class="tkpi-val">{{ status.evaluatedCount }} / {{ status.totalToEvaluate }}</span>
        </div>
        <div class="tkpi">
          <span class="tkpi-lbl">Speed</span>
          <span class="tkpi-val amber">{{ status.evalSpeedPerSec }} /s</span>
        </div>
        <div class="tkpi">
          <span class="tkpi-lbl">Passed Gate</span>
          <span class="tkpi-val emerald">{{ status.passedCount }}</span>
        </div>
        <div class="tkpi">
          <span class="tkpi-lbl">Best Score</span>
          <span class="tkpi-val">{{ status.bestScoreFound.toFixed(1) }}</span>
        </div>
      </div>
    </div>

    <!-- Main Workspace: Databank Table + Strategy Inspector -->
    <div class="databank-workspace">
      <!-- Databank Table Section -->
      <section class="databank-card">
        <div class="databank-header">
          <div class="db-title-group">
            <h2>Strategy Databank</h2>
            <span class="db-count">{{ filteredDatabank.length }} strategies</span>
          </div>
          <div class="db-controls">
            <div class="tier-pills">
              <button :class="['tier-pill', { active: tierFilter === 'ALL' }]" @click="tierFilter = 'ALL'">All</button>
              <button :class="['tier-pill elite', { active: tierFilter === 'ELITE' }]" @click="tierFilter = 'ELITE'">Elite (82+)</button>
              <button :class="['tier-pill inst', { active: tierFilter === 'INSTITUTIONAL' }]" @click="tierFilter = 'INSTITUTIONAL'">Institutional</button>
              <button :class="['tier-pill qual', { active: tierFilter === 'QUALIFIED' }]" @click="tierFilter = 'QUALIFIED'">Qualified</button>
            </div>
            <input
              v-model="searchQuery"
              type="text"
              placeholder="Filter by ID, symbol, rule..."
              class="db-search"
            />
            <button
              v-if="databank.length > 0"
              class="btn-clear-db"
              @click="clearAllCandidates"
            >
              Clear Databank
            </button>
          </div>
        </div>

        <div v-if="filteredDatabank.length === 0" class="empty-databank">
          <p v-if="status.running">Mining in progress — strategies passing the IS + OOS acceptance gate will stream here automatically...</p>
          <p v-else>No strategies in the Databank yet. Choose an instrument or preset above and click <strong>Start Strategy Mining</strong>.</p>
        </div>

        <div v-else class="table-scroll">
          <table class="databank-table">
            <thead>
              <tr>
                <th>Rank</th>
                <th>Strategy ID</th>
                <th>Market</th>
                <th>Archetype</th>
                <th>Trades (IS / OOS)</th>
                <th>Net Profit (IS / OOS)</th>
                <th>Profit Factor (IS / OOS)</th>
                <th>Sharpe</th>
                <th>Max DD</th>
                <th>Ret/DD</th>
                <th>R²</th>
                <th>SQX Score</th>
                <th style="text-align: right;">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(c, idx) in filteredDatabank"
                :key="c.id"
                :class="{ selected: selectedCandidate?.id === c.id }"
                @click="selectedCandidateId = c.id"
              >
                <td>
                  <span :class="['tier-badge', c.tier.toLowerCase()]">#{{ idx + 1 }} {{ c.tier }}</span>
                </td>
                <td class="font-mono font-bold text-white">
                  {{ c.id }}
                  <span v-if="c.promoted" class="promoted-pill" title="Registered in Strategy Catalog">IN CATALOG</span>
                </td>
                <td>
                  <span class="sym-badge">{{ c.symbol }}</span>
                  <span class="tf-badge">{{ c.timeframe }}</span>
                </td>
                <td>
                  <span class="arch-text">{{ c.archetype }}</span>
                  <span class="entry-sub">{{ c.entryOrderType }}</span>
                </td>
                <td class="font-mono">
                  <strong>{{ c.totalTrades }}</strong>
                  <span class="sub-split">({{ c.isTrades }} / {{ c.oosTrades }})</span>
                </td>
                <td class="font-mono">
                  <span :class="c.netProfit >= 0 ? 'profit' : 'loss'">
                    ${{ c.netProfit.toFixed(0) }}
                  </span>
                  <span class="sub-split">
                    (${{ c.isNetProfit.toFixed(0) }} / ${{ c.oosNetProfit.toFixed(0) }})
                  </span>
                </td>
                <td class="font-mono">
                  <strong :class="c.profitFactor >= 1.3 ? 'profit' : ''">{{ c.profitFactor.toFixed(2) }}</strong>
                  <span class="sub-split">({{ c.isProfitFactor.toFixed(2) }} / {{ c.oosProfitFactor.toFixed(2) }})</span>
                </td>
                <td class="font-mono">{{ c.sharpeRatio.toFixed(2) }}</td>
                <td class="font-mono loss">{{ c.maxDrawdownPct.toFixed(1) }}%</td>
                <td class="font-mono">{{ c.retDdRatio.toFixed(2) }}</td>
                <td class="font-mono">{{ c.rSquared.toFixed(2) }}</td>
                <td>
                  <div class="score-cell">
                    <span class="score-num">{{ c.compositeScore.toFixed(1) }}</span>
                    <div class="score-bar-bg">
                      <div class="score-bar-fill" :style="{ width: `${Math.min(100, c.compositeScore)}%` }"></div>
                    </div>
                  </div>
                </td>
                <td class="actions-cell" @click.stop>
                  <button
                    class="act-btn promote"
                    :disabled="actionBusyId === c.id"
                    title="Save Java class & register in Strategy Catalog"
                    @click="promoteCandidate(c)"
                  >
                    {{ c.promoted ? '✓ Saved' : '+ Catalog' }}
                  </button>
                  <button
                    class="act-btn backtest"
                    :disabled="actionBusyId === c.id"
                    title="Run full backtest & open Price Chart"
                    @click="runFullBacktest(c)"
                  >
                    📈 Chart
                  </button>
                  <button
                    class="act-btn delete"
                    title="Remove from Databank"
                    @click="deleteCandidate(c.id)"
                  >
                    ✕
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Strategy DNA & Equity Inspector -->
      <section v-if="selectedCandidate" class="inspector-card">
        <div class="inspector-header">
          <div>
            <div class="insp-title-row">
              <h3>{{ selectedCandidate.id }}</h3>
              <span :class="['tier-badge', selectedCandidate.tier.toLowerCase()]">{{ selectedCandidate.tier }}</span>
              <span v-if="selectedCandidate.promoted" class="promoted-pill">REGISTERED IN STRATEGY CATALOG</span>
            </div>
            <p class="insp-sub">
              {{ selectedCandidate.symbol }} · {{ selectedCandidate.timeframe }} ({{ selectedCandidate.yearSpec }}) ·
              Discovered in Generation #{{ selectedCandidate.generationFound }}
            </p>
          </div>
          <div class="insp-actions">
            <button
              class="btn-promote-lg"
              :disabled="actionBusyId === selectedCandidate.id"
              @click="promoteCandidate(selectedCandidate)"
            >
              {{ selectedCandidate.promoted ? '✓ Saved in Strategy Catalog' : '💾 Promote to Strategy Catalog' }}
            </button>
            <button
              class="btn-backtest-lg"
              :disabled="actionBusyId === selectedCandidate.id"
              @click="runFullBacktest(selectedCandidate)"
            >
              🚀 Open Full Backtest &amp; Price Chart
            </button>
          </div>
        </div>

        <div class="insp-tabs">
          <button :class="['itab', { active: inspectorTab === 'overview' }]" @click="inspectorTab = 'overview'">
            IS / OOS Equity &amp; Robustness
          </button>
          <button :class="['itab', { active: inspectorTab === 'rules' }]" @click="inspectorTab = 'rules'">
            Strategy Rules &amp; Building Blocks
          </button>
          <button :class="['itab', { active: inspectorTab === 'code' }]" @click="inspectorTab = 'code'">
            Generated Java Code ({{ selectedCandidate.id }}.java)
          </button>
        </div>

        <!-- Tab 1: Overview & Equity Curve -->
        <div v-if="inspectorTab === 'overview'" class="insp-body">
          <div class="is-oos-compare-grid">
            <div class="split-box is">
              <span class="split-tag">IN-SAMPLE (TRAINING)</span>
              <div class="split-metrics">
                <div><span>Net Profit:</span> <strong :class="selectedCandidate.isNetProfit >= 0 ? 'profit' : 'loss'">${{ selectedCandidate.isNetProfit.toFixed(2) }}</strong></div>
                <div><span>Profit Factor:</span> <strong>{{ selectedCandidate.isProfitFactor.toFixed(2) }}</strong></div>
                <div><span>Sharpe Ratio:</span> <strong>{{ selectedCandidate.isSharpeRatio.toFixed(2) }}</strong></div>
                <div><span>Trades:</span> <strong>{{ selectedCandidate.isTrades }}</strong></div>
              </div>
            </div>
            <div class="split-box oos">
              <span class="split-tag oos">OUT-OF-SAMPLE (BLIND VALIDATION)</span>
              <div class="split-metrics">
                <div><span>Net Profit:</span> <strong :class="selectedCandidate.oosNetProfit >= 0 ? 'profit' : 'loss'">${{ selectedCandidate.oosNetProfit.toFixed(2) }}</strong></div>
                <div><span>Profit Factor:</span> <strong>{{ selectedCandidate.oosProfitFactor.toFixed(2) }}</strong></div>
                <div><span>Sharpe Ratio:</span> <strong>{{ selectedCandidate.oosSharpeRatio.toFixed(2) }}</strong></div>
                <div><span>Trades:</span> <strong>{{ selectedCandidate.oosTrades }}</strong></div>
              </div>
            </div>
            <div class="split-box mc">
              <span class="split-tag mc">ROBUSTNESS &amp; MONTE CARLO</span>
              <div class="split-metrics">
                <div><span>Equity R²:</span> <strong>{{ selectedCandidate.rSquared.toFixed(3) }}</strong></div>
                <div><span>Return / DD:</span> <strong>{{ selectedCandidate.retDdRatio.toFixed(2) }}</strong></div>
                <div><span>MC 95% VaR:</span> <strong>${{ selectedCandidate.mcVar95.toFixed(2) }}</strong></div>
                <div><span>MC Loss Risk:</span> <strong>{{ selectedCandidate.mcLossProbPct.toFixed(1) }}%</strong></div>
              </div>
            </div>
          </div>

          <div class="equity-wrap">
            <div class="eq-header">
              <h4>Combined In-Sample + Out-of-Sample Equity Curve</h4>
              <span class="oos-note">OOS split starts at sample #{{ selectedCandidate.oosStartIndex }} ({{ oosSplitPct }}% blind test window)</span>
            </div>
            <EquityChart
              v-if="selectedCandidate.equityCurve && selectedCandidate.equityCurve.length > 1"
              :data="selectedCandidate.equityCurve"
              :height="260"
            />
          </div>
        </div>

        <!-- Tab 2: Strategy Rules -->
        <div v-else-if="inspectorTab === 'rules'" class="insp-body">
          <div class="rules-grid">
            <div class="rule-box long">
              <div class="rule-title">🟢 Long Entry Signal</div>
              <div class="rule-code">{{ selectedCandidate.longSignalSummary }}</div>
            </div>
            <div class="rule-box short">
              <div class="rule-title">🔴 Short Entry Signal</div>
              <div class="rule-code">{{ selectedCandidate.shortSignalSummary }}</div>
            </div>
            <div class="rule-box entry">
              <div class="rule-title">⚡ Order Execution Method</div>
              <div class="rule-code">{{ selectedCandidate.entrySummary }}</div>
            </div>
            <div class="rule-box exit">
              <div class="rule-title">🛡️ Stop-Loss, Profit-Target &amp; Exit Management</div>
              <div class="rule-code">{{ selectedCandidate.exitSummary }}</div>
            </div>
          </div>
          <div class="ind-strip">
            <span class="ind-lbl">Indicators Used:</span>
            <span v-for="ind in selectedCandidate.indicators" :key="ind" class="ind-pill">{{ ind }}</span>
          </div>
        </div>

        <!-- Tab 3: Generated Java Code -->
        <div v-else-if="inspectorTab === 'code'" class="insp-body">
          <div class="code-toolbar">
            <span class="code-path font-mono">{{ selectedCandidate.javaFilePath }}</span>
            <button class="btn-copy-code" @click="copyJavaCode">
              {{ copiedCode ? '✓ Copied to Clipboard' : '📋 Copy Java Class' }}
            </button>
          </div>
          <pre class="java-code-viewer"><code>{{ selectedCandidate.javaSourceCode }}</code></pre>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.generator-view {
  padding: 1.5rem 2rem;
  color: #E5E7EB;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 1rem;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.title-row h1 {
  font-size: 1.6rem;
  font-weight: 700;
  color: #F9FAFB;
  margin: 0;
}

.engine-badge {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.2), rgba(217, 119, 6, 0.35));
  border: 1px solid rgba(245, 158, 11, 0.45);
  color: #FBBF24;
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
}

.subtitle {
  margin: 0.25rem 0 0 0;
  font-size: 0.85rem;
  color: #9CA3AF;
}

.preset-bar {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
}

.preset-lbl {
  font-size: 0.75rem;
  color: #9CA3AF;
  margin-right: 0.25rem;
}

.preset-btn, .toggle-cfg-btn {
  background: #181B22;
  border: 1px solid #2D3748;
  color: #E5E7EB;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.38rem 0.7rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.preset-btn:hover, .toggle-cfg-btn:hover {
  border-color: #F59E0B;
  color: #FBBF24;
}

.config-grid {
  display: grid;
  grid-template-columns: 1fr 1.35fr 1fr;
  gap: 1rem;
}

.cfg-card {
  background: #14171E;
  border: 1px solid #1F2937;
  border-radius: 10px;
  padding: 1rem 1.15rem;
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}

.cfg-card-header {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  border-bottom: 1px solid #1F2937;
  padding-bottom: 0.55rem;
}

.step-num {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(245, 158, 11, 0.2);
  border: 1px solid #F59E0B;
  color: #FBBF24;
  font-size: 0.75rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cfg-card-header h3 {
  font-size: 0.9rem;
  font-weight: 600;
  color: #F3F4F6;
  margin: 0;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.65rem;
}

.form-grid-3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0.65rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.28rem;
}

.field label {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #9CA3AF;
}

.field select, .field input {
  background: #0D0F14;
  border: 1px solid #2D3748;
  border-radius: 6px;
  color: #F9FAFB;
  padding: 0.42rem 0.55rem;
  font-size: 0.8rem;
  outline: none;
}

.field select:focus, .field input:focus {
  border-color: #F59E0B;
}

.blocks-title {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #9CA3AF;
}

.blocks-chip-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.block-chip {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.28rem 0.55rem;
  font-size: 0.72rem;
  border-radius: 6px;
  background: #0D0F14;
  border: 1px solid #2D3748;
  color: #9CA3AF;
  cursor: pointer;
}

.block-chip.active {
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.45);
  color: #34D399;
}

.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.gate-summary-box {
  margin-top: auto;
  padding: 0.6rem 0.75rem;
  background: rgba(59, 130, 246, 0.08);
  border: 1px solid rgba(59, 130, 246, 0.25);
  border-radius: 6px;
  font-size: 0.75rem;
  color: #93C5FD;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.telemetry-strip {
  background: #14171E;
  border: 1px solid #1F2937;
  border-radius: 10px;
  padding: 0.85rem 1.2rem;
  display: flex;
  align-items: center;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.btn-start-mining {
  background: linear-gradient(135deg, #F59E0B, #D97706);
  border: none;
  color: #0D0F14;
  font-weight: 700;
  font-size: 0.85rem;
  padding: 0.65rem 1.25rem;
  border-radius: 8px;
  cursor: pointer;
  white-space: nowrap;
}

.btn-start-mining:hover {
  filter: brightness(1.08);
}

.btn-stop-mining {
  background: rgba(239, 68, 68, 0.2);
  border: 1px solid #EF4444;
  color: #FCA5A5;
  font-weight: 700;
  font-size: 0.85rem;
  padding: 0.65rem 1.25rem;
  border-radius: 8px;
  cursor: pointer;
}

.telemetry-progress {
  flex: 1;
  min-width: 240px;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 0.78rem;
  color: #D1D5DB;
}

.status-msg {
  display: flex;
  align-items: center;
  gap: 0.45rem;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #F59E0B;
  box-shadow: 0 0 8px #F59E0B;
}

.progress-track {
  height: 8px;
  background: #0D0F14;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid #2D3748;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #F59E0B, #10B981);
  transition: width 0.25s ease;
}

.telemetry-kpis {
  display: flex;
  gap: 1.25rem;
}

.tkpi {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.tkpi-lbl {
  font-size: 0.68rem;
  text-transform: uppercase;
  color: #6B7280;
}

.tkpi-val {
  font-size: 0.92rem;
  font-weight: 700;
  font-family: monospace;
  color: #F9FAFB;
}

.tkpi-val.amber { color: #FBBF24; }
.tkpi-val.emerald { color: #34D399; }

.databank-workspace {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.databank-card, .inspector-card {
  background: #14171E;
  border: 1px solid #1F2937;
  border-radius: 10px;
  padding: 1rem 1.2rem;
}

.databank-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 0.85rem;
}

.db-title-group {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.db-title-group h2 {
  font-size: 1.05rem;
  margin: 0;
  color: #F9FAFB;
}

.db-count {
  font-size: 0.75rem;
  background: #1F2937;
  color: #D1D5DB;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
}

.db-controls {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
}

.tier-pills {
  display: flex;
  gap: 0.3rem;
}

.tier-pill {
  background: #0D0F14;
  border: 1px solid #2D3748;
  color: #9CA3AF;
  font-size: 0.72rem;
  padding: 0.25rem 0.6rem;
  border-radius: 6px;
  cursor: pointer;
}

.tier-pill.active {
  border-color: #F59E0B;
  color: #FBBF24;
}

.db-search {
  background: #0D0F14;
  border: 1px solid #2D3748;
  border-radius: 6px;
  color: #F9FAFB;
  padding: 0.3rem 0.6rem;
  font-size: 0.75rem;
  width: 200px;
}

.btn-clear-db {
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.35);
  color: #FCA5A5;
  font-size: 0.72rem;
  padding: 0.3rem 0.65rem;
  border-radius: 6px;
  cursor: pointer;
}

.empty-databank {
  padding: 2rem;
  text-align: center;
  color: #9CA3AF;
  font-size: 0.88rem;
}

.table-scroll {
  overflow-x: auto;
  max-height: 360px;
  overflow-y: auto;
}

.databank-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.78rem;
}

.databank-table th {
  text-align: left;
  padding: 0.55rem 0.65rem;
  border-bottom: 1px solid #1F2937;
  color: #9CA3AF;
  font-size: 0.68rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  position: sticky;
  top: 0;
  background: #14171E;
}

.databank-table td {
  padding: 0.55rem 0.65rem;
  border-bottom: 1px solid rgba(31, 41, 55, 0.6);
  white-space: nowrap;
}

.databank-table tbody tr {
  cursor: pointer;
  transition: background 0.12s ease;
}

.databank-table tbody tr:hover {
  background: rgba(255, 255, 255, 0.03);
}

.databank-table tbody tr.selected {
  background: rgba(245, 158, 11, 0.10);
}

.tier-badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
}

.tier-badge.elite {
  background: rgba(245, 158, 11, 0.2);
  color: #FBBF24;
  border: 1px solid rgba(245, 158, 11, 0.4);
}

.tier-badge.institutional {
  background: rgba(16, 185, 129, 0.18);
  color: #34D399;
  border: 1px solid rgba(16, 185, 129, 0.35);
}

.tier-badge.qualified {
  background: rgba(59, 130, 246, 0.18);
  color: #60A5FA;
  border: 1px solid rgba(59, 130, 246, 0.35);
}

.promoted-pill {
  margin-left: 0.4rem;
  font-size: 0.62rem;
  background: rgba(16, 185, 129, 0.2);
  color: #34D399;
  border: 1px solid rgba(16, 185, 129, 0.4);
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
}

.sym-badge {
  font-weight: 700;
  color: #F3F4F6;
  margin-right: 0.3rem;
}

.tf-badge {
  font-size: 0.68rem;
  background: #1F2937;
  color: #9CA3AF;
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
}

.entry-sub, .sub-split {
  font-size: 0.7rem;
  color: #6B7280;
  margin-left: 0.3rem;
}

.profit { color: #10B981; font-weight: 600; }
.loss { color: #EF4444; font-weight: 600; }

.score-cell {
  display: flex;
  align-items: center;
  gap: 0.45rem;
}

.score-num {
  font-family: monospace;
  font-weight: 700;
  color: #FBBF24;
}

.score-bar-bg {
  width: 48px;
  height: 6px;
  background: #0D0F14;
  border-radius: 999px;
  overflow: hidden;
}

.score-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #F59E0B, #10B981);
}

.actions-cell {
  display: flex;
  justify-content: flex-end;
  gap: 0.35rem;
}

.act-btn {
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.25rem 0.5rem;
  border-radius: 5px;
  cursor: pointer;
  border: 1px solid transparent;
}

.act-btn.promote {
  background: rgba(16, 185, 129, 0.15);
  border-color: rgba(16, 185, 129, 0.4);
  color: #34D399;
}

.act-btn.backtest {
  background: rgba(59, 130, 246, 0.15);
  border-color: rgba(59, 130, 246, 0.4);
  color: #60A5FA;
}

.act-btn.delete {
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.3);
  color: #FCA5A5;
}

.inspector-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
  padding-bottom: 0.85rem;
  border-bottom: 1px solid #1F2937;
}

.insp-title-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.insp-title-row h3 {
  margin: 0;
  font-size: 1.15rem;
  color: #F9FAFB;
}

.insp-sub {
  margin: 0.2rem 0 0 0;
  font-size: 0.78rem;
  color: #9CA3AF;
}

.insp-actions {
  display: flex;
  gap: 0.65rem;
}

.btn-promote-lg {
  background: rgba(16, 185, 129, 0.18);
  border: 1px solid #10B981;
  color: #34D399;
  font-weight: 600;
  font-size: 0.8rem;
  padding: 0.5rem 0.95rem;
  border-radius: 7px;
  cursor: pointer;
}

.btn-backtest-lg {
  background: linear-gradient(135deg, #3B82F6, #2563EB);
  border: none;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 0.8rem;
  padding: 0.5rem 0.95rem;
  border-radius: 7px;
  cursor: pointer;
}

.insp-tabs {
  display: flex;
  gap: 0.5rem;
  margin: 0.85rem 0;
}

.itab {
  background: #0D0F14;
  border: 1px solid #2D3748;
  color: #9CA3AF;
  font-size: 0.78rem;
  font-weight: 600;
  padding: 0.45rem 0.9rem;
  border-radius: 6px;
  cursor: pointer;
}

.itab.active {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.1);
}

.is-oos-compare-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0.85rem;
  margin-bottom: 1rem;
}

.split-box {
  background: #0D0F14;
  border: 1px solid #1F2937;
  border-radius: 8px;
  padding: 0.75rem 0.95rem;
}

.split-tag {
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #60A5FA;
  display: block;
  margin-bottom: 0.45rem;
}

.split-tag.oos { color: #34D399; }
.split-tag.mc { color: #FBBF24; }

.split-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.35rem;
  font-size: 0.78rem;
}

.split-metrics span {
  color: #9CA3AF;
}

.eq-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.45rem;
}

.eq-header h4 {
  margin: 0;
  font-size: 0.88rem;
  color: #E5E7EB;
}

.oos-note {
  font-size: 0.75rem;
  color: #34D399;
}

.rules-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.85rem;
}

.rule-box {
  background: #0D0F14;
  border: 1px solid #1F2937;
  border-radius: 8px;
  padding: 0.85rem 1rem;
}

.rule-title {
  font-size: 0.78rem;
  font-weight: 700;
  color: #D1D5DB;
  margin-bottom: 0.4rem;
}

.rule-code {
  font-family: monospace;
  font-size: 0.82rem;
  color: #FBBF24;
  line-height: 1.45;
}

.ind-strip {
  margin-top: 0.85rem;
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
}

.ind-lbl {
  font-size: 0.75rem;
  color: #9CA3AF;
}

.ind-pill {
  font-size: 0.72rem;
  background: #1F2937;
  color: #E5E7EB;
  padding: 0.2rem 0.55rem;
  border-radius: 999px;
}

.code-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.code-path {
  font-size: 0.75rem;
  color: #9CA3AF;
}

.btn-copy-code {
  background: #1F2937;
  border: 1px solid #374151;
  color: #F3F4F6;
  font-size: 0.75rem;
  padding: 0.35rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
}

.java-code-viewer {
  background: #0A0C10;
  border: 1px solid #1F2937;
  border-radius: 8px;
  padding: 0.9rem;
  font-size: 0.75rem;
  line-height: 1.45;
  color: #D1D5DB;
  max-height: 340px;
  overflow: auto;
  margin: 0;
}
</style>

