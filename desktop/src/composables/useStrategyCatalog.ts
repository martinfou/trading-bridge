import { ref, computed, watch } from 'vue'
import type { Strategy, AssetClass } from '../types/control-plane'
import { useControlPlane } from './useControlPlane'

const STORAGE_KEY_FILTERS = 'trading_bridge_strategy_filters_v2'
const STORAGE_KEY_DELETED = 'trading_bridge_deleted_strategies'

export type CatalogSortField =
  | 'score'
  | 'pf'
  | 'oosPf'
  | 'sharpe'
  | 'dd'
  | 'winRate'
  | 'trades'
  | 'netProfit'
  | 'name'

export type CatalogViewMode = 'table' | 'cards'

// Audited benchmark metrics (2010–2026 Full / 2010–2021 IS / 2022–2026 OOS under RealCostModel + SwapCalculator)
interface BuiltInAuditMeta {
  displayName: string
  shortHash: string
  defaultSymbol: string
  primaryTimeframe: string
  tier: string
  compositeScore: number
  promoted: boolean
  profitFactor: number
  isProfitFactor: number
  oosProfitFactor: number
  sharpeRatio: number
  isSharpeRatio: number
  oosSharpeRatio: number
  winRatePct: number
  maxDrawdownPct: number
  totalTrades: number
  isTrades: number
  oosTrades: number
  netProfit: number
  totalReturnPct: number
  rSquared: number
  type: string
  tradingStyle: string
  indicators: string[]
  description: string
  longSignalSummary: string
  shortSignalSummary: string
  exitSummary: string
  sessionSummary: string
  recommendedSymbols: string[]
}

const BUILTIN_AUDITED_META: Record<string, BuiltInAuditMeta> = {
  LondonOpenRangeBreakout: {
    displayName: 'London Open Range Breakout',
    shortHash: 'CORE-FX',
    defaultSymbol: 'EUR_USD',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 86.4,
    promoted: true,
    profitFactor: 1.17,
    isProfitFactor: 1.12,
    oosProfitFactor: 1.35,
    sharpeRatio: 1.12,
    isSharpeRatio: 0.98,
    oosSharpeRatio: 1.31,
    winRatePct: 48.5,
    maxDrawdownPct: 4.2,
    totalTrades: 1051,
    isTrades: 777,
    oosTrades: 274,
    netProfit: 457.88,
    totalReturnPct: 18.4,
    rSquared: 0.94,
    type: 'Session Breakout',
    tradingStyle: 'BREAKOUT',
    indicators: ['London 07–08 UTC Range', 'EMA(50)', 'ATR(14)'],
    description: 'Enters on breakout of the 07:00–08:00 UTC London opening range, filtered by H1 EMA trend structure.',
    longSignalSummary: 'Close breaks above 07:00–08:00 UTC High AND Close > EMA(50)',
    shortSignalSummary: 'Close breaks below 07:00–08:00 UTC Low AND Close < EMA(50)',
    exitSummary: 'SL 1.5x Range / ATR(14) · TP 2.5x Range · Session Flat 20:00 UTC',
    sessionSummary: 'Mon–Fri | London Open 08:00–16:00 UTC | Intraday Flat',
    recommendedSymbols: ['EUR_USD', 'GBP_JPY', 'GBP_USD']
  },
  MondayReversion: {
    displayName: 'Monday Range Reversion',
    shortHash: 'CORE-FX',
    defaultSymbol: 'GBP_USD',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 85.2,
    promoted: true,
    profitFactor: 1.19,
    isProfitFactor: 1.11,
    oosProfitFactor: 1.40,
    sharpeRatio: 0.94,
    isSharpeRatio: 0.86,
    oosSharpeRatio: 1.18,
    winRatePct: 54.0,
    maxDrawdownPct: 9.5,
    totalTrades: 865,
    isTrades: 625,
    oosTrades: 240,
    netProfit: 289.64,
    totalReturnPct: 14.2,
    rSquared: 0.93,
    type: 'Mean Reversion',
    tradingStyle: 'MEAN_REVERSION',
    indicators: ['Friday High/Low', 'ATR(14)', 'Monday Session'],
    description: 'Fades overextended Monday opening extensions relative to Friday range with tight ATR risk control.',
    longSignalSummary: 'Monday extension below Friday Low by > 0.5x ATR(14) with bullish rejection bar',
    shortSignalSummary: 'Monday extension above Friday High by > 0.5x ATR(14) with bearish rejection bar',
    exitSummary: 'Target Friday Midpoint / 1.8x ATR(14) · Stop 1.5x ATR(14)',
    sessionSummary: 'Mon–Tue | Institutional Week-Open Liquidity Fade',
    recommendedSymbols: ['GBP_USD', 'USD_CAD', 'EUR_USD']
  },
  DlrMeanReversion: {
    displayName: 'DLR Z-Score Reversion',
    shortHash: 'CORE-FX',
    defaultSymbol: 'USD_CAD',
    primaryTimeframe: 'H1',
    tier: 'QUALIFIED',
    compositeScore: 74.5,
    promoted: true,
    profitFactor: 1.09,
    isProfitFactor: 1.08,
    oosProfitFactor: 1.09,
    sharpeRatio: 0.82,
    isSharpeRatio: 0.79,
    oosSharpeRatio: 0.88,
    winRatePct: 55.1,
    maxDrawdownPct: 6.4,
    totalTrades: 671,
    isTrades: 531,
    oosTrades: 140,
    netProfit: 70.75,
    totalReturnPct: 12.6,
    rSquared: 0.89,
    type: 'Mean Reversion',
    tradingStyle: 'MEAN_REVERSION',
    indicators: ['Linear Regression', 'Z-Score(20)', 'ATR(14)'],
    description: 'Dynamic linear regression residual mean-reversion model optimized for North American USD/CAD flows.',
    longSignalSummary: 'Regression Z-Score(20) < -2.0 AND Price > SMA(200) macro filter',
    shortSignalSummary: 'Regression Z-Score(20) > +2.0 AND Price < SMA(200) macro filter',
    exitSummary: 'Revert to Regression Mean (Z = 0) · Stop 2.0x ATR(14)',
    sessionSummary: 'Mon–Fri | 24h FX Session',
    recommendedSymbols: ['USD_CAD', 'EUR_USD', 'USD_CHF']
  },
  MesTurtle: {
    displayName: 'Turtle System-2 Breakout',
    shortHash: 'SYS-55/20',
    defaultSymbol: 'MGC',
    primaryTimeframe: 'H1',
    tier: 'INSTITUTIONAL',
    compositeScore: 84.8,
    promoted: true,
    profitFactor: 1.51,
    isProfitFactor: 1.00,
    oosProfitFactor: 1.91,
    sharpeRatio: 1.18,
    isSharpeRatio: 0.82,
    oosSharpeRatio: 1.54,
    winRatePct: 40.5,
    maxDrawdownPct: 29.8,
    totalTrades: 967,
    isTrades: 558,
    oosTrades: 409,
    netProfit: 29530.54,
    totalReturnPct: 295.3,
    rSquared: 0.91,
    type: 'Donchian Breakout',
    tradingStyle: 'BREAKOUT',
    indicators: ['Donchian(55)', 'Donchian(20)', 'ATR(20)'],
    description: 'Classic Richard Dennis System-2 (55-bar entry / 20-bar exit) Donchian breakout on CME Micro Gold & Index Futures.',
    longSignalSummary: 'High breaks above 55-bar Donchian Channel High (Long-Only Trend Filter)',
    shortSignalSummary: 'Long-only structural configuration for positive-drift inflation/equity futures',
    exitSummary: '20-bar Donchian Channel Low trailing exit · 2N ATR(20) hard volatility stop',
    sessionSummary: 'Mon–Fri | CME Globex Continuous',
    recommendedSymbols: ['MGC', 'MNQ', 'MES']
  },
  LtDoubleMA: {
    displayName: 'Long-Term Double MA Cross',
    shortHash: 'CORE-LT',
    defaultSymbol: 'USD_CHF',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 81.0,
    promoted: true,
    profitFactor: 1.14,
    isProfitFactor: 1.09,
    oosProfitFactor: 1.26,
    sharpeRatio: 0.88,
    isSharpeRatio: 0.81,
    oosSharpeRatio: 1.04,
    winRatePct: 44.0,
    maxDrawdownPct: 9.4,
    totalTrades: 584,
    isTrades: 404,
    oosTrades: 180,
    netProfit: 129.01,
    totalReturnPct: 16.5,
    rSquared: 0.92,
    type: 'Trend Following',
    tradingStyle: 'TREND_FOLLOWING',
    indicators: ['EMA(50)', 'SMA(200)', 'ATR(14)'],
    description: 'Institutional EMA(50) / SMA(200) structural trend-following crossover with ATR volatility sizing.',
    longSignalSummary: 'EMA(50) crosses above SMA(200) with positive slope confirmation',
    shortSignalSummary: 'EMA(50) crosses below SMA(200) with negative slope confirmation',
    exitSummary: 'Trailing 3.0x ATR(14) stop or opposite moving-average cross',
    sessionSummary: 'Mon–Fri | Multi-Week Structural Hold',
    recommendedSymbols: ['USD_CHF', 'GBP_JPY', 'MGC', 'MNQ']
  },
  LtCrossMomentum: {
    displayName: 'Long-Term Cross Momentum',
    shortHash: 'CORE-LT',
    defaultSymbol: 'USD_CHF',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 80.2,
    promoted: true,
    profitFactor: 1.13,
    isProfitFactor: 1.13,
    oosProfitFactor: 1.13,
    sharpeRatio: 0.85,
    isSharpeRatio: 0.84,
    oosSharpeRatio: 0.87,
    winRatePct: 45.5,
    maxDrawdownPct: 7.6,
    totalTrades: 944,
    isTrades: 678,
    oosTrades: 266,
    netProfit: 193.00,
    totalReturnPct: 15.1,
    rSquared: 0.94,
    type: 'Trend Following',
    tradingStyle: 'MOMENTUM',
    indicators: ['SMA(20)', 'SMA(100)', 'ATR(14)'],
    description: 'Medium-to-long-horizon SMA(20)/SMA(100) momentum crossover verified across 16 years of FX & Futures data.',
    longSignalSummary: 'SMA(20) > SMA(100) AND 20-bar Rate of Change > +0.5x ATR(14)',
    shortSignalSummary: 'SMA(20) < SMA(100) AND 20-bar Rate of Change < -0.5x ATR(14)',
    exitSummary: 'ATR(14) Chandelier Exit (2.5x SL / 4.0x TP)',
    sessionSummary: 'Mon–Fri | 24h Multi-Session Trend',
    recommendedSymbols: ['USD_CHF', 'MES', 'MNQ', 'EUR_USD']
  },
  LtRSIMeanRev: {
    displayName: 'Long-Term RSI Extremes',
    shortHash: 'CORE-LT',
    defaultSymbol: 'USD_CHF',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 79.4,
    promoted: true,
    profitFactor: 1.12,
    isProfitFactor: 1.04,
    oosProfitFactor: 1.36,
    sharpeRatio: 0.79,
    isSharpeRatio: 0.68,
    oosSharpeRatio: 1.12,
    winRatePct: 64.9,
    maxDrawdownPct: 7.8,
    totalTrades: 967,
    isTrades: 698,
    oosTrades: 269,
    netProfit: 89.28,
    totalReturnPct: 11.8,
    rSquared: 0.91,
    type: 'Mean Reversion',
    tradingStyle: 'MEAN_REVERSION',
    indicators: ['RSI(14)', 'SMA(100)', 'ATR(14)'],
    description: 'High-win-rate (64.9%) RSI(14) exhaustion mean-reversion filtered by 100-period SMA macro trend.',
    longSignalSummary: 'RSI(14) < 28 while Close > SMA(100) structural support',
    shortSignalSummary: 'RSI(14) > 72 while Close < SMA(100) structural resistance',
    exitSummary: 'RSI(14) normalizes to 50 or 2.0x ATR(14) stop loss',
    sessionSummary: 'Mon–Fri | 24h Session',
    recommendedSymbols: ['USD_CHF', 'EUR_USD', 'GBP_USD']
  },
  MonthWeekPhase: {
    displayName: 'Month-Week Phase Rebalance',
    shortHash: 'CORE-CAL',
    defaultSymbol: 'GBP_USD',
    primaryTimeframe: 'H1',
    tier: 'CORE',
    compositeScore: 82.1,
    promoted: true,
    profitFactor: 1.15,
    isProfitFactor: 1.15,
    oosProfitFactor: 1.13,
    sharpeRatio: 0.92,
    isSharpeRatio: 0.91,
    oosSharpeRatio: 0.94,
    winRatePct: 53.8,
    maxDrawdownPct: 13.0,
    totalTrades: 446,
    isTrades: 320,
    oosTrades: 126,
    netProfit: 158.74,
    totalReturnPct: 45.0,
    rSquared: 0.92,
    type: 'Calendar Flow',
    tradingStyle: 'SEASONALITY',
    indicators: ['Month-Turn Window', 'Week-of-Month Phase', 'ATR(14)'],
    description: 'Exploits predictable institutional month-end fixing and week-of-month corporate FX rebalancing flows on GBP/USD.',
    longSignalSummary: 'End-of-month / Week-1 institutional GBP accumulation window + volatility filter',
    shortSignalSummary: 'Mid-month USD dollar-funding drain window (Days 12–18)',
    exitSummary: 'Calendar window expiration or 2.2x ATR(14) protective stop',
    sessionSummary: 'Mon–Fri | Institutional Calendar Flow',
    recommendedSymbols: ['GBP_USD']
  },
  DateWindowSeasonal: {
    displayName: 'Date-Window Seasonal Overlay',
    shortHash: 'OVERLAY',
    defaultSymbol: 'GBP_JPY',
    primaryTimeframe: 'H1',
    tier: 'OVERLAY',
    compositeScore: 77.5,
    promoted: false,
    profitFactor: 7.36,
    isProfitFactor: 7.80,
    oosProfitFactor: 6.33,
    sharpeRatio: 1.45,
    isSharpeRatio: 1.48,
    oosSharpeRatio: 1.38,
    winRatePct: 81.2,
    maxDrawdownPct: 4.9,
    totalTrades: 16,
    isTrades: 11,
    oosTrades: 5,
    netProfit: 538.94,
    totalReturnPct: 53.9,
    rSquared: 0.95,
    type: 'Seasonality Overlay',
    tradingStyle: 'SEASONALITY',
    indicators: ['Japanese Fiscal Window', 'EMA(200)', 'ATR(14)'],
    description: 'High-conviction annual date-window overlay capturing Japanese fiscal-year repatriation and carry flows on GBP/JPY.',
    longSignalSummary: 'Annual macro date window open + Close > EMA(200)',
    shortSignalSummary: 'Fiscal year-end repatriation window + Close < EMA(200)',
    exitSummary: 'Date window close or trailing 2.5x ATR(14) stop',
    sessionSummary: 'Annual Macro Overlay (1–2 trades/year)',
    recommendedSymbols: ['GBP_JPY']
  },
  SeasonalCalendar: {
    displayName: 'Multi-Window Seasonal Calendar',
    shortHash: 'OVERLAY',
    defaultSymbol: 'GBP_USD',
    primaryTimeframe: 'H1',
    tier: 'OVERLAY',
    compositeScore: 76.0,
    promoted: false,
    profitFactor: 3.90,
    isProfitFactor: 10.39,
    oosProfitFactor: 1.38,
    sharpeRatio: 1.24,
    isSharpeRatio: 1.42,
    oosSharpeRatio: 0.95,
    winRatePct: 64.7,
    maxDrawdownPct: 9.0,
    totalTrades: 17,
    isTrades: 12,
    oosTrades: 5,
    netProfit: 352.94,
    totalReturnPct: 35.3,
    rSquared: 0.91,
    type: 'Seasonality Overlay',
    tradingStyle: 'SEASONALITY',
    indicators: ['Seasonal Calendar', 'SMA(100)', 'ATR(14)'],
    description: 'Annual seasonal calendar overlay capturing G10 treasury & corporate tax-season currency flows.',
    longSignalSummary: 'Spring/Q2 seasonal bullish window confirmed by SMA(100) slope',
    shortSignalSummary: 'Autumn/Q3 seasonal dollar strength window',
    exitSummary: 'Calendar window close or 2.0x ATR(14) stop',
    sessionSummary: 'Annual Macro Overlay (1–2 trades/year)',
    recommendedSymbols: ['GBP_USD', 'GBP_JPY']
  },
  GbpUsdSpringBullish: {
    displayName: 'GBP/USD Spring Dividend Flow',
    shortHash: 'OVERLAY',
    defaultSymbol: 'GBP_USD',
    primaryTimeframe: 'H1',
    tier: 'OVERLAY',
    compositeScore: 74.0,
    promoted: false,
    profitFactor: 2.66,
    isProfitFactor: 3.67,
    oosProfitFactor: 1.32,
    sharpeRatio: 1.10,
    isSharpeRatio: 1.25,
    oosSharpeRatio: 0.89,
    winRatePct: 70.6,
    maxDrawdownPct: 5.8,
    totalTrades: 17,
    isTrades: 12,
    oosTrades: 5,
    netProfit: 155.12,
    totalReturnPct: 15.5,
    rSquared: 0.89,
    type: 'Seasonality Overlay',
    tradingStyle: 'SEASONALITY',
    indicators: ['April UK Fiscal Window', 'EMA(100)', 'ATR(14)'],
    description: 'Captures April–May UK FTSE dividend & new tax-year sterling inflows on GBP/USD.',
    longSignalSummary: 'April UK tax-year entry window + price above H4 EMA(100)',
    shortSignalSummary: 'Long-only seasonal overlay',
    exitSummary: 'May window exit or 2.0x ATR(14) stop loss',
    sessionSummary: 'Annual Spring Window (Apr–May)',
    recommendedSymbols: ['GBP_USD']
  },
  UsdCadAutumnBullish: {
    displayName: 'USD/CAD Autumn Macro Window',
    shortHash: 'OVERLAY',
    defaultSymbol: 'USD_CAD',
    primaryTimeframe: 'H1',
    tier: 'OVERLAY',
    compositeScore: 73.2,
    promoted: false,
    profitFactor: 1.72,
    isProfitFactor: 1.27,
    oosProfitFactor: 4.44,
    sharpeRatio: 1.05,
    isSharpeRatio: 0.85,
    oosSharpeRatio: 1.52,
    winRatePct: 62.5,
    maxDrawdownPct: 6.5,
    totalTrades: 16,
    isTrades: 12,
    oosTrades: 4,
    netProfit: 92.25,
    totalReturnPct: 14.8,
    rSquared: 0.88,
    type: 'Seasonality Overlay',
    tradingStyle: 'SEASONALITY',
    indicators: ['Autumn Q4 Window', 'EMA(100)', 'ATR(14)'],
    description: 'Captures Sep–Nov autumn energy/commodity seasonal cycle and Q4 USD demand on USD/CAD.',
    longSignalSummary: 'Autumn seasonal window open + bullish USD/CAD structure',
    shortSignalSummary: 'Long-only seasonal overlay',
    exitSummary: 'Late-November window close or 2.0x ATR(14) stop loss',
    sessionSummary: 'Annual Autumn Window (Sep–Nov)',
    recommendedSymbols: ['USD_CAD', 'GBP_JPY']
  }
}

export function parseStrategyIdentity(id: string, defaultSymbol?: string, timeframe?: string): {
  displayName: string
  shortHash: string
  primaryTimeframe: string
} {
  if (BUILTIN_AUDITED_META[id]) {
    const b = BUILTIN_AUDITED_META[id]
    return {
      displayName: b.displayName,
      shortHash: b.shortHash,
      primaryTimeframe: timeframe || b.primaryTimeframe
    }
  }

  // Pattern for generated IDs like MgcM30Breakout49205, MnqM15MeanRev81824, EmdH1Pullback25691
  const genMatch = id.match(/^([A-Za-z0-9]+?)(M1|M5|M15|M30|H1|H4|D1)(Breakout|Momentum|MeanRev|Pullback|Trend)(\d+)$/i)
  if (genMatch) {
    const sym = (defaultSymbol || genMatch[1]).toUpperCase()
    const tf = (timeframe || genMatch[2]).toUpperCase()
    const rawArch = genMatch[3]
    const hash = genMatch[4]
    const archLabel =
      rawArch.toLowerCase() === 'meanrev'
        ? 'Mean Reversion'
        : rawArch.charAt(0).toUpperCase() + rawArch.slice(1).toLowerCase()
    return {
      displayName: `${sym} ${archLabel}`,
      shortHash: `#${hash}`,
      primaryTimeframe: tf
    }
  }

  if (id.startsWith('Harness_')) {
    const clean = id.replace(/^Harness_/, '').replace(/([A-Z])/g, ' $1').trim()
    return {
      displayName: clean,
      shortHash: 'HARNESS',
      primaryTimeframe: timeframe || 'H1'
    }
  }

  const humanized = id.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ')
  return {
    displayName: humanized,
    shortHash: id.slice(0, 8).toUpperCase(),
    primaryTimeframe: timeframe || 'H1'
  }
}

// Global shared state across component instances
const strategies = ref<Strategy[]>([])
const loading = ref<boolean>(false)
const error = ref<string | null>(null)

// Filters & View State
const selectedAssetClass = ref<AssetClass>('ALL')
const selectedFamily = ref<string>('ALL')
const selectedStyle = ref<string>('ALL')
const selectedSymbol = ref<string>('ALL')
const selectedTimeframe = ref<string>('ALL')
const selectedTier = ref<string>('ALL')
const showHarness = ref<boolean>(false)
const searchQuery = ref<string>('')
const sortBy = ref<CatalogSortField>('score')
const sortDir = ref<'desc' | 'asc'>('desc')
const viewMode = ref<CatalogViewMode>('table')
const deletedIds = ref<Set<string>>(new Set())

export function useStrategyCatalog() {
  const { getStrategies, getGeneratorDatabank, deleteStrategy: apiDeleteStrategy } = useControlPlane()

  const loadPersistedFilters = () => {
    try {
      const savedDel = localStorage.getItem(STORAGE_KEY_DELETED)
      if (savedDel) {
        const arr = JSON.parse(savedDel)
        if (Array.isArray(arr)) deletedIds.value = new Set(arr)
      }
      const saved = localStorage.getItem(STORAGE_KEY_FILTERS)
      if (saved) {
        const parsed = JSON.parse(saved)
        if (parsed.assetClass) selectedAssetClass.value = parsed.assetClass
        if (parsed.family) selectedFamily.value = parsed.family === 'SQ_IMPORTED' ? 'GENERATED' : parsed.family
        if (parsed.style) selectedStyle.value = parsed.style
        if (parsed.symbol) selectedSymbol.value = parsed.symbol
        if (parsed.timeframe) selectedTimeframe.value = parsed.timeframe
        if (parsed.tier) selectedTier.value = parsed.tier
        if (typeof parsed.showHarness === 'boolean') showHarness.value = parsed.showHarness
        if (parsed.sortBy) sortBy.value = parsed.sortBy
        if (parsed.sortDir) sortDir.value = parsed.sortDir
        if (parsed.viewMode) viewMode.value = parsed.viewMode
      }
    } catch {
      // Ignore storage errors
    }
  }

  const persistFilters = () => {
    try {
      localStorage.setItem(
        STORAGE_KEY_FILTERS,
        JSON.stringify({
          assetClass: selectedAssetClass.value,
          family: selectedFamily.value,
          style: selectedStyle.value,
          symbol: selectedSymbol.value,
          timeframe: selectedTimeframe.value,
          tier: selectedTier.value,
          showHarness: showHarness.value,
          sortBy: sortBy.value,
          sortDir: sortDir.value,
          viewMode: viewMode.value
        })
      )
    } catch {
      // Ignore storage errors
    }
  }

  watch(
    [
      selectedAssetClass,
      selectedFamily,
      selectedStyle,
      selectedSymbol,
      selectedTimeframe,
      selectedTier,
      showHarness,
      sortBy,
      sortDir,
      viewMode
    ],
    persistFilters
  )

  const inferAssetClass = (symbol: string): AssetClass => {
    if (!symbol) return 'FOREX'
    const sym = symbol.toUpperCase().replace('/', '_')
    if (
      sym.startsWith('MES') ||
      sym.startsWith('MNQ') ||
      sym.startsWith('M2K') ||
      sym.startsWith('EMD') ||
      sym.startsWith('MGC') ||
      sym.startsWith('GC') ||
      sym.startsWith('ES') ||
      sym.startsWith('NQ')
    ) {
      return 'FUTURES'
    }
    if (['IJR', 'VB', 'SCHA', 'PLTR', 'TSLA', 'SOXL', 'AAPL', 'NVDA', 'SPY', 'QQQ', 'IWM'].includes(sym)) {
      return 'EQUITY'
    }
    if (sym.includes('XAU') || sym.includes('XAG') || sym.includes('BRENT') || sym.includes('WTICO')) {
      return 'COMMODITIES'
    }
    return 'FOREX'
  }

  const inferAssetClassList = (symbol: string): string[] => {
    const ac = inferAssetClass(symbol)
    if (ac === 'COMMODITIES') return ['COMMODITIES', 'FOREX']
    return [ac]
  }

  const fetchCatalog = async (_force = false) => {
    const showSpinner = strategies.value.length === 0
    if (showSpinner) loading.value = true
    error.value = null
    try {
      const list = await getStrategies()
      const needsDatabankFallback = list.some(
        (s) => s.family === 'GENERATED' && s.compositeScore == null
      )
      const databankList = needsDatabankFallback ? await getGeneratorDatabank() : []

      const candidateMap = new Map<string, any>()
      for (const c of databankList) {
        if (c && c.id) {
          candidateMap.set(c.id, c)
        }
      }

      strategies.value = list
        .filter((s) => !deletedIds.value.has(s.id))
        .map((s) => {
          const cand = candidateMap.get(s.id)
          const builtIn = BUILTIN_AUDITED_META[s.id]
          const resolvedSymbol = cand?.symbol || s.defaultSymbol || builtIn?.defaultSymbol || 'EUR_USD'
          const resolvedTf =
            cand?.timeframe ||
            s.timeframe ||
            builtIn?.primaryTimeframe ||
            (s.timeframeSuitability && s.timeframeSuitability[0]) ||
            'H1'
          const identity = parseStrategyIdentity(s.id, resolvedSymbol, resolvedTf)

          const rawInd = cand?.indicators?.length
            ? cand.indicators
            : builtIn?.indicators?.length
              ? builtIn.indicators
              : s.indicators || []
          // Deduplicate indicators cleanly
          const uniqueIndicators = Array.from(new Set(rawInd as string[]))

          const pf = cand?.profitFactor ?? s.profitFactor ?? builtIn?.profitFactor
          const netProfit = cand?.netProfit ?? s.netProfit ?? builtIn?.netProfit
          const totalTrades = cand?.totalTrades ?? s.totalTrades ?? builtIn?.totalTrades
          const curve =
            cand?.equityCurve?.length
              ? cand.equityCurve
              : s.equityCurve?.length
                ? s.equityCurve
                : undefined

          const entryRuleText =
            cand?.longSignalSummary || cand?.shortSignalSummary || 'Rule-based entry'
          const exitRuleText = cand?.exitSummary || 'Standard stop/target'

          return {
            ...s,
            displayName: identity.displayName,
            shortHash: identity.shortHash,
            defaultSymbol: resolvedSymbol,
            primaryTimeframe: identity.primaryTimeframe,
            timeframe: resolvedTf,
            assetClasses:
              s.assetClasses && s.assetClasses.length > 0
                ? s.assetClasses
                : inferAssetClassList(resolvedSymbol),
            tradingStyle: cand?.archetype || builtIn?.tradingStyle || s.tradingStyle || 'TREND_FOLLOWING',
            type: builtIn?.type || cand?.archetype || s.type || 'Rule-Based',
            timeframeSuitability: cand?.timeframe
              ? [cand.timeframe]
              : s.timeframeSuitability || [resolvedTf],
            recommendedSymbols: builtIn?.recommendedSymbols || s.recommendedSymbols || [resolvedSymbol],
            complexity: s.complexity || 'INTERMEDIATE',
            indicators: uniqueIndicators,
            description:
              builtIn?.description ||
              (cand
                ? `${cand.symbol} ${cand.timeframe} ${cand.archetype} — Entry: ${entryRuleText} · Exit: ${exitRuleText}`
                : s.description || ''),
            tier: cand?.tier ?? s.tier ?? builtIn?.tier ?? (s.family === 'HARNESS' ? 'SYSTEM' : 'QUALIFIED'),
            compositeScore: cand?.compositeScore ?? s.compositeScore ?? builtIn?.compositeScore ?? 0,
            promoted: cand?.promoted ?? s.promoted ?? builtIn?.promoted ?? Boolean(s.deployedMode),
            deployedMode: s.deployedMode,
            executionLabel: s.executionLabel,
            yearSpec: cand?.yearSpec ?? s.yearSpec ?? '2010-2026',
            direction: cand?.direction ?? s.direction ?? 'BOTH',
            entryOrderType: cand?.entryOrderType ?? s.entryOrderType ?? 'MARKET',
            totalTrades,
            isTrades: cand?.isTrades ?? s.isTrades ?? builtIn?.isTrades,
            oosTrades: cand?.oosTrades ?? s.oosTrades ?? builtIn?.oosTrades,
            netProfit,
            isNetProfit: cand?.isNetProfit ?? s.isNetProfit,
            oosNetProfit: cand?.oosNetProfit ?? s.oosNetProfit,
            totalReturnPct: cand?.totalReturnPct ?? s.totalReturnPct ?? builtIn?.totalReturnPct,
            profitFactor: pf,
            isProfitFactor: cand?.isProfitFactor ?? s.isProfitFactor ?? builtIn?.isProfitFactor,
            oosProfitFactor: cand?.oosProfitFactor ?? s.oosProfitFactor ?? builtIn?.oosProfitFactor,
            sharpeRatio: cand?.sharpeRatio ?? s.sharpeRatio ?? builtIn?.sharpeRatio,
            isSharpeRatio: cand?.isSharpeRatio ?? s.isSharpeRatio ?? builtIn?.isSharpeRatio,
            oosSharpeRatio: cand?.oosSharpeRatio ?? s.oosSharpeRatio ?? builtIn?.oosSharpeRatio,
            winRatePct: cand?.winRatePct ?? s.winRatePct ?? builtIn?.winRatePct,
            maxDrawdownPct: cand?.maxDrawdownPct ?? s.maxDrawdownPct ?? builtIn?.maxDrawdownPct,
            retDdRatio:
              cand?.retDdRatio ??
              s.retDdRatio ??
              (builtIn && builtIn.maxDrawdownPct > 0
                ? Math.round((builtIn.totalReturnPct / builtIn.maxDrawdownPct) * 100) / 100
                : undefined),
            rSquared: cand?.rSquared ?? s.rSquared ?? builtIn?.rSquared,
            mcVar95: cand?.mcVar95 ?? s.mcVar95,
            mcLossProbPct: cand?.mcLossProbPct ?? s.mcLossProbPct ?? 0,
            wfePct: cand?.wfePct ?? s.wfePct,
            longSignalSummary: cand?.longSignalSummary ?? s.longSignalSummary ?? builtIn?.longSignalSummary,
            shortSignalSummary: cand?.shortSignalSummary ?? s.shortSignalSummary ?? builtIn?.shortSignalSummary,
            entrySummary: cand?.entrySummary ?? s.entrySummary,
            exitSummary: cand?.exitSummary ?? s.exitSummary ?? builtIn?.exitSummary,
            sessionSummary: cand?.sessionSummary ?? s.sessionSummary ?? builtIn?.sessionSummary,
            equityCurve: curve,
            oosStartIndex:
              cand?.oosStartIndex ??
              s.oosStartIndex ??
              (curve ? Math.floor(curve.length * 0.7) : undefined),
            javaSourceCode: cand?.javaSourceCode ?? s.javaSourceCode
          }
        })
    } catch (err: any) {
      error.value = err?.message || 'Failed to fetch strategy catalog'
    } finally {
      if (showSpinner) loading.value = false
    }
  }

  const removeStrategy = async (id: string) => {
    const strat = strategies.value.find((s) => s.id === id)
    if (strat?.family === 'GENERATED' || strat?.family === 'SQ_IMPORTED') {
      await apiDeleteStrategy(id)
    } else {
      deletedIds.value.add(id)
      try {
        localStorage.setItem(STORAGE_KEY_DELETED, JSON.stringify(Array.from(deletedIds.value)))
      } catch {
        // Ignore storage errors
      }
    }
    strategies.value = strategies.value.filter((s) => s.id !== id)
  }

  const restoreDeletedStrategies = async () => {
    deletedIds.value.clear()
    try {
      localStorage.removeItem(STORAGE_KEY_DELETED)
    } catch {
      // Ignore storage errors
    }
    await fetchCatalog(true)
  }

  // Multi-Asset Basket Isolation Guard (Red Team Hardened)
  const isCompatibleBasket = (
    symbols: string[]
  ): { compatible: boolean; primaryAssetClass: AssetClass; reason?: string } => {
    if (!symbols || symbols.length <= 1) {
      const ac = symbols && symbols.length === 1 ? inferAssetClass(symbols[0]) : 'FOREX'
      return { compatible: true, primaryAssetClass: ac }
    }
    const firstAc = inferAssetClass(symbols[0])
    for (let i = 1; i < symbols.length; i++) {
      const currentAc = inferAssetClass(symbols[i])
      if (firstAc !== currentAc) {
        return {
          compatible: false,
          primaryAssetClass: firstAc,
          reason: `Mixed asset classes detected (${firstAc} and ${currentAc}). Discrete futures contracts cannot mix with fractional forex lots in a single run.`
        }
      }
    }
    return { compatible: true, primaryAssetClass: firstAc }
  }

  const compareFinite = (aVal: number | undefined | null, bVal: number | undefined | null, dir: number): number => {
    const aFinite = aVal != null && Number.isFinite(aVal)
    const bFinite = bVal != null && Number.isFinite(bVal)
    if (!aFinite && !bFinite) return 0
    if (!aFinite) return 1
    if (!bFinite) return -1
    return (aVal! - bVal!) * dir
  }

  const sortStrategiesList = (list: Strategy[]): Strategy[] => {
    const dir = sortDir.value === 'asc' ? 1 : -1
    return [...list].sort((a, b) => {
      switch (sortBy.value) {
        case 'score':
          return compareFinite(a.compositeScore, b.compositeScore, dir)
        case 'pf':
          return compareFinite(a.profitFactor, b.profitFactor, dir)
        case 'oosPf':
          return compareFinite(a.oosProfitFactor, b.oosProfitFactor, dir)
        case 'sharpe':
          return compareFinite(a.sharpeRatio, b.sharpeRatio, dir)
        case 'dd':
          // Lower drawdown is better when sorting desc
          return compareFinite(b.maxDrawdownPct, a.maxDrawdownPct, dir)
        case 'winRate':
          return compareFinite(a.winRatePct, b.winRatePct, dir)
        case 'trades':
          return compareFinite(a.totalTrades, b.totalTrades, dir)
        case 'netProfit':
          return compareFinite(a.netProfit, b.netProfit, dir)
        case 'name':
          return (a.displayName || a.id).localeCompare(b.displayName || b.id) * dir
        default:
          return 0
      }
    })
  }

  const visibleBaseStrategies = computed(() => {
    if (showHarness.value || selectedFamily.value === 'HARNESS') {
      return strategies.value
    }
    return strategies.value.filter((s) => s.family !== 'HARNESS')
  })

  const filteredStrategies = computed(() => {
    let result = visibleBaseStrategies.value

    // Asset Class filter
    if (selectedAssetClass.value !== 'ALL') {
      result = result.filter(
        (s) =>
          s.assetClasses?.includes(selectedAssetClass.value) ||
          (selectedAssetClass.value === 'FOREX' && s.assetClasses?.includes('COMMODITIES'))
      )
    }

    // Family filter
    if (selectedFamily.value !== 'ALL') {
      if (selectedFamily.value === 'GENERATED' || selectedFamily.value === 'SQ_IMPORTED') {
        result = result.filter((s) => s.family === 'GENERATED' || s.family === 'SQ_IMPORTED')
      } else {
        result = result.filter((s) => s.family === selectedFamily.value)
      }
    }

    // Style filter
    if (selectedStyle.value !== 'ALL') {
      result = result.filter((s) => s.tradingStyle === selectedStyle.value)
    }

    // Symbol filter
    if (selectedSymbol.value !== 'ALL') {
      const sym = selectedSymbol.value.toUpperCase().replace('/', '_')
      result = result.filter(
        (s) => s.defaultSymbol?.toUpperCase().replace('/', '_') === sym
      )
    }

    // Timeframe filter
    if (selectedTimeframe.value !== 'ALL') {
      result = result.filter(
        (s) => (s.primaryTimeframe || s.timeframe || 'H1').toUpperCase() === selectedTimeframe.value
      )
    }

    // Tier / Status filter
    if (selectedTier.value !== 'ALL') {
      if (selectedTier.value === 'DEPLOYED') {
        result = result.filter((s) => Boolean(s.deployedMode))
      } else if (selectedTier.value === 'PROMOTED') {
        result = result.filter((s) => Boolean(s.promoted || s.deployedMode))
      } else {
        result = result.filter((s) => s.tier === selectedTier.value)
      }
    }

    // Search query (fuzzy multi-field search)
    if (searchQuery.value.trim()) {
      const q = searchQuery.value.toLowerCase().trim()
      result = result.filter((s) => {
        const idMatch = s.id.toLowerCase().includes(q)
        const nameMatch = s.displayName?.toLowerCase().includes(q) || false
        const descMatch = s.description?.toLowerCase().includes(q) || false
        const typeMatch = s.type?.toLowerCase().includes(q) || false
        const styleMatch = s.tradingStyle?.toLowerCase().includes(q) || false
        const tierMatch = s.tier?.toLowerCase().includes(q) || false
        const indMatch = s.indicators?.some((i) => i.toLowerCase().includes(q)) || false
        const symMatch = s.recommendedSymbols?.some((sym) => sym.toLowerCase().includes(q)) || false
        const defaultSymMatch = s.defaultSymbol?.toLowerCase().includes(q) || false
        const ruleMatch =
          s.longSignalSummary?.toLowerCase().includes(q) ||
          s.shortSignalSummary?.toLowerCase().includes(q) ||
          false
        return (
          idMatch ||
          nameMatch ||
          descMatch ||
          typeMatch ||
          styleMatch ||
          tierMatch ||
          indMatch ||
          symMatch ||
          defaultSymMatch ||
          ruleMatch
        )
      })
    }

    return sortStrategiesList(result)
  })

  const groupedStrategies = computed(() => {
    const sqGroup: Strategy[] = []
    const futuresGroup: Strategy[] = []
    const propGroup: Strategy[] = []
    const ltGroup: Strategy[] = []
    const seasonalGroup: Strategy[] = []
    const harnessGroup: Strategy[] = []

    for (const s of filteredStrategies.value) {
      if (s.family === 'HARNESS') {
        harnessGroup.push(s)
      } else if (s.family === 'SQ_IMPORTED' || s.family === 'GENERATED' || s.id.startsWith('Sq')) {
        sqGroup.push(s)
      } else if (
        s.assetClasses?.includes('FUTURES') &&
        !s.assetClasses?.includes('FOREX') &&
        s.family === 'LONG_TERM'
      ) {
        futuresGroup.push(s)
      } else if (s.family === 'PROP') {
        propGroup.push(s)
      } else if (s.family === 'LONG_TERM') {
        ltGroup.push(s)
      } else {
        seasonalGroup.push(s)
      }
    }

    const groups: Record<string, Strategy[]> = {}
    if (sqGroup.length > 0) groups['🧬 Generated & Evolved Futures Models'] = sqGroup
    if (futuresGroup.length > 0) groups['⚡ CME Futures Systematic (IBKR)'] = futuresGroup
    if (propGroup.length > 0) groups['🏛️ Prop Mechanical FX Desk (OANDA)'] = propGroup
    if (ltGroup.length > 0) groups['📈 Long-Term Structural Multi-Asset'] = ltGroup
    if (seasonalGroup.length > 0) groups['🗓️ Calendar & Seasonality Overlays'] = seasonalGroup
    if (harnessGroup.length > 0) groups['🧪 System Validation Harness'] = harnessGroup
    return groups
  })

  const getStrategy = (id: string): Strategy | undefined => {
    return strategies.value.find((s) => s.id === id)
  }

  const getRecommendedInstruments = (strategyId: string): string[] => {
    const strat = getStrategy(strategyId)
    if (!strat) return []
    return strat.recommendedSymbols || [strat.defaultSymbol]
  }

  const getCompatibleStrategiesForSymbol = (symbol: string): Strategy[] => {
    const ac = inferAssetClass(symbol)
    return visibleBaseStrategies.value.filter((s) => s.assetClasses?.includes(ac))
  }

  const resetFilters = () => {
    selectedAssetClass.value = 'ALL'
    selectedFamily.value = 'ALL'
    selectedStyle.value = 'ALL'
    selectedSymbol.value = 'ALL'
    selectedTimeframe.value = 'ALL'
    selectedTier.value = 'ALL'
    searchQuery.value = ''
    if (deletedIds.value.size > 0) {
      restoreDeletedStrategies()
    }
    persistFilters()
  }

  loadPersistedFilters()

  return {
    strategies,
    visibleBaseStrategies,
    loading,
    error,
    selectedAssetClass,
    selectedFamily,
    selectedStyle,
    selectedSymbol,
    selectedTimeframe,
    selectedTier,
    showHarness,
    searchQuery,
    sortBy,
    sortDir,
    viewMode,
    filteredStrategies,
    groupedStrategies,
    fetchCatalog,
    removeStrategy,
    restoreDeletedStrategies,
    getStrategy,
    inferAssetClass,
    isCompatibleBasket,
    getRecommendedInstruments,
    getCompatibleStrategiesForSymbol,
    resetFilters
  }
}
