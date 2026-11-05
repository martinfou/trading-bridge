<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import { useStatusBar } from '@/composables/useStatusBar'
import { useStrategyCatalog } from '@/composables/useStrategyCatalog'
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
const { fetchCatalog } = useStrategyCatalog()

// Form configuration
const symbol = ref('MGC')
const timeframe = ref('M30')
const yearSpec = ref('2025-2026')
const oosSplitPct = ref(30)
const searchMode = ref('GENETIC')
const archetype = ref('ALL')
const direction = ref('BOTH')
const entryOrderType = ref('ALL')
const populationSize = ref(50)
const generations = ref(10)

const minTrades = ref(12)
const minProfitFactor = ref(1.15)
const minOosProfitFactor = ref(1.02)
const minSharpe = ref(0.40)
const maxDrawdownPct = ref(25.0)
const initialCapital = ref(10000)
const lotSize = ref(1.0)
const commissionPerTrade = ref(0.62)
const slippagePct = ref(0.00005)

interface BuildingBlockMeta {
  id: string
  label: string
  category: string
  formula: string
  desc: string
  benchmark: string
}

const allBuildingBlocks: BuildingBlockMeta[] = [
  { id: 'DONCHIAN_BREAKOUT', label: 'Donchian Channel Breakout', category: 'Breakout', formula: 'Close[0] > HighestHigh(N)[shift]', desc: 'Classic N-bar price channel breakout above prior highest high or below prior lowest low.', benchmark: 'Best for: Breakout & Trend Following on Futures/Gold' },
  { id: 'KELTNER_CHANNEL', label: 'Keltner ATR Channel', category: 'Breakout', formula: 'Close[0] > EMA(N) ± k × ATR(N)', desc: 'Volatility-adjusted exponential moving average envelope breakout.', benchmark: 'Best for: Smooth volatility breakouts without Bollinger whip' },
  { id: 'BB_KC_SQUEEZE', label: 'TTM Bollinger/Keltner Squeeze', category: 'Volatility', formula: 'BBWidth(N) < KCWidth(N) & Breakout', desc: 'Detects low-volatility compression when Bollinger Bands contract inside Keltner Channels, firing on expansion.', benchmark: 'Best for: Explosive post-consolidation breakouts' },
  { id: 'BOLLINGER_BANDS', label: 'Bollinger Bands', category: 'Volatility', formula: 'Close[0] vs SMA(N) ± k × StdDev(N)', desc: 'Supports both standard-deviation volatility breakouts and oversold/overbought mean-reversion dips.', benchmark: 'Best for: Volatility Breakout & Mean Reversion' },
  { id: 'SUPERTREND', label: 'SuperTrend Volatility Band', category: 'Trend', formula: 'Close[0] > MidRange(N) ± k × ATR(N)', desc: 'Dynamic ATR-trailing trend channel that flips direction only when price breaches the volatility band.', benchmark: 'Best for: Intraday Futures (MGC, MNQ, MES) Trend & Momentum' },
  { id: 'MA_CROSSOVER', label: 'EMA / SMA Crossover', category: 'Trend', formula: 'Fast EMA(N1)[s1] > Slow SMA(N2)[s2]', desc: 'Multi-period moving average alignment confirming structural trend direction.', benchmark: 'Best for: Trend Following & Multi-Horizon Filtering' },
  { id: 'PRICE_VS_MA', label: 'Price vs EMA Filter', category: 'Trend', formula: 'Close[s] > EMA(N)[s]', desc: 'Ensures entries only occur on the dominant side of the exponential moving average.', benchmark: 'Best for: Trend Pullback & Directional Bias' },
  { id: 'LINEAR_REG_SLOPE', label: 'Linear Regression Slope', category: 'Trend', formula: 'LinRegSlope(N)[s] > k × ATR(14)', desc: 'Least-squares linear regression slope normalized by ATR to measure true directional velocity.', benchmark: 'Best for: Institutional Trend & Momentum confirmation' },
  { id: 'HIGHER_HIGHS_SWING', label: 'Swing Structure (HH/HL)', category: 'Structure', formula: 'Highest(N,1) > Highest(N,N+1) & Lowest(N,1) > Lowest(N,N+1)', desc: 'Dow-theory multi-pivot swing progression confirming Higher-Highs + Higher-Lows (or Lower-Lows + Lower-Highs).', benchmark: 'Best for: Structural Trend & Pullback continuation' },
  { id: 'DAILY_HIGH_LOW', label: 'Daily High / Low Structure', category: 'Structure', formula: 'DailyHigh[s1] > DailyHigh[s2]', desc: 'Session reference high/low progression across 24-bar daily lookback windows.', benchmark: 'Best for: Session Breakout & Macro Trend alignment' },
  { id: 'VWAP_ANCHOR', label: 'Rolling VWAP Fair Value', category: 'Volume', formula: 'Close[0] > ∑(TypicalPrice × Vol) ÷ ∑Vol', desc: 'Volume-Weighted Average Price anchor filtering long entries above institutional fair value and shorts below.', benchmark: 'Best for: Index Futures (MNQ, MES) & Equities' },
  { id: 'VOLUME_SURGE', label: 'Volume Breakout Surge', category: 'Volume', formula: 'Volume[0] > k × AvgVolume(N)[1]', desc: 'Requires bar participation volume to exceed a multiple of the N-bar average volume.', benchmark: 'Best for: Filtering false breakouts on thin volume' },
  { id: 'MACD_HISTOGRAM', label: 'MACD Histogram Impulse', category: 'Momentum', formula: 'MACD(N1,N2)[s] > 0 & Rising', desc: 'Checks that the fast/slow EMA spread is both positive (or negative for shorts) and accelerating.', benchmark: 'Best for: Momentum Impulse & Trend acceleration' },
  { id: 'MOMENTUM_ROC', label: 'ATR-Normalized Momentum ROC', category: 'Momentum', formula: '(Close[0] − Close[N]) > k × ATR(14)', desc: 'Measures N-bar price displacement normalized by current ATR volatility.', benchmark: 'Best for: Cross-market Momentum strategies' },
  { id: 'RSI_FILTER', label: 'RSI Oscillator', category: 'Oscillator', formula: 'RSI(N)[s] < Oversold or > Momentum', desc: 'Relative Strength Index supporting both mean-reversion extremes (<35 / >65) and momentum (>55).', benchmark: 'Best for: Mean Reversion & Pullback timing' },
  { id: 'STOCHASTIC_OSC', label: 'Stochastic %K Oscillator', category: 'Oscillator', formula: '(Close − Lowest(N)) ÷ (Highest(N) − Lowest(N)) × 100', desc: 'Measures where price closed within its N-bar high-low range for reversal or thrust timing.', benchmark: 'Best for: Pullback entries inside a larger trend' },
  { id: 'CCI_OSCILLATOR', label: 'Commodity Channel Index (CCI)', category: 'Oscillator', formula: '(Close − SMA(N)) ÷ (0.015 × StdDev(N))', desc: 'Detects cyclical excursions beyond ±100 for either breakout thrusts or mean-reversion snapbacks.', benchmark: 'Best for: Gold (MGC/GC) & Commodity cycles' },
  { id: 'WILLIAMS_R', label: 'Williams %R Extreme', category: 'Oscillator', formula: '(Highest(N) − Close) ÷ (Highest(N) − Lowest(N)) × -100', desc: 'High-sensitivity oscillator capturing acute overbought/oversold exhaustion and momentum thrusts.', benchmark: 'Best for: Short-horizon Mean Reversion' },
  { id: 'ZSCORE_EXTREME', label: 'Statistical Z-Score Filter', category: 'Mean-Rev', formula: 'Z = (Close[s] − SMA(N)) ÷ StdDev(N)', desc: 'Quantifies exact standard-deviation distance from the rolling mean for statistical arbitrage entries.', benchmark: 'Best for: Institutional Mean Reversion (±1.5σ to ±2.5σ)' },
  { id: 'ENGULFING_PATTERN', label: 'Engulfing Candlestick Action', category: 'Price Action', formula: 'Close[0] > Open[0] & Close[0] > High[s] & Body ≥ k×ATR', desc: 'Price-action conviction bar whose real body engulfs prior bar structure with ATR-verified expansion.', benchmark: 'Best for: High-precision entry trigger on all timeframes' },
  { id: 'INSIDE_BAR_COIL', label: 'Inside Bar / NR Coil', category: 'Price Action', formula: 'Range[s] < 0.85 × ATR(14) & Breakout', desc: 'Narrow-range coiled bar followed by immediate directional range expansion.', benchmark: 'Best for: Low-risk, high R:R breakout entries' },
  { id: 'ADX_REGIME', label: 'ADX Trend Strength Regime', category: 'Regime', formula: 'ADX(N)[s] ≥ Threshold (18–32)', desc: 'Filters out trendless markets by requiring directional range efficiency above threshold.', benchmark: 'Best for: Regime filtering in Trend & Breakout models' },
  { id: 'ATR_EXPANSION', label: 'ATR Volatility Expansion', category: 'Regime', formula: 'ATR(Fast)[0] > k × ATR(Slow)[1]', desc: 'Confirms that short-term volatility is expanding relative to baseline historical volatility.', benchmark: 'Best for: Avoiding dead/flat overnight sessions' },
  { id: 'CHOPPINESS_FILTER', label: 'Kaufman Efficiency Ratio (ER)', category: 'Regime', formula: '|Close[0] − Close[N]| ÷ ∑|Close[i] − Close[i-1]|', desc: 'Fractal efficiency ratio (0 to 1) that blocks trades during noisy, choppy sideways whipsaws.', benchmark: 'Best for: Boosting Profit Factor & reducing drawdown' },
]

const selectedBlocks = ref<string[]>(allBuildingBlocks.map(b => b.id))
const activeBlockCategory = ref<string>('ALL')
const showBlocksDrawer = ref<boolean>(true)
const workspaceLayout = ref<'split' | 'stacked'>('split')

const blockCategories = ['ALL', 'Breakout', 'Volatility', 'Trend', 'Structure', 'Volume', 'Momentum', 'Oscillator', 'Mean-Rev', 'Price Action', 'Regime']

const visibleBuildingBlocks = computed(() => {
  if (activeBlockCategory.value === 'ALL') return allBuildingBlocks
  return allBuildingBlocks.filter(b => b.category === activeBlockCategory.value)
})

const instruments = ref<string[]>([
  'MGC', 'GC', 'MES', 'MNQ', 'M2K', 'EMD', 'AAPL',
  'EUR_USD', 'GBP_USD', 'USD_JPY', 'GBP_JPY', 'AUD_USD', 'USD_CAD', 'USD_CHF', 'NZD_USD', 'XAU_USD'
])

const timeframes = [
  { id: 'M5', label: 'M5' },
  { id: 'M15', label: 'M15' },
  { id: 'M30', label: 'M30' },
  { id: 'H1', label: 'H1' },
  { id: 'H4', label: 'H4' },
  { id: 'D1', label: 'D1' },
]

const archetypeOptions = [
  { id: 'ALL', label: 'All Styles' },
  { id: 'BREAKOUT', label: 'Breakout' },
  { id: 'TREND_FOLLOWING', label: 'Trend' },
  { id: 'MOMENTUM', label: 'Momentum' },
  { id: 'PULLBACK', label: 'Pullback' },
  { id: 'MEAN_REVERSION', label: 'Mean-Rev' },
]

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
const sortColumn = ref<string>('sqxScore')
const sortDir = ref<'asc' | 'desc'>('desc')
const actionBusyId = ref<string | null>(null)
const copiedCode = ref(false)
const showConfigPanel = ref(true)

let pollTimer: any = null

function toggleSort(col: string) {
  if (sortColumn.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortColumn.value = col
    sortDir.value = col === 'id' || col === 'market' || col === 'archetype' || col === 'maxDd' ? 'asc' : 'desc'
  }
}

function sortArrow(col: string): string {
  if (sortColumn.value !== col) return '⇅'
  return sortDir.value === 'asc' ? '▲' : '▼'
}

const selectedCandidate = computed(() => {
  if (!databank.value.length) return null
  if (selectedCandidateId.value) {
    const found = databank.value.find(c => c.id === selectedCandidateId.value)
    if (found) return found
  }
  return databank.value[0]
})

const filteredDatabank = computed(() => {
  const filtered = databank.value.filter(c => {
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
  return [...filtered].sort((a, b) => {
    let cmp = 0
    switch (sortColumn.value) {
      case 'rank':
      case 'sqxScore':
        cmp = a.compositeScore - b.compositeScore
        break
      case 'id':
        cmp = a.id.localeCompare(b.id)
        break
      case 'market':
        cmp = `${a.symbol}_${a.timeframe}`.localeCompare(`${b.symbol}_${b.timeframe}`)
        break
      case 'archetype':
        cmp = `${a.archetype}_${a.entryOrderType}`.localeCompare(`${b.archetype}_${b.entryOrderType}`)
        break
      case 'trades':
        cmp = a.totalTrades - b.totalTrades
        break
      case 'netProfit':
        cmp = a.netProfit - b.netProfit
        break
      case 'profitFactor':
        cmp = a.profitFactor - b.profitFactor
        break
      case 'sharpe':
        cmp = a.sharpeRatio - b.sharpeRatio
        break
      case 'maxDd':
        cmp = a.maxDrawdownPct - b.maxDrawdownPct
        break
      case 'retDd':
        cmp = a.retDdRatio - b.retDdRatio
        break
      case 'rSquared':
        cmp = a.rSquared - b.rSquared
        break
      default:
        cmp = a.compositeScore - b.compositeScore
    }
    return sortDir.value === 'asc' ? cmp : -cmp
  })
})

function applyPreset(preset: 'mgc' | 'mnq' | 'mes' | 'aapl' | 'eurusd' | 'gbpjpy') {
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
  } else if (preset === 'aapl') {
    symbol.value = 'AAPL'
    timeframe.value = 'M30'
    yearSpec.value = '2025-2026'
    archetype.value = 'MOMENTUM'
    entryOrderType.value = 'ALL'
    initialCapital.value = 10000
    lotSize.value = 1.0
    commissionPerTrade.value = 0.50
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

function applyGatePreset(level: 'relaxed' | 'standard' | 'strict') {
  if (level === 'relaxed') {
    minTrades.value = 8
    minProfitFactor.value = 1.08
    minOosProfitFactor.value = 0.95
    minSharpe.value = 0.20
    maxDrawdownPct.value = 30.0
  } else if (level === 'standard') {
    minTrades.value = 12
    minProfitFactor.value = 1.15
    minOosProfitFactor.value = 1.02
    minSharpe.value = 0.40
    maxDrawdownPct.value = 25.0
  } else if (level === 'strict') {
    minTrades.value = 20
    minProfitFactor.value = 1.30
    minOosProfitFactor.value = 1.15
    minSharpe.value = 0.80
    maxDrawdownPct.value = 15.0
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

function selectBlockPack(pack: 'ALL' | 'TREND_BREAKOUT' | 'MEAN_REV' | 'PA_VOL') {
  if (pack === 'ALL') {
    selectedBlocks.value = allBuildingBlocks.map(b => b.id)
  } else if (pack === 'TREND_BREAKOUT') {
    selectedBlocks.value = allBuildingBlocks
      .filter(b => ['Breakout', 'Trend', 'Volatility', 'Regime'].includes(b.category))
      .map(b => b.id)
  } else if (pack === 'MEAN_REV') {
    selectedBlocks.value = allBuildingBlocks
      .filter(b => ['Oscillator', 'Mean-Rev', 'Volatility'].includes(b.category))
      .map(b => b.id)
  } else if (pack === 'PA_VOL') {
    selectedBlocks.value = allBuildingBlocks
      .filter(b => ['Price Action', 'Volume', 'Structure', 'Momentum'].includes(b.category))
      .map(b => b.id)
  }
}

function formatMoney(val: number): string {
  const sign = val < 0 ? '-' : ''
  const abs = Math.abs(Math.round(val))
  return `${sign}$${abs.toLocaleString('en-US')}`
}

function formatArchetypeShort(arch: string): string {
  switch (arch) {
    case 'TREND_FOLLOWING': return 'TREND'
    case 'MEAN_REVERSION': return 'MEAN-REV'
    default: return arch
  }
}

function positionTooltip(event: MouseEvent, col: ColDefinition) {
  const target = event.currentTarget as HTMLElement
  const rect = target
    ? target.getBoundingClientRect()
    : { left: event.clientX, top: event.clientY, bottom: event.clientY }
  const tooltipWidth = 340
  const x = Math.min(window.innerWidth - tooltipWidth - 16, Math.max(16, rect.left))
  const y = rect.bottom + 185 > window.innerHeight ? Math.max(12, rect.top - 165) : rect.bottom + 8
  activeTooltip.value = { col, x, y }
}

function showBlockTooltip(event: MouseEvent, b: BuildingBlockMeta) {
  positionTooltip(event, {
    title: `${b.label} — ${b.category} Signal / Filter`,
    formula: b.formula,
    description: b.desc,
    benchmark: b.benchmark,
  })
}

function showIndicatorTooltip(event: MouseEvent, indLabel: string) {
  const upper = indLabel.toUpperCase()
  const matched = allBuildingBlocks.find(b => {
    if (upper.startsWith('DONCHIAN') && b.id === 'DONCHIAN_BREAKOUT') return true
    if (upper.includes('EMA') && upper.includes('SMA') && b.id === 'MA_CROSSOVER') return true
    if (upper.startsWith('EMA(') && b.id === 'PRICE_VS_MA') return true
    if (upper.startsWith('SUPERTREND') && b.id === 'SUPERTREND') return true
    if (upper.startsWith('BOLLINGER') && b.id === 'BOLLINGER_BANDS') return true
    if (upper.startsWith('KELTNER') && b.id === 'KELTNER_CHANNEL') return true
    if (upper.startsWith('TTMSQUEEZE') && b.id === 'BB_KC_SQUEEZE') return true
    if (upper.startsWith('RSI(') && b.id === 'RSI_FILTER') return true
    if (upper.startsWith('STOCH(') && b.id === 'STOCHASTIC_OSC') return true
    if (upper.startsWith('CCI(') && b.id === 'CCI_OSCILLATOR') return true
    if (upper.startsWith('WILL%R') && b.id === 'WILLIAMS_R') return true
    if (upper.startsWith('MACD(') && b.id === 'MACD_HISTOGRAM') return true
    if (upper.startsWith('ROC(') && b.id === 'MOMENTUM_ROC') return true
    if (upper.startsWith('VWAP(') && b.id === 'VWAP_ANCHOR') return true
    if (upper.startsWith('VOLSURGE') && b.id === 'VOLUME_SURGE') return true
    if (upper.startsWith('ENGULFING') && b.id === 'ENGULFING_PATTERN') return true
    if (upper.startsWith('INSIDEBAR') && b.id === 'INSIDE_BAR_COIL') return true
    if (upper.startsWith('LINREG') && b.id === 'LINEAR_REG_SLOPE') return true
    if (upper.startsWith('ZSCORE') && b.id === 'ZSCORE_EXTREME') return true
    if (upper.startsWith('KAUFMANER') && b.id === 'CHOPPINESS_FILTER') return true
    if (upper.startsWith('SWINGHHLL') && b.id === 'HIGHER_HIGHS_SWING') return true
    if (upper.startsWith('ADX(') && b.id === 'ADX_REGIME') return true
    if (upper.startsWith('DAILYHIGHLOW') && b.id === 'DAILY_HIGH_LOW') return true
    if (upper.startsWith('ATR(') && b.id === 'ATR_EXPANSION') return true
    return false
  })
  if (matched) {
    showBlockTooltip(event, matched)
  } else {
    positionTooltip(event, {
      title: `Indicator Building Block: ${indLabel}`,
      formula: 'ATR-Normalized Technical Building Block',
      description: 'Active technical indicator or volatility filter evolved in this strategy’s entry/exit genome.',
      benchmark: 'Used in both In-Sample training and blind Out-of-Sample validation',
    })
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
        const prevCount = databank.value.length
        databank.value = data.candidates
        if (!selectedCandidateId.value && databank.value.length > 0) {
          selectedCandidateId.value = databank.value[0].id
        }
        if (databank.value.length !== prevCount) {
          fetchCatalog(true).catch(() => {})
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
      await fetchCatalog(true).catch(() => {})
      await fetchDatabankAndStatus()
    } else {
      const err = await res.json().catch(() => ({}))
      setStatus(err.error || 'Failed to promote strategy', 'error')
    }
  } finally {
    actionBusyId.value = null
  }
}

async function openInDashboard(candidate: DatabankCandidate) {
  actionBusyId.value = candidate.id
  try {
    if (!candidate.promoted) {
      await fetch(`${controlPlaneUrl.value}/api/generator/databank/${candidate.id}/promote`, {
        method: 'POST',
      })
    }
    await fetchCatalog(true).catch(() => {})
    router.push({ path: '/dashboard', query: { strategyId: candidate.id, symbol: candidate.symbol } })
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

interface ColDefinition {
  title: string
  formula?: string
  description: string
  benchmark?: string
}

const COLUMN_DEFINITIONS: Record<string, ColDefinition> = {
  rank: {
    title: 'Rank & Quality Tier',
    formula: 'Click to Sort · Ranked by Composite Robustness Score (0–100)',
    description: 'Relative ranking of the strategy in your Databank and its institutional classification tier based on In-Sample + blind Out-of-Sample robustness.',
    benchmark: 'ELITE (≥ 82) · INSTITUTIONAL (68–81) · QUALIFIED (< 68)'
  },
  id: {
    title: 'Strategy ID & Catalog Status',
    formula: '<Symbol><Timeframe><Archetype><GenomeHash>',
    description: 'Unique deterministic identifier generated from the instrument, timeframe, trading style, and rule genome. Strategies marked "SAVED" are compiled into trading-strategies.',
    benchmark: 'Click any row to inspect its Equity Curve, Rule DNA & Java source code'
  },
  market: {
    title: 'Market & Candle Timeframe',
    formula: 'Instrument Symbol + Bar Interval',
    description: 'Target financial instrument (CME Micro/Mini Futures, Equities, or Forex pair) and bar timeframe on which the strategy signals and ATR stops were evolved.',
    benchmark: 'Matches tick size & point value in FuturesRegistry / ForexPnL'
  },
  archetype: {
    title: 'Strategy Archetype & Entry Type',
    formula: 'Signal Family + Order Execution Mode',
    description: 'Primary trading logic style (Breakout, Trend Following, Momentum, Mean Reversion, Pullback) and order type (STOP pending breakout level vs. MARKET on bar close).',
    benchmark: 'STOP entries confirm momentum expansion; MARKET entries capture immediate closes'
  },
  trades: {
    title: 'Total Trades (In-Sample / Out-of-Sample)',
    formula: 'Total Closed Trades (IS Trades / Blind OOS Trades)',
    description: 'Total number of completed round-turn trades across the full historical window, split between the training In-Sample (IS) period and unseen Out-of-Sample (OOS) validation period.',
    benchmark: 'Target: ≥ 30 total trades (≥ 10 in OOS) for statistical significance'
  },
  netProfit: {
    title: 'Net Profit (In-Sample / Out-of-Sample)',
    formula: 'Gross Profit − Gross Loss − Commissions − Slippage',
    description: 'Realized net dollar profit after deducting realistic per-trade commissions and fill slippage, shown for the full period and broken down into (IS / OOS) profit.',
    benchmark: 'Both In-Sample (IS) and Out-of-Sample (OOS) net profit should be positive'
  },
  profitFactor: {
    title: 'Profit Factor (In-Sample / Out-of-Sample)',
    formula: 'Gross Winning Trades ($) ÷ |Gross Losing Trades ($)|',
    description: 'Dollars won for every $1.00 lost. The parentheses show (In-Sample PF / Out-of-Sample PF) to detect curve-fitting—true edge retains a strong PF in unseen OOS data.',
    benchmark: 'Good: > 1.25 · Institutional: > 1.45 · OOS PF should stay > 1.10'
  },
  sharpe: {
    title: 'Annualized Sharpe Ratio',
    formula: '(Mean Return − Risk-Free Rate) ÷ Return Volatility × √Periods',
    description: 'Risk-adjusted return per unit of return volatility across the backtest window. Higher values indicate smoother, more consistent returns relative to equity fluctuations.',
    benchmark: 'Acceptable: > 0.60 · Strong: > 1.00 · Institutional: > 1.50'
  },
  maxDd: {
    title: 'Maximum Peak-to-Valley Drawdown (%)',
    formula: 'Max((Peak Equity − Trough Equity) ÷ Peak Equity) × 100',
    description: 'Largest percentage drop in account equity from a historical equity peak to a subsequent trough before a new high is reached.',
    benchmark: 'Target: < 20% for aggressive models · < 12% for prop/institutional desks'
  },
  retDd: {
    title: 'Return / Drawdown Ratio (Calmar-Style)',
    formula: 'Total Return (%) ÷ Max Drawdown (%)',
    description: 'Measures how many times the strategy’s cumulative percentage return exceeds its worst historical percentage drawdown.',
    benchmark: 'Good: > 1.50 · Strong: > 2.50 · Elite: > 4.00'
  },
  rSquared: {
    title: 'Equity Curve Stability (R²)',
    formula: 'Linear Regression Coefficient of Determination (0.00 to 1.00)',
    description: 'Measures how closely the strategy’s cumulative equity curve follows a straight upward line. A value near 1.00 means steady linear growth; near 0.00 means erratic or flat performance.',
    benchmark: 'Good: > 0.65 · Institutional: > 0.80 · Elite: > 0.90'
  },
  sqxScore: {
    title: 'Composite Robustness Score (0–100)',
    formula: '28% PF + 24% OOS PF + 18% Sharpe + 15% R² + 15% Low-DD',
    description: 'Multi-factor institutional fitness score combining full-period Profit Factor, unseen Out-of-Sample Profit Factor, Sharpe Ratio, Equity R² linearity, and Drawdown control.',
    benchmark: '68+ Institutional · 82+ Elite Strategy'
  },
  actions: {
    title: 'Quick Strategy Actions',
    formula: 'Save to Catalog · Full Backtest Chart · Delete',
    description: '"+ Catalog / ✓ Saved" writes the standalone Java class to trading-strategies. "📈 Chart" runs a full backtest and opens the interactive TradingView candlestick & trade chart.',
    benchmark: 'Promoted strategies remain in your Strategy Catalog permanently'
  },
  mcVar95: {
    title: 'Monte Carlo 95% Value at Risk (VaR)',
    formula: '95th Percentile Worst Drawdown Across 80 Trade-Order Shuffles ($)',
    description: 'Estimates the worst-case dollar drawdown at the 95% confidence level when the historical sequence of trades is randomly reshuffled 80 times.',
    benchmark: 'Should remain well below your maximum account risk tolerance'
  },
  mcLossRisk: {
    title: 'Monte Carlo Probability of Net Loss (%)',
    formula: '% of Simulated Equity Paths Ending Below Initial Capital',
    description: 'Percentage of randomized Monte Carlo trade-shuffle simulations that finished with a net loss.',
    benchmark: 'Target: 0.0% to 5.0% for robust strategies'
  },
  databankInfo: {
    title: 'Strategy Databank (Validated Candidates)',
    formula: 'Persistent Repository of Strategies Passing IS + OOS Filters',
    description: 'Stores all discovered strategies that passed both your In-Sample training thresholds and blind Out-of-Sample validation gate. Every strategy here is automatically available in your Strategy Catalog.',
    benchmark: 'Click any column header to sort · Click any row to inspect'
  },
  tierElite: {
    title: 'Elite Tier (Score ≥ 82)',
    formula: 'Composite Robustness Score ≥ 82.0',
    description: 'Top-tier strategies combining high Profit Factor in both In-Sample and unseen Out-of-Sample windows, strong Sharpe ratio, linear Equity R², and tight drawdown control.',
    benchmark: 'Ready for Walk-Forward Analysis & Paper/Live deployment'
  },
  tierInst: {
    title: 'Institutional Tier (Score 68 – 81.9)',
    formula: '68.0 ≤ Composite Robustness Score < 82.0',
    description: 'Robust multi-regime strategies with consistent Out-of-Sample generalization and favorable Return-to-Drawdown characteristics.',
    benchmark: 'Strong candidates for portfolio diversification'
  },
  tierQual: {
    title: 'Qualified Tier (Score < 68)',
    formula: 'Passed All Acceptance Gate Thresholds (Score < 68.0)',
    description: 'Strategies that passed every minimum filter in your Acceptance Gate (Trades, IS/OOS Profit Factor, Sharpe, Max DD) with moderate composite scores.',
    benchmark: 'Useful as baseline models or for further Walk-Forward refinement'
  },
  blocksInfo: {
    title: 'Active Signal & Filter Building Blocks (24 Blocks)',
    formula: 'Entry Signal Trigger (Slot 1) AND Regime / Confirmation Filter (Slot 2)',
    description: 'Select which technical indicators, price-action patterns, volume filters, and regime detectors the Genetic Engine can combine and mutate when evolving strategies.',
    benchmark: 'Hover any building block card below for its mathematical formula & regime guide'
  },
  packAll: {
    title: 'All 24 Signal & Filter Building Blocks',
    formula: 'Breakout + Trend + Volatility + Volume + Oscillators + Price Action + Regime',
    description: 'Enables the full 24-block institutional library so genetic evolution can discover multi-factor combinations across every indicator family.',
    benchmark: 'Recommended when Strategy Archetype is set to "All Styles"'
  },
  packTrend: {
    title: 'Trend, Breakout & Volatility Regime Pack',
    formula: 'Donchian, Keltner, TTM Squeeze, SuperTrend, EMA/SMA, LinReg, ADX, Kaufman ER',
    description: 'Focuses evolution on directional breakout channels, moving average alignment, and choppiness/volatility filters.',
    benchmark: 'Ideal for Gold (MGC/GC), Nasdaq (MNQ), and GBP/JPY breakouts'
  },
  packMeanRev: {
    title: 'Mean Reversion, Statistical Z-Score & Oscillators Pack',
    formula: 'RSI, Stochastic %K, CCI, Williams %R, Z-Score, Bollinger Bands',
    description: 'Focuses evolution on overbought/oversold exhaustion, statistical standard-deviation extremes, and snapback entries.',
    benchmark: 'Ideal for S&P 500 (MES), EUR/USD, and range-bound regimes'
  },
  packPaVol: {
    title: 'Price Action, Candlestick, Volume & Market Structure Pack',
    formula: 'Engulfing Bar, Inside Bar Coil, Rolling VWAP, Volume Surge, Swing HH/HL, MACD',
    description: 'Combines pure price-action candlestick triggers and multi-pivot swing structure with institutional VWAP and volume participation.',
    benchmark: 'Ideal for intraday M15 / M30 / H1 futures & equities'
  },
  cfgSearchEngine: {
    title: 'Evolutionary Search Engine Mode',
    formula: 'Genetic Evolution (Tournament + Crossover + Mutation) vs. Random Monte-Carlo',
    description: 'Genetic Evolution (GA) breeds the highest-scoring strategies across generations via elitism, rule crossover, and parameter mutation. Random Mining samples independent genomes.',
    benchmark: 'Genetic Evolution (GA) converges 3–5× faster on high-OOS strategies'
  },
  cfgArchetype: {
    title: 'Strategy Archetype (Trading Logic Family)',
    formula: 'Breakout · Trend Following · Momentum · Pullback · Mean Reversion',
    description: 'Biases the primary signal slot toward a specific trading style while still allowing secondary confirmation filters from your active building blocks.',
    benchmark: 'Choose "All Styles" to let the engine compete all 5 styles head-to-head'
  },
  cfgEntryType: {
    title: 'Order Execution Mode (Pending Stop vs. Market)',
    formula: 'STOP: Highest/Lowest(N) ± k×ATR (Valid M bars) | MARKET: Fill at Next Open',
    description: 'Stop Pending Breakout places a resting stop order beyond recent structure that only triggers if price confirms momentum. Market executes immediately on the signal bar close.',
    benchmark: 'STOP entries excel at breakouts; MARKET entries excel at mean reversion'
  },
  cfgDirection: {
    title: 'Trading Direction Symmetry',
    formula: 'BOTH (Mirror Long & Short Rules) · LONG_ONLY · SHORT_ONLY',
    description: 'Controls whether generated strategies trade both Long and Short using symmetric opposite rules, or specialize exclusively on one side of the market.',
    benchmark: 'Symmetric Long & Short prevents secular bull/bear bias overfitting'
  },
  cfgPopSize: {
    title: 'Population Size (Genomes per Generation)',
    formula: 'Industry Best Practice: 40–100 (Wide Diversity > Many Generations)',
    description: 'Number of distinct strategy genomes evaluated in parallel inside each generation. Quantitative research shows a wider Population Size (e.g. 50–100) prevents premature convergence onto a single local maximum.',
    benchmark: 'Quick Scan: 24–30 · Institutional Standard: 50–60 · Deep Discovery: 80–100'
  },
  cfgGenerations: {
    title: 'Evolutionary Generations (Breeding Cycles)',
    formula: 'Industry Best Practice: 8–15 Generations (Avoid Over-Evolution > 25)',
    description: 'Number of tournament-selection, crossover, and mutation cycles. Stopping between 8 and 15 generations captures genuine structural edges before the Genetic Algorithm begins curve-fitting noise in the In-Sample window.',
    benchmark: 'Quick Scan: 5 · Institutional Standard: 10 · Deep Discovery: 15 (Max ~20)'
  },
  gateMinTrades: {
    title: 'Acceptance Filter: Minimum Total Trades',
    formula: 'Total Closed Trades (IS + OOS) ≥ Threshold',
    description: 'Rejects any candidate strategy that does not generate enough round-turn trades across the historical window to be statistically meaningful.',
    benchmark: 'Recommended: 12–25 for single-year windows · 30–60 for multi-year windows'
  },
  gateMinPf: {
    title: 'Acceptance Filter: Minimum Overall Profit Factor',
    formula: 'Gross Profit ÷ |Gross Loss| ≥ Threshold (Full Dataset)',
    description: 'Minimum required ratio of gross winning dollars to gross losing dollars across the combined In-Sample + Out-of-Sample backtest.',
    benchmark: 'Recommended: 1.15 to 1.35'
  },
  gateMinOosPf: {
    title: 'Acceptance Filter: Minimum Blind Out-of-Sample Profit Factor',
    formula: 'OOS Gross Profit ÷ |OOS Gross Loss| ≥ Threshold (Unseen Data)',
    description: 'Crucial anti-overfitting gate: requires the strategy to remain profitable on the unseen Out-of-Sample validation partition that was never used for rule selection.',
    benchmark: 'Recommended: ≥ 1.02 to 1.15'
  },
  gateMinSharpe: {
    title: 'Acceptance Filter: Minimum Annualized Sharpe Ratio',
    formula: 'Annualized Risk-Adjusted Return ≥ Threshold',
    description: 'Filters out erratic strategies with high return variance, keeping only candidates with smooth risk-adjusted equity growth.',
    benchmark: 'Recommended: 0.40 to 1.00'
  },
  gateMaxDd: {
    title: 'Acceptance Filter: Maximum Allowed Drawdown (%)',
    formula: 'Max Peak-to-Valley Equity Drop (%) ≤ Threshold',
    description: 'Hard risk ceiling: immediately discards any candidate whose worst historical peak-to-trough equity drawdown exceeds this percentage.',
    benchmark: 'Recommended: 15% to 25%'
  },
  gateCommission: {
    title: 'Transaction Cost Filter: Commission ($ / Trade)',
    formula: 'Deducted on Every Entry & Exit Fill During Evolution',
    description: 'Bakes realistic per-trade brokerage/exchange commissions directly into every genetic evaluation so high-churn noise strategies are naturally eliminated.',
    benchmark: 'Micro Futures (MGC/MNQ/MES): ~$0.62 · Standard Futures: ~$2.10'
  },
  ruleLongSignal: {
    title: 'Long Entry Signal DNA (Boolean Conjunction)',
    formula: 'Rule 1 (Primary Trigger) AND Rule 2 (Regime / Confirmation Filter)',
    description: 'All conditions in the Long Signal expression must evaluate to TRUE on the completed bar before a BUY Market order or BUY Stop breakout order is armed.',
    benchmark: 'Evaluated on bar close to guarantee zero intra-bar lookahead bias'
  },
  ruleShortSignal: {
    title: 'Short Entry Signal DNA (Symmetric Mirror)',
    formula: 'Mirror Opposite of Long Signal Rules & Thresholds',
    description: 'All conditions in the Short Signal expression must evaluate to TRUE on the completed bar before a SELL Market order or SELL Stop breakout order is armed.',
    benchmark: 'Symmetric parameters prevent directional curve-fitting'
  },
  ruleEntryMethod: {
    title: 'Order Execution & Pending Stop Validity',
    formula: 'Market on Bar Close OR Pending Stop at Highest/Lowest ± ATR',
    description: 'Specifies how a triggered signal enters the market. Pending Stop orders remain active for N bars and automatically expire if price does not break the trigger level.',
    benchmark: 'Executed with realistic fill slippage in BacktestEngine'
  },
  ruleExitManagement: {
    title: 'ATR-Normalized Risk & Exit Management',
    formula: 'Initial SL + Profit Target + Trailing ATR Stop + Max Bars Time Exit',
    description: 'Every trade is protected by a volatility-scaled Stop-Loss and Profit-Target (multiples of ATR), optional dynamic ATR trailing stop, and a maximum holding-time exit (.asCloseOnly()).',
    benchmark: 'Strictly 1 position at a time — no martingale or position stacking'
  }
}

const activeTooltip = ref<{
  col: ColDefinition
  x: number
  y: number
} | null>(null)

function showTooltip(event: MouseEvent, key: string) {
  const def = COLUMN_DEFINITIONS[key]
  if (!def) return
  positionTooltip(event, def)
}

function hideTooltip() {
  activeTooltip.value = null
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
    <!-- Top Studio Header & Market Presets -->
    <header class="page-header">
      <div class="brand-group">
        <div class="title-row">
          <h1>Strategy Generator</h1>
          <span class="engine-badge">GENETIC EVOLUTION ENGINE</span>
          <span class="active-summary-pill">
            <strong>{{ symbol }}</strong> · {{ timeframe }} · {{ 100 - oosSplitPct }}/{{ oosSplitPct }} IS/OOS · {{ selectedBlocks.length }} Blocks
          </span>
        </div>
        <p class="subtitle">
          Evolutionary multi-asset strategy discovery with In-Sample / Out-of-Sample partitioning, ATR risk normalization &amp; 1-click Java codegen
        </p>
      </div>

      <div class="header-controls">
        <div class="preset-bar">
          <span class="preset-lbl">Presets:</span>
          <button class="preset-btn gold" @click="applyPreset('mgc')">⚡ Gold MGC</button>
          <button class="preset-btn blue" @click="applyPreset('mnq')">📈 Nasdaq MNQ</button>
          <button class="preset-btn emerald" @click="applyPreset('mes')">🏛️ S&amp;P MES</button>
          <button class="preset-btn cyan" @click="applyPreset('aapl')">🍎 AAPL M30</button>
          <button class="preset-btn purple" @click="applyPreset('eurusd')">💱 EUR/USD</button>
          <button class="preset-btn amber" @click="applyPreset('gbpjpy')">🔥 GBP/JPY</button>
        </div>

        <div class="view-toggles">
          <button
            class="layout-btn"
            :title="workspaceLayout === 'split' ? 'Switch to Full-Width Stacked Layout' : 'Switch to Side-by-Side Split Inspector'"
            @click="workspaceLayout = workspaceLayout === 'split' ? 'stacked' : 'split'"
          >
            {{ workspaceLayout === 'split' ? '◫ Split View' : '▭ Stacked View' }}
          </button>
          <button class="toggle-cfg-btn" @click="showConfigPanel = !showConfigPanel">
            {{ showConfigPanel ? '▲ Hide Studio' : '▼ Configure Studio' }}
          </button>
        </div>
      </div>
    </header>

    <!-- Studio Builder Panel -->
    <div v-if="showConfigPanel" class="studio-builder">
      <!-- Row 1: 3 Clean Configuration Cards -->
      <div class="config-grid">
        <!-- Card 1: Market, Timeframe & IS/OOS Partition -->
        <section class="cfg-card">
          <div class="cfg-card-header">
            <div class="step-title">
              <span class="step-num">1</span>
              <h3>Market &amp; Data Partition</h3>
            </div>
            <span class="card-tag">{{ symbol }} · {{ timeframe }}</span>
          </div>

          <div class="form-grid-2">
            <div class="field">
              <label>Instrument / Symbol</label>
              <select v-model="symbol" :disabled="status.running">
                <option v-for="sym in instruments" :key="sym" :value="sym">{{ sym }}</option>
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
          </div>

          <div class="field">
            <label>Candle Timeframe</label>
            <div class="seg-group">
              <button
                v-for="tf in timeframes"
                :key="tf.id"
                type="button"
                :class="['seg-btn', { active: timeframe === tf.id }]"
                :disabled="status.running"
                @click="timeframe = tf.id"
              >
                {{ tf.label }}
              </button>
            </div>
          </div>

          <div class="field">
            <div class="split-lbl-row">
              <label>In-Sample / Out-of-Sample Split</label>
              <div class="split-pills-mini">
                <button type="button" :class="['mini-opt', { active: oosSplitPct === 20 }]" :disabled="status.running" @click="oosSplitPct = 20">80 / 20</button>
                <button type="button" :class="['mini-opt', { active: oosSplitPct === 30 }]" :disabled="status.running" @click="oosSplitPct = 30">70 / 30 Std</button>
                <button type="button" :class="['mini-opt', { active: oosSplitPct === 40 }]" :disabled="status.running" @click="oosSplitPct = 40">60 / 40 Strict</button>
              </div>
            </div>
            <div class="visual-split-bar">
              <div class="is-segment" :style="{ width: `${100 - oosSplitPct}%` }">
                {{ 100 - oosSplitPct }}% In-Sample (Train)
              </div>
              <div class="oos-segment" :style="{ width: `${oosSplitPct}%` }">
                {{ oosSplitPct }}% Blind OOS
              </div>
            </div>
          </div>

          <div class="form-grid-2">
            <div class="field">
              <label>Initial Capital ($)</label>
              <input type="number" v-model.number="initialCapital" step="1000" :disabled="status.running" />
            </div>
            <div class="field">
              <label>Order Size (Lots / Qty)</label>
              <input type="number" v-model.number="lotSize" step="0.01" :disabled="status.running" />
            </div>
          </div>
        </section>

        <!-- Card 2: Evolution Engine & Architecture -->
        <section class="cfg-card">
          <div class="cfg-card-header">
            <div class="step-title">
              <span class="step-num">2</span>
              <h3>Evolution Engine &amp; Architecture</h3>
            </div>
            <span class="card-tag amber">{{ populationSize * generations }} Candidates</span>
          </div>

          <div class="field" @mouseenter="showTooltip($event, 'cfgSearchEngine')" @mouseleave="hideTooltip">
            <label class="hover-lbl">Search Engine Mode</label>
            <div class="seg-group">
              <button
                type="button"
                :class="['seg-btn', { active: searchMode === 'GENETIC' }]"
                :disabled="status.running"
                @click="searchMode = 'GENETIC'"
              >
                🧬 Genetic Evolution (GA)
              </button>
              <button
                type="button"
                :class="['seg-btn', { active: searchMode === 'RANDOM' }]"
                :disabled="status.running"
                @click="searchMode = 'RANDOM'"
              >
                🎲 Monte-Carlo Mining
              </button>
            </div>
          </div>

          <div class="field" @mouseenter="showTooltip($event, 'cfgArchetype')" @mouseleave="hideTooltip">
            <label class="hover-lbl">Strategy Archetype Family</label>
            <div class="seg-group wrap">
              <button
                v-for="arch in archetypeOptions"
                :key="arch.id"
                type="button"
                :class="['seg-btn', { active: archetype === arch.id }]"
                :disabled="status.running"
                @click="archetype = arch.id"
              >
                {{ arch.label }}
              </button>
            </div>
          </div>

          <div class="form-grid-2">
            <div class="field" @mouseenter="showTooltip($event, 'cfgEntryType')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Order Execution</label>
              <select v-model="entryOrderType" :disabled="status.running">
                <option value="ALL">Stop Breakout + Market</option>
                <option value="STOP">Stop Pending Breakout</option>
                <option value="MARKET">Market on Bar Close</option>
              </select>
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'cfgDirection')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Trade Direction</label>
              <select v-model="direction" :disabled="status.running">
                <option value="BOTH">Long &amp; Short (Symmetric)</option>
                <option value="LONG_ONLY">Long Only</option>
                <option value="SHORT_ONLY">Short Only</option>
              </select>
            </div>
          </div>

          <div class="form-grid-2">
            <div class="field" @mouseenter="showTooltip($event, 'cfgPopSize')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Population Size</label>
              <input type="number" v-model.number="populationSize" min="10" max="200" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'cfgGenerations')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Generations</label>
              <input type="number" v-model.number="generations" min="1" max="50" :disabled="status.running" />
            </div>
          </div>

          <div class="ga-preset-strip">
            <span class="ga-preset-lbl">GA Depth:</span>
            <button
              type="button"
              :class="['mini-opt', { active: populationSize === 24 && generations === 5 }]"
              :disabled="status.running"
              @mouseenter="showTooltip($event, 'cfgPopSize')"
              @mouseleave="hideTooltip"
              @click="populationSize = 24; generations = 5"
            >
              ⚡ Fast (24×5)
            </button>
            <button
              type="button"
              :class="['mini-opt', { active: populationSize === 50 && generations === 10 }]"
              :disabled="status.running"
              @mouseenter="showTooltip($event, 'cfgPopSize')"
              @mouseleave="hideTooltip"
              @click="populationSize = 50; generations = 10"
            >
              🎯 Institutional (50×10)
            </button>
            <button
              type="button"
              :class="['mini-opt', { active: populationSize === 80 && generations === 15 }]"
              :disabled="status.running"
              @mouseenter="showTooltip($event, 'cfgGenerations')"
              @mouseleave="hideTooltip"
              @click="populationSize = 80; generations = 15"
            >
              🔬 Deep (80×15)
            </button>
          </div>
        </section>

        <!-- Card 3: Institutional Acceptance Gate -->
        <section class="cfg-card">
          <div class="cfg-card-header">
            <div class="step-title">
              <span class="step-num">3</span>
              <h3>Acceptance Gate Filters</h3>
            </div>
            <div class="split-pills-mini">
              <button type="button" class="mini-opt" :disabled="status.running" @click="applyGatePreset('relaxed')">Relaxed</button>
              <button type="button" class="mini-opt" :disabled="status.running" @click="applyGatePreset('standard')">Standard</button>
              <button type="button" class="mini-opt" :disabled="status.running" @click="applyGatePreset('strict')">Strict</button>
            </div>
          </div>

          <div class="form-grid-2">
            <div class="field" @mouseenter="showTooltip($event, 'gateMinTrades')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Min Total Trades</label>
              <input type="number" v-model.number="minTrades" min="1" max="500" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'gateMinPf')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Min Overall PF</label>
              <input type="number" v-model.number="minProfitFactor" step="0.05" min="0.5" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'gateMinOosPf')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Min Blind OOS PF</label>
              <input type="number" v-model.number="minOosProfitFactor" step="0.05" min="0.5" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'gateMinSharpe')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Min Sharpe Ratio</label>
              <input type="number" v-model.number="minSharpe" step="0.1" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'gateMaxDd')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Max Drawdown (%)</label>
              <input type="number" v-model.number="maxDrawdownPct" step="1" min="2" max="80" :disabled="status.running" />
            </div>
            <div class="field" @mouseenter="showTooltip($event, 'gateCommission')" @mouseleave="hideTooltip">
              <label class="hover-lbl">Commission ($ / Trade)</label>
              <input type="number" v-model.number="commissionPerTrade" step="0.05" min="0" :disabled="status.running" />
            </div>
          </div>

          <div class="gate-summary-box">
            <div><strong>Validation Pipeline:</strong> In-Sample Train → Blind OOS Gate → 80-Run Monte Carlo</div>
          </div>
        </section>
      </div>

      <!-- Row 2: Dedicated Signal & Filter Building Block Studio Matrix -->
      <section class="blocks-studio-card">
        <div class="blocks-studio-header">
          <div class="blocks-studio-left" @mouseenter="showTooltip($event, 'blocksInfo')" @mouseleave="hideTooltip">
            <span class="step-num">4</span>
            <div>
              <h3 class="hover-lbl">Signal &amp; Filter Building Block Library</h3>
              <span class="blocks-sub-count">{{ selectedBlocks.length }} of {{ allBuildingBlocks.length }} institutional signals &amp; regime filters active — hover any block for mathematical formula &amp; regime guide</span>
            </div>
          </div>

          <div class="blocks-studio-actions">
            <div class="blocks-pack-btns">
              <button type="button" class="pack-btn" :disabled="status.running" @mouseenter="showTooltip($event, 'packAll')" @mouseleave="hideTooltip" @click="selectBlockPack('ALL')">✓ All (24)</button>
              <button type="button" class="pack-btn" :disabled="status.running" @mouseenter="showTooltip($event, 'packTrend')" @mouseleave="hideTooltip" @click="selectBlockPack('TREND_BREAKOUT')">Trend &amp; Breakout</button>
              <button type="button" class="pack-btn" :disabled="status.running" @mouseenter="showTooltip($event, 'packMeanRev')" @mouseleave="hideTooltip" @click="selectBlockPack('MEAN_REV')">Mean-Rev &amp; Osc</button>
              <button type="button" class="pack-btn" :disabled="status.running" @mouseenter="showTooltip($event, 'packPaVol')" @mouseleave="hideTooltip" @click="selectBlockPack('PA_VOL')">Price Action &amp; Vol</button>
            </div>
            <button type="button" class="btn-collapse-blocks" @click="showBlocksDrawer = !showBlocksDrawer">
              {{ showBlocksDrawer ? '▲ Collapse Library' : '▼ Expand 24 Signals & Filters' }}
            </button>
          </div>
        </div>

        <template v-if="showBlocksDrawer">
          <!-- Category Filter Tabs -->
          <div class="cat-filter-strip">
            <button
              v-for="cat in blockCategories"
              :key="cat"
              type="button"
              :class="['cat-tab', { active: activeBlockCategory === cat }]"
              @click="activeBlockCategory = cat"
            >
              {{ cat === 'ALL' ? `All Categories (${allBuildingBlocks.length})` : cat }}
            </button>
          </div>

          <!-- Clean 4-Column Signal & Filter Cards -->
          <div class="blocks-matrix-grid">
            <button
              v-for="b in visibleBuildingBlocks"
              :key="b.id"
              type="button"
              :class="['signal-block-card', { active: selectedBlocks.includes(b.id) }]"
              :disabled="status.running"
              @mouseenter="showBlockTooltip($event, b)"
              @mouseleave="hideTooltip"
              @click="toggleBlock(b.id)"
            >
              <div class="sbc-top">
                <span class="sbc-check">{{ selectedBlocks.includes(b.id) ? '✓' : '' }}</span>
                <span class="sbc-name">{{ b.label }}</span>
                <span class="sbc-cat">{{ b.category }}</span>
              </div>
              <div class="sbc-formula">{{ b.formula }}</div>
            </button>
          </div>
        </template>
      </section>
    </div>

    <!-- Live Execution & Telemetry Command Bar -->
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

    <!-- Master-Detail Workspace: Strategy Databank + Live Side-by-Side Inspector -->
    <div :class="['databank-workspace', workspaceLayout === 'split' && selectedCandidate ? 'split-mode' : 'stacked-mode']">
      <!-- Left / Top Pane: Strategy Databank Table -->
      <section class="databank-card">
        <div class="databank-header">
          <div class="db-title-group" @mouseenter="showTooltip($event, 'databankInfo')" @mouseleave="hideTooltip">
            <h2 class="hover-lbl">Strategy Databank</h2>
            <span class="db-count">{{ filteredDatabank.length }} strategies</span>
          </div>
          <div class="db-controls">
            <div class="tier-pills">
              <button :class="['tier-pill', { active: tierFilter === 'ALL' }]" @mouseenter="showTooltip($event, 'databankInfo')" @mouseleave="hideTooltip" @click="tierFilter = 'ALL'">All</button>
              <button :class="['tier-pill elite', { active: tierFilter === 'ELITE' }]" @mouseenter="showTooltip($event, 'tierElite')" @mouseleave="hideTooltip" @click="tierFilter = 'ELITE'">Elite (82+)</button>
              <button :class="['tier-pill inst', { active: tierFilter === 'INSTITUTIONAL' }]" @mouseenter="showTooltip($event, 'tierInst')" @mouseleave="hideTooltip" @click="tierFilter = 'INSTITUTIONAL'">Institutional</button>
              <button :class="['tier-pill qual', { active: tierFilter === 'QUALIFIED' }]" @mouseenter="showTooltip($event, 'tierQual')" @mouseleave="hideTooltip" @click="tierFilter = 'QUALIFIED'">Qualified</button>
            </div>
            <input
              v-model="searchQuery"
              type="text"
              placeholder="Search ID, symbol, indicator..."
              class="db-search"
            />
            <button
              v-if="databank.length > 0"
              class="btn-clear-db"
              @click="clearAllCandidates"
            >
              Clear
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
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'rank' }]" @click="toggleSort('rank')" @mouseenter="showTooltip($event, 'rank')" @mouseleave="hideTooltip">
                  <span class="th-label">Rank <span class="sort-arrow">{{ sortArrow('rank') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'id' }]" @click="toggleSort('id')" @mouseenter="showTooltip($event, 'id')" @mouseleave="hideTooltip">
                  <span class="th-label">Strategy <span class="sort-arrow">{{ sortArrow('id') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'market' }]" @click="toggleSort('market')" @mouseenter="showTooltip($event, 'market')" @mouseleave="hideTooltip">
                  <span class="th-label">Market <span class="sort-arrow">{{ sortArrow('market') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'archetype' }]" @click="toggleSort('archetype')" @mouseenter="showTooltip($event, 'archetype')" @mouseleave="hideTooltip">
                  <span class="th-label">Style <span class="sort-arrow">{{ sortArrow('archetype') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'trades' }]" @click="toggleSort('trades')" @mouseenter="showTooltip($event, 'trades')" @mouseleave="hideTooltip">
                  <span class="th-label">Trades <span class="sort-arrow">{{ sortArrow('trades') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'netProfit' }]" @click="toggleSort('netProfit')" @mouseenter="showTooltip($event, 'netProfit')" @mouseleave="hideTooltip">
                  <span class="th-label">Net Profit <span class="sort-arrow">{{ sortArrow('netProfit') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'profitFactor' }]" @click="toggleSort('profitFactor')" @mouseenter="showTooltip($event, 'profitFactor')" @mouseleave="hideTooltip">
                  <span class="th-label">PF (IS/OOS) <span class="sort-arrow">{{ sortArrow('profitFactor') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'sharpe' }]" @click="toggleSort('sharpe')" @mouseenter="showTooltip($event, 'sharpe')" @mouseleave="hideTooltip">
                  <span class="th-label">Sharpe <span class="sort-arrow">{{ sortArrow('sharpe') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'maxDd' }]" @click="toggleSort('maxDd')" @mouseenter="showTooltip($event, 'maxDd')" @mouseleave="hideTooltip">
                  <span class="th-label">Max DD <span class="sort-arrow">{{ sortArrow('maxDd') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'retDd' }]" @click="toggleSort('retDd')" @mouseenter="showTooltip($event, 'retDd')" @mouseleave="hideTooltip">
                  <span class="th-label">Ret/DD <span class="sort-arrow">{{ sortArrow('retDd') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'rSquared' }]" @click="toggleSort('rSquared')" @mouseenter="showTooltip($event, 'rSquared')" @mouseleave="hideTooltip">
                  <span class="th-label">R² <span class="sort-arrow">{{ sortArrow('rSquared') }}</span></span>
                </th>
                <th :class="['th-sortable', { 'th-sorted': sortColumn === 'sqxScore' }]" @click="toggleSort('sqxScore')" @mouseenter="showTooltip($event, 'sqxScore')" @mouseleave="hideTooltip">
                  <span class="th-label">Score <span class="sort-arrow">{{ sortArrow('sqxScore') }}</span></span>
                </th>
                <th style="text-align: right;" @mouseenter="showTooltip($event, 'actions')" @mouseleave="hideTooltip">
                  <span class="th-label" style="justify-content: flex-end;">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(c, idx) in filteredDatabank"
                :key="c.id"
                :class="{ selected: selectedCandidate?.id === c.id }"
                @click="selectedCandidateId = c.id"
              >
                <td @mouseenter="showTooltip($event, 'rank')" @mouseleave="hideTooltip">
                  <span :class="['tier-badge', c.tier.toLowerCase()]">#{{ idx + 1 }} {{ c.tier }}</span>
                </td>
                <td class="font-mono font-bold text-white" @mouseenter="showTooltip($event, 'id')" @mouseleave="hideTooltip">
                  <div class="strat-id-cell">
                    <span>{{ c.id }}</span>
                    <span v-if="c.promoted" class="promoted-dot" title="Saved in Strategy Catalog">✓</span>
                  </div>
                </td>
                <td @mouseenter="showTooltip($event, 'market')" @mouseleave="hideTooltip">
                  <span class="sym-badge">{{ c.symbol }}</span>
                  <span class="tf-badge">{{ c.timeframe }}</span>
                </td>
                <td @mouseenter="showTooltip($event, 'archetype')" @mouseleave="hideTooltip">
                  <span class="arch-text">{{ formatArchetypeShort(c.archetype) }}</span>
                  <span class="entry-sub">{{ c.entryOrderType }}</span>
                </td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'trades')" @mouseleave="hideTooltip">
                  <strong>{{ c.totalTrades }}</strong>
                  <span class="sub-split">{{ c.isTrades }}/{{ c.oosTrades }}</span>
                </td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'netProfit')" @mouseleave="hideTooltip">
                  <div class="metric-stack">
                    <span :class="c.netProfit >= 0 ? 'profit' : 'loss'">{{ formatMoney(c.netProfit) }}</span>
                    <span class="sub-split">{{ formatMoney(c.isNetProfit) }} / {{ formatMoney(c.oosNetProfit) }}</span>
                  </div>
                </td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'profitFactor')" @mouseleave="hideTooltip">
                  <div class="metric-stack">
                    <strong :class="c.profitFactor >= 1.3 ? 'profit' : ''">{{ Math.min(99.9, c.profitFactor).toFixed(2) }}</strong>
                    <span class="sub-split">{{ Math.min(99.9, c.isProfitFactor).toFixed(2) }} / {{ Math.min(99.9, c.oosProfitFactor).toFixed(2) }}</span>
                  </div>
                </td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'sharpe')" @mouseleave="hideTooltip">{{ c.sharpeRatio.toFixed(2) }}</td>
                <td class="font-mono loss" @mouseenter="showTooltip($event, 'maxDd')" @mouseleave="hideTooltip">{{ c.maxDrawdownPct.toFixed(1) }}%</td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'retDd')" @mouseleave="hideTooltip">{{ c.retDdRatio.toFixed(2) }}</td>
                <td class="font-mono" @mouseenter="showTooltip($event, 'rSquared')" @mouseleave="hideTooltip">{{ c.rSquared.toFixed(2) }}</td>
                <td @mouseenter="showTooltip($event, 'sqxScore')" @mouseleave="hideTooltip">
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
                    {{ c.promoted ? '✓ Saved' : '+ Save' }}
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

      <!-- Right / Bottom Pane: Live Strategy DNA & Equity Inspector -->
      <section v-if="selectedCandidate" class="inspector-card">
        <div class="inspector-header">
          <div>
            <div class="insp-title-row">
              <h3>{{ selectedCandidate.id }}</h3>
              <span :class="['tier-badge', selectedCandidate.tier.toLowerCase()]">{{ selectedCandidate.tier }}</span>
              <span v-if="selectedCandidate.promoted" class="promoted-pill">IN CATALOG</span>
            </div>
            <p class="insp-sub">
              <strong>{{ selectedCandidate.symbol }}</strong> · {{ selectedCandidate.timeframe }} ({{ selectedCandidate.yearSpec }}) ·
              Generation #{{ selectedCandidate.generationFound }}
            </p>
          </div>
          <div class="insp-actions">
            <button
              class="btn-promote-lg"
              :disabled="actionBusyId === selectedCandidate.id"
              @click="promoteCandidate(selectedCandidate)"
            >
              {{ selectedCandidate.promoted ? '✓ Saved' : '💾 Save to Catalog' }}
            </button>
            <button
              class="btn-promote-lg secondary"
              :disabled="actionBusyId === selectedCandidate.id"
              @click="openInDashboard(selectedCandidate)"
            >
              ⚡ Dashboard
            </button>
            <button
              class="btn-backtest-lg"
              :disabled="actionBusyId === selectedCandidate.id"
              @click="runFullBacktest(selectedCandidate)"
            >
              🚀 Full Backtest &amp; Chart
            </button>
          </div>
        </div>

        <div class="insp-tabs">
          <button :class="['itab', { active: inspectorTab === 'overview' }]" @click="inspectorTab = 'overview'">
            📈 Equity &amp; IS/OOS Robustness
          </button>
          <button :class="['itab', { active: inspectorTab === 'rules' }]" @click="inspectorTab = 'rules'">
            🧬 Signal &amp; Filter DNA
          </button>
          <button :class="['itab', { active: inspectorTab === 'code' }]" @click="inspectorTab = 'code'">
            💻 Java Code
          </button>
        </div>

        <!-- Tab 1: Overview & Equity Curve -->
        <div v-if="inspectorTab === 'overview'" class="insp-body">
          <div class="is-oos-compare-grid">
            <div class="split-box is">
              <span class="split-tag">IN-SAMPLE (TRAIN)</span>
              <div class="split-metrics">
                <div><span>Net Profit</span> <strong :class="selectedCandidate.isNetProfit >= 0 ? 'profit' : 'loss'">{{ formatMoney(selectedCandidate.isNetProfit) }}</strong></div>
                <div><span>Profit Factor</span> <strong>{{ Math.min(99.9, selectedCandidate.isProfitFactor).toFixed(2) }}</strong></div>
                <div><span>Sharpe</span> <strong>{{ selectedCandidate.isSharpeRatio.toFixed(2) }}</strong></div>
                <div><span>Trades</span> <strong>{{ selectedCandidate.isTrades }}</strong></div>
              </div>
            </div>
            <div class="split-box oos">
              <span class="split-tag oos">BLIND OUT-OF-SAMPLE</span>
              <div class="split-metrics">
                <div><span>Net Profit</span> <strong :class="selectedCandidate.oosNetProfit >= 0 ? 'profit' : 'loss'">{{ formatMoney(selectedCandidate.oosNetProfit) }}</strong></div>
                <div><span>Profit Factor</span> <strong>{{ Math.min(99.9, selectedCandidate.oosProfitFactor).toFixed(2) }}</strong></div>
                <div><span>Sharpe</span> <strong>{{ selectedCandidate.oosSharpeRatio.toFixed(2) }}</strong></div>
                <div><span>Trades</span> <strong>{{ selectedCandidate.oosTrades }}</strong></div>
              </div>
            </div>
            <div class="split-box mc">
              <span class="split-tag mc">MONTE CARLO &amp; STABILITY</span>
              <div class="split-metrics">
                <div class="metric-tip-row" @mouseenter="showTooltip($event, 'rSquared')" @mouseleave="hideTooltip">
                  <span class="metric-tip-lbl">Equity R²</span> <strong>{{ selectedCandidate.rSquared.toFixed(2) }}</strong>
                </div>
                <div class="metric-tip-row" @mouseenter="showTooltip($event, 'retDd')" @mouseleave="hideTooltip">
                  <span class="metric-tip-lbl">Ret / DD</span> <strong>{{ selectedCandidate.retDdRatio.toFixed(2) }}</strong>
                </div>
                <div class="metric-tip-row" @mouseenter="showTooltip($event, 'mcVar95')" @mouseleave="hideTooltip">
                  <span class="metric-tip-lbl">MC VaR 95</span> <strong>{{ formatMoney(selectedCandidate.mcVar95) }}</strong>
                </div>
                <div class="metric-tip-row" @mouseenter="showTooltip($event, 'mcLossRisk')" @mouseleave="hideTooltip">
                  <span class="metric-tip-lbl">Loss Prob</span> <strong>{{ selectedCandidate.mcLossProbPct.toFixed(1) }}%</strong>
                </div>
              </div>
            </div>
          </div>

          <div class="equity-wrap">
            <div class="eq-header">
              <h4>Combined In-Sample + Out-of-Sample Equity Curve</h4>
              <div class="eq-legend">
                <span class="eq-leg-item is">
                  <span class="eq-leg-swatch is"></span>
                  In-Sample ({{ 100 - oosSplitPct }}% Train · #1–#{{ selectedCandidate.oosStartIndex }})
                </span>
                <span class="eq-leg-item oos">
                  <span class="eq-leg-swatch oos"></span>
                  Out-of-Sample ({{ oosSplitPct }}% Blind · #{{ selectedCandidate.oosStartIndex }}+)
                </span>
                <span class="eq-leg-item dd">
                  <span class="eq-leg-swatch dd"></span>
                  Drawdown %
                </span>
              </div>
            </div>
            <EquityChart
              v-if="selectedCandidate.equityCurve && selectedCandidate.equityCurve.length > 1"
              :data="selectedCandidate.equityCurve"
              :oos-start-index="selectedCandidate.oosStartIndex"
              :oos-split-pct="oosSplitPct"
              :height="255"
            />
          </div>
        </div>

        <!-- Tab 2: Strategy Rules & Signal DNA -->
        <div v-else-if="inspectorTab === 'rules'" class="insp-body">
          <div class="rules-grid">
            <div class="rule-box long" @mouseenter="showTooltip($event, 'ruleLongSignal')" @mouseleave="hideTooltip">
              <div class="rule-title hover-lbl">🟢 Long Entry Signal</div>
              <div class="rule-code">{{ selectedCandidate.longSignalSummary }}</div>
            </div>
            <div class="rule-box short" @mouseenter="showTooltip($event, 'ruleShortSignal')" @mouseleave="hideTooltip">
              <div class="rule-title hover-lbl">🔴 Short Entry Signal</div>
              <div class="rule-code">{{ selectedCandidate.shortSignalSummary }}</div>
            </div>
            <div class="rule-box entry" @mouseenter="showTooltip($event, 'ruleEntryMethod')" @mouseleave="hideTooltip">
              <div class="rule-title hover-lbl">⚡ Order Execution Method</div>
              <div class="rule-code">{{ selectedCandidate.entrySummary }}</div>
            </div>
            <div class="rule-box exit" @mouseenter="showTooltip($event, 'ruleExitManagement')" @mouseleave="hideTooltip">
              <div class="rule-title hover-lbl">🛡️ ATR Stop-Loss, Profit-Target &amp; Exit</div>
              <div class="rule-code">{{ selectedCandidate.exitSummary }}</div>
            </div>
          </div>
          <div class="ind-strip">
            <span class="ind-lbl">Active Signals &amp; Filters (hover for definition):</span>
            <span
              v-for="ind in selectedCandidate.indicators"
              :key="ind"
              class="ind-pill"
              @mouseenter="showIndicatorTooltip($event, ind)"
              @mouseleave="hideTooltip"
            >
              {{ ind }}
            </span>
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

    <!-- Instant Floating Definition Popover -->
    <Teleport to="body">
      <div
        v-if="activeTooltip"
        class="sqx-col-tooltip"
        :style="{ left: `${activeTooltip.x}px`, top: `${activeTooltip.y}px` }"
      >
        <div class="sqx-tip-title">{{ activeTooltip.col.title }}</div>
        <div v-if="activeTooltip.col.formula" class="sqx-tip-formula">{{ activeTooltip.col.formula }}</div>
        <div class="sqx-tip-desc">{{ activeTooltip.col.description }}</div>
        <div v-if="activeTooltip.col.benchmark" class="sqx-tip-bench">{{ activeTooltip.col.benchmark }}</div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.generator-view {
  padding: 1.25rem 1.75rem;
  color: #E5E7EB;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.85rem;
  padding-bottom: 0.4rem;
  border-bottom: 1px solid rgba(31, 41, 55, 0.7);
}

.title-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  flex-wrap: wrap;
}

.title-row h1 {
  font-size: 1.45rem;
  font-weight: 700;
  color: #F9FAFB;
  margin: 0;
  letter-spacing: -0.01em;
}

.engine-badge {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.18), rgba(217, 119, 6, 0.32));
  border: 1px solid rgba(245, 158, 11, 0.45);
  color: #FBBF24;
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding: 0.18rem 0.55rem;
  border-radius: 999px;
}

.active-summary-pill {
  background: rgba(17, 24, 39, 0.9);
  border: 1px solid #2D3748;
  color: #9CA3AF;
  font-size: 0.72rem;
  padding: 0.18rem 0.6rem;
  border-radius: 999px;
}

.active-summary-pill strong {
  color: #F3F4F6;
}

.subtitle {
  margin: 0.2rem 0 0 0;
  font-size: 0.8rem;
  color: #9CA3AF;
}

.header-controls {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.preset-bar {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  flex-wrap: wrap;
}

.preset-lbl {
  font-size: 0.72rem;
  color: #6B7280;
  margin-right: 0.15rem;
}

.view-toggles {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.preset-btn, .toggle-cfg-btn, .layout-btn {
  background: #14171E;
  border: 1px solid #262F3F;
  color: #D1D5DB;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0.34rem 0.62rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.preset-btn:hover, .toggle-cfg-btn:hover, .layout-btn:hover {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.08);
}

.studio-builder {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}

.config-grid {
  display: grid;
  grid-template-columns: 1.05fr 1.15fr 1fr;
  gap: 0.85rem;
}

.cfg-card {
  background: #13161D;
  border: 1px solid #1F2633;
  border-radius: 10px;
  padding: 0.85rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.cfg-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  border-bottom: 1px solid #1E2532;
  padding-bottom: 0.45rem;
}

.step-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.step-num {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(245, 158, 11, 0.18);
  border: 1px solid rgba(245, 158, 11, 0.6);
  color: #FBBF24;
  font-size: 0.7rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.cfg-card-header h3 {
  font-size: 0.84rem;
  font-weight: 600;
  color: #F3F4F6;
  margin: 0;
}

.card-tag {
  font-size: 0.68rem;
  font-weight: 600;
  padding: 0.12rem 0.45rem;
  border-radius: 4px;
  background: #1E2532;
  color: #9CA3AF;
}

.card-tag.amber {
  background: rgba(245, 158, 11, 0.14);
  color: #FBBF24;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.55rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.24rem;
}

.field label {
  font-size: 0.66rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #9CA3AF;
}

.hover-lbl {
  cursor: help;
  border-bottom: 1px dotted rgba(156, 163, 175, 0.4);
  width: fit-content;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.hover-lbl:hover {
  color: #FBBF24 !important;
  border-bottom-color: #FBBF24;
}

.field select, .field input {
  background: #0B0D12;
  border: 1px solid #262F3F;
  border-radius: 6px;
  color: #F9FAFB;
  padding: 0.36rem 0.5rem;
  font-size: 0.76rem;
  outline: none;
  transition: border-color 0.15s ease;
}

.field select:focus, .field input:focus {
  border-color: #F59E0B;
}

.seg-group {
  display: flex;
  background: #0B0D12;
  border: 1px solid #262F3F;
  border-radius: 6px;
  padding: 0.14rem;
  gap: 0.14rem;
}

.seg-group.wrap {
  flex-wrap: wrap;
}

.seg-btn {
  flex: 1;
  background: transparent;
  border: none;
  color: #9CA3AF;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.28rem 0.45rem;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.12s ease;
}

.seg-btn:hover:not(:disabled) {
  color: #F3F4F6;
}

.seg-btn.active {
  background: rgba(245, 158, 11, 0.18);
  color: #FBBF24;
  box-shadow: inset 0 0 0 1px rgba(245, 158, 11, 0.45);
}

.split-lbl-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.split-pills-mini {
  display: flex;
  gap: 0.22rem;
}

.mini-opt {
  background: #0B0D12;
  border: 1px solid #262F3F;
  color: #9CA3AF;
  font-size: 0.62rem;
  font-weight: 600;
  padding: 0.12rem 0.38rem;
  border-radius: 4px;
  cursor: pointer;
}

.mini-opt:hover:not(:disabled), .mini-opt.active {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.12);
}

.visual-split-bar {
  display: flex;
  height: 22px;
  border-radius: 5px;
  overflow: hidden;
  border: 1px solid #262F3F;
  font-size: 0.65rem;
  font-weight: 700;
}

.is-segment {
  background: rgba(59, 130, 246, 0.22);
  color: #93C5FD;
  display: flex;
  align-items: center;
  justify-content: center;
  border-right: 1px solid rgba(59, 130, 246, 0.4);
  transition: width 0.2s ease;
}

.oos-segment {
  background: rgba(16, 185, 129, 0.22);
  color: #6EE7B7;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: width 0.2s ease;
}

.gate-summary-box {
  margin-top: auto;
  padding: 0.45rem 0.65rem;
  background: rgba(59, 130, 246, 0.07);
  border: 1px solid rgba(59, 130, 246, 0.22);
  border-radius: 6px;
  font-size: 0.7rem;
  color: #93C5FD;
}

.ga-preset-strip {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin-top: auto;
  padding-top: 0.2rem;
}

.ga-preset-lbl {
  font-size: 0.65rem;
  font-weight: 700;
  color: #9CA3AF;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-right: 0.15rem;
}

/* Signal & Filter Building Blocks Studio Matrix */
.blocks-studio-card {
  background: #13161D;
  border: 1px solid #1F2633;
  border-radius: 10px;
  padding: 0.8rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.blocks-studio-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.65rem;
}

.blocks-studio-left {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.blocks-studio-left h3 {
  margin: 0;
  font-size: 0.85rem;
  font-weight: 600;
  color: #F3F4F6;
}

.blocks-sub-count {
  font-size: 0.72rem;
  color: #9CA3AF;
}

.blocks-studio-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.blocks-pack-btns {
  display: flex;
  gap: 0.28rem;
  flex-wrap: wrap;
}

.pack-btn, .btn-collapse-blocks {
  background: #0B0D12;
  border: 1px solid #262F3F;
  color: #D1D5DB;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 0.24rem 0.55rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.pack-btn:hover:not(:disabled), .btn-collapse-blocks:hover {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.1);
}

.cat-filter-strip {
  display: flex;
  gap: 0.3rem;
  flex-wrap: wrap;
  padding-bottom: 0.2rem;
  border-bottom: 1px solid #1E2532;
}

.cat-tab {
  background: transparent;
  border: 1px solid transparent;
  color: #9CA3AF;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 0.2rem 0.52rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.cat-tab:hover {
  color: #F3F4F6;
  background: rgba(255, 255, 255, 0.04);
}

.cat-tab.active {
  background: rgba(245, 158, 11, 0.14);
  border-color: rgba(245, 158, 11, 0.4);
  color: #FBBF24;
}

.blocks-matrix-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(225px, 1fr));
  gap: 0.45rem;
  max-height: 175px;
  overflow-y: auto;
  padding-right: 0.2rem;
}

.signal-block-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.2rem;
  padding: 0.42rem 0.6rem;
  border-radius: 7px;
  background: #0B0D12;
  border: 1px solid #222B3A;
  color: #9CA3AF;
  cursor: pointer;
  text-align: left;
  transition: all 0.12s ease;
}

.signal-block-card:hover:not(:disabled) {
  border-color: #F59E0B;
  transform: translateY(-1px);
}

.signal-block-card.active {
  background: rgba(16, 185, 129, 0.08);
  border-color: rgba(16, 185, 129, 0.4);
  color: #F3F4F6;
}

.sbc-top {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 0.38rem;
}

.sbc-check {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid #374151;
  background: #13161D;
  color: #10B981;
  font-size: 0.62rem;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.signal-block-card.active .sbc-check {
  border-color: #10B981;
  background: rgba(16, 185, 129, 0.2);
}

.sbc-name {
  font-size: 0.72rem;
  font-weight: 600;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sbc-cat {
  font-size: 0.56rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.08rem 0.32rem;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  color: #9CA3AF;
  flex-shrink: 0;
}

.signal-block-card.active .sbc-cat {
  background: rgba(16, 185, 129, 0.18);
  color: #6EE7B7;
}

.sbc-formula {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.62rem;
  color: #6B7280;
  padding-left: 1.25rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

/* Telemetry Strip */
.telemetry-strip {
  background: #13161D;
  border: 1px solid #1F2633;
  border-radius: 10px;
  padding: 0.7rem 1rem;
  display: flex;
  align-items: center;
  gap: 1.25rem;
  flex-wrap: wrap;
}

.btn-start-mining {
  background: linear-gradient(135deg, #F59E0B, #D97706);
  border: none;
  color: #0B0D12;
  font-weight: 700;
  font-size: 0.82rem;
  padding: 0.58rem 1.15rem;
  border-radius: 8px;
  cursor: pointer;
  white-space: nowrap;
  box-shadow: 0 4px 14px rgba(245, 158, 11, 0.25);
}

.btn-start-mining:hover {
  filter: brightness(1.08);
}

.btn-stop-mining {
  background: rgba(239, 68, 68, 0.18);
  border: 1px solid #EF4444;
  color: #FCA5A5;
  font-weight: 700;
  font-size: 0.82rem;
  padding: 0.58rem 1.15rem;
  border-radius: 8px;
  cursor: pointer;
}

.telemetry-progress {
  flex: 1;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 0.75rem;
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
  height: 7px;
  background: #0B0D12;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid #262F3F;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #F59E0B, #10B981);
  transition: width 0.25s ease;
}

.telemetry-kpis {
  display: flex;
  gap: 1.1rem;
}

.tkpi {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.tkpi-lbl {
  font-size: 0.64rem;
  text-transform: uppercase;
  color: #6B7280;
}

.tkpi-val {
  font-size: 0.88rem;
  font-weight: 700;
  font-family: monospace;
  color: #F9FAFB;
}

.tkpi-val.amber { color: #FBBF24; }
.tkpi-val.emerald { color: #34D399; }

/* Master-Detail Workspace */
.databank-workspace {
  display: grid;
  gap: 1rem;
  align-items: start;
}

.databank-workspace.split-mode {
  grid-template-columns: minmax(0, 1.35fr) minmax(380px, 1fr);
}

.databank-workspace.stacked-mode {
  grid-template-columns: 1fr;
}

@media (max-width: 1320px) {
  .databank-workspace.split-mode {
    grid-template-columns: 1fr;
  }
  .config-grid {
    grid-template-columns: 1fr;
  }
}

.databank-card, .inspector-card {
  background: #13161D;
  border: 1px solid #1F2633;
  border-radius: 10px;
  padding: 0.9rem 1.05rem;
}

.inspector-card {
  position: sticky;
  top: 0.75rem;
}

.databank-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.65rem;
  margin-bottom: 0.7rem;
}

.db-title-group {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.db-title-group h2 {
  font-size: 0.98rem;
  margin: 0;
  color: #F9FAFB;
}

.db-count {
  font-size: 0.7rem;
  background: #1E2532;
  color: #D1D5DB;
  padding: 0.14rem 0.5rem;
  border-radius: 999px;
}

.db-controls {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
}

.tier-pills {
  display: flex;
  gap: 0.25rem;
}

.tier-pill {
  background: #0B0D12;
  border: 1px solid #262F3F;
  color: #9CA3AF;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 0.22rem 0.52rem;
  border-radius: 5px;
  cursor: pointer;
}

.tier-pill.active {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.1);
}

.db-search {
  background: #0B0D12;
  border: 1px solid #262F3F;
  border-radius: 5px;
  color: #F9FAFB;
  padding: 0.25rem 0.55rem;
  font-size: 0.72rem;
  width: 175px;
}

.btn-clear-db {
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.35);
  color: #FCA5A5;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 0.24rem 0.55rem;
  border-radius: 5px;
  cursor: pointer;
}

.empty-databank {
  padding: 2.2rem 1rem;
  text-align: center;
  color: #9CA3AF;
  font-size: 0.84rem;
}

.table-scroll {
  overflow-x: auto;
  max-height: 490px;
  overflow-y: auto;
}

.databank-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.74rem;
}

.databank-table th {
  text-align: left;
  padding: 0.48rem 0.5rem;
  border-bottom: 1px solid #1E2532;
  color: #9CA3AF;
  font-size: 0.64rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  position: sticky;
  top: 0;
  background: #13161D;
  white-space: nowrap;
  z-index: 2;
}

.databank-table td {
  padding: 0.46rem 0.5rem;
  border-bottom: 1px solid rgba(30, 37, 50, 0.65);
  white-space: nowrap;
  vertical-align: middle;
}

.databank-table tbody tr {
  cursor: pointer;
  transition: background 0.12s ease;
}

.databank-table tbody tr:hover {
  background: rgba(255, 255, 255, 0.03);
}

.databank-table tbody tr.selected {
  background: rgba(245, 158, 11, 0.11);
}

.strat-id-cell {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.promoted-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: rgba(16, 185, 129, 0.2);
  color: #34D399;
  font-size: 0.6rem;
  font-weight: 800;
}

.metric-stack {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.tier-badge {
  font-size: 0.62rem;
  font-weight: 700;
  padding: 0.14rem 0.42rem;
  border-radius: 4px;
}

.tier-badge.elite {
  background: rgba(245, 158, 11, 0.18);
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
  font-size: 0.6rem;
  background: rgba(16, 185, 129, 0.18);
  color: #34D399;
  border: 1px solid rgba(16, 185, 129, 0.4);
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
}

.sym-badge {
  font-weight: 700;
  color: #F3F4F6;
  margin-right: 0.25rem;
}

.tf-badge {
  font-size: 0.64rem;
  background: #1E2532;
  color: #9CA3AF;
  padding: 0.08rem 0.3rem;
  border-radius: 4px;
}

.arch-text {
  font-size: 0.7rem;
  font-weight: 600;
  color: #E5E7EB;
}

.entry-sub {
  font-size: 0.62rem;
  color: #6B7280;
  margin-left: 0.25rem;
}

.sub-split {
  font-size: 0.63rem;
  color: #6B7280;
}

.profit { color: #10B981; font-weight: 600; }
.loss { color: #EF4444; font-weight: 600; }

.score-cell {
  display: flex;
  align-items: center;
  gap: 0.38rem;
}

.score-num {
  font-family: monospace;
  font-weight: 700;
  color: #FBBF24;
}

.score-bar-bg {
  width: 40px;
  height: 5px;
  background: #0B0D12;
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
  gap: 0.28rem;
}

.act-btn {
  font-size: 0.66rem;
  font-weight: 600;
  padding: 0.22rem 0.45rem;
  border-radius: 5px;
  cursor: pointer;
  border: 1px solid transparent;
}

.act-btn.promote {
  background: rgba(16, 185, 129, 0.14);
  border-color: rgba(16, 185, 129, 0.38);
  color: #34D399;
}

.act-btn.backtest {
  background: rgba(59, 130, 246, 0.14);
  border-color: rgba(59, 130, 246, 0.38);
  color: #60A5FA;
}

.act-btn.delete {
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.28);
  color: #FCA5A5;
}

/* Strategy Inspector */
.inspector-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 0.65rem;
  padding-bottom: 0.7rem;
  border-bottom: 1px solid #1E2532;
}

.insp-title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.insp-title-row h3 {
  margin: 0;
  font-size: 1.05rem;
  color: #F9FAFB;
}

.insp-sub {
  margin: 0.18rem 0 0 0;
  font-size: 0.74rem;
  color: #9CA3AF;
}

.insp-actions {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
}

.btn-promote-lg {
  background: rgba(16, 185, 129, 0.16);
  border: 1px solid #10B981;
  color: #34D399;
  font-weight: 600;
  font-size: 0.73rem;
  padding: 0.38rem 0.7rem;
  border-radius: 6px;
  cursor: pointer;
}

.btn-promote-lg.secondary {
  background: #0B0D12;
  border-color: #262F3F;
  color: #D1D5DB;
}

.btn-backtest-lg {
  background: linear-gradient(135deg, #3B82F6, #2563EB);
  border: none;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 0.73rem;
  padding: 0.38rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
}

.insp-tabs {
  display: flex;
  gap: 0.35rem;
  margin: 0.7rem 0;
}

.itab {
  flex: 1;
  background: #0B0D12;
  border: 1px solid #262F3F;
  color: #9CA3AF;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0.38rem 0.6rem;
  border-radius: 6px;
  cursor: pointer;
  text-align: center;
}

.itab.active {
  border-color: #F59E0B;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.1);
}

.is-oos-compare-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.55rem;
  margin-bottom: 0.75rem;
}

.split-box {
  background: #0B0D12;
  border: 1px solid #1E2532;
  border-radius: 8px;
  padding: 0.6rem 0.7rem;
}

.split-tag {
  font-size: 0.6rem;
  font-weight: 700;
  letter-spacing: 0.05em;
  color: #60A5FA;
  display: block;
  margin-bottom: 0.35rem;
}

.split-tag.oos { color: #34D399; }
.split-tag.mc { color: #FBBF24; }

.split-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.28rem 0.45rem;
  font-size: 0.72rem;
}

.split-metrics > div {
  display: flex;
  flex-direction: column;
}

.split-metrics span {
  font-size: 0.63rem;
  color: #6B7280;
}

.eq-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.35rem;
  margin-bottom: 0.4rem;
}

.eq-header h4 {
  margin: 0;
  font-size: 0.8rem;
  color: #E5E7EB;
}

.oos-note {
  font-size: 0.68rem;
  color: #34D399;
}

.eq-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.eq-leg-item {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.67rem;
  font-weight: 700;
  color: #94A3B8;
}

.eq-leg-item.is {
  color: #38BDF8;
}

.eq-leg-item.oos {
  color: #34D399;
}

.eq-leg-item.dd {
  color: #F87171;
}

.eq-leg-swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  display: inline-block;
}

.eq-leg-swatch.is {
  background: #38BDF8;
  box-shadow: 0 0 6px rgba(56, 189, 248, 0.55);
}

.eq-leg-swatch.oos {
  background: #10B981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.55);
}

.eq-leg-swatch.dd {
  background: #EF4444;
}

.rules-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.6rem;
}

.rule-box {
  background: #0B0D12;
  border: 1px solid #1E2532;
  border-radius: 8px;
  padding: 0.65rem 0.8rem;
}

.rule-title {
  font-size: 0.72rem;
  font-weight: 700;
  color: #D1D5DB;
  margin-bottom: 0.3rem;
}

.rule-code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.74rem;
  color: #FBBF24;
  line-height: 1.4;
}

.ind-strip {
  margin-top: 0.7rem;
  display: flex;
  align-items: center;
  gap: 0.38rem;
  flex-wrap: wrap;
}

.ind-lbl {
  font-size: 0.7rem;
  color: #9CA3AF;
}

.ind-pill {
  font-size: 0.68rem;
  background: #1E2532;
  border: 1px solid #2D3748;
  color: #E5E7EB;
  padding: 0.16rem 0.5rem;
  border-radius: 999px;
  cursor: help;
  transition: all 0.12s ease;
}

.ind-pill:hover {
  border-color: #F59E0B;
  color: #FBBF24;
}

.code-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.45rem;
}

.code-path {
  font-size: 0.7rem;
  color: #9CA3AF;
}

.btn-copy-code {
  background: #1E2532;
  border: 1px solid #374151;
  color: #F3F4F6;
  font-size: 0.7rem;
  padding: 0.3rem 0.65rem;
  border-radius: 5px;
  cursor: pointer;
}

.java-code-viewer {
  background: #090B0F;
  border: 1px solid #1E2532;
  border-radius: 8px;
  padding: 0.75rem;
  font-size: 0.7rem;
  line-height: 1.42;
  color: #D1D5DB;
  max-height: 310px;
  overflow: auto;
  margin: 0;
}

.th-label {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  border-bottom: 1px dotted rgba(156, 163, 175, 0.35);
  padding-bottom: 1px;
}

.th-sortable {
  cursor: pointer;
  user-select: none;
  transition: color 0.15s ease, background 0.15s ease;
}

.th-sortable:hover {
  color: #F9FAFB;
  background: rgba(255, 255, 255, 0.04);
}

.th-sortable:hover .th-label {
  border-bottom-color: #FBBF24;
}

.th-sorted {
  color: #FBBF24 !important;
}

.sort-arrow {
  display: inline-block;
  font-size: 0.62rem;
  opacity: 0.4;
}

.th-sorted .sort-arrow {
  opacity: 1;
  color: #FBBF24;
}

.metric-tip-row {
  cursor: help;
}

.metric-tip-lbl {
  border-bottom: 1px dotted rgba(156, 163, 175, 0.4);
}

.metric-tip-row:hover .metric-tip-lbl {
  color: #FBBF24;
  border-bottom-color: #FBBF24;
}

:global(.sqx-col-tooltip) {
  position: fixed;
  z-index: 99999;
  width: 340px;
  background: rgba(13, 16, 23, 0.98);
  border: 1px solid rgba(245, 158, 11, 0.5);
  border-radius: 10px;
  padding: 0.75rem 0.9rem;
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.7);
  pointer-events: none;
  backdrop-filter: blur(10px);
}

:global(.sqx-tip-title) {
  font-size: 0.8rem;
  font-weight: 700;
  color: #F9FAFB;
  margin-bottom: 0.3rem;
}

:global(.sqx-tip-formula) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.68rem;
  color: #FBBF24;
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.25);
  border-radius: 5px;
  padding: 0.22rem 0.48rem;
  margin-bottom: 0.45rem;
}

:global(.sqx-tip-desc) {
  font-size: 0.74rem;
  line-height: 1.45;
  color: #D1D5DB;
  margin-bottom: 0.45rem;
}

:global(.sqx-tip-bench) {
  font-size: 0.68rem;
  font-weight: 600;
  color: #34D399;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding-top: 0.35rem;
}
</style>
