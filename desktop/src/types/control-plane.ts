export interface Strategy {
  id: string
  displayName?: string
  shortHash?: string
  family: string
  defaultSymbol: string
  primaryTimeframe?: string
  deployedMode?: string
  executionLabel?: string
  executionLabelMeta?: {
    id: string
    displayName: string
    category: string
    badgeBackgroundColor: string
    badgeTextColor: string
    brokerBacked: boolean
    countsTowardPaperPeriod: boolean
    stubWarning?: boolean
  }
  brokerAccountId?: string
  type?: string
  indicators?: string[]
  description?: string
  assetClasses?: string[]
  tradingStyle?: string
  timeframeSuitability?: string[]
  recommendedSymbols?: string[]
  complexity?: string
  // Quantitative scorecard & generator databank enrichment
  tier?: string
  compositeScore?: number
  promoted?: boolean
  timeframe?: string
  yearSpec?: string
  direction?: string
  entryOrderType?: string
  totalTrades?: number
  isTrades?: number
  oosTrades?: number
  netProfit?: number
  isNetProfit?: number
  oosNetProfit?: number
  totalReturnPct?: number
  profitFactor?: number
  isProfitFactor?: number
  oosProfitFactor?: number
  sharpeRatio?: number
  isSharpeRatio?: number
  oosSharpeRatio?: number
  winRatePct?: number
  maxDrawdownPct?: number
  retDdRatio?: number
  rSquared?: number
  mcVar95?: number
  mcLossProbPct?: number
  wfePct?: number
  longSignalSummary?: string
  shortSignalSummary?: string
  entrySummary?: string
  exitSummary?: string
  sessionSummary?: string
  equityCurve?: number[]
  oosStartIndex?: number
  javaSourceCode?: string
  auditVerdict?: string
  auditNote?: string
}

export type AssetClass = 'ALL' | 'FUTURES' | 'FOREX' | 'EQUITY' | 'COMMODITIES'

export interface CostPreset {
  id: string
  name: string
  assetClass: AssetClass
  lotSize: number
  unitLabel: string
  capital: number
  commissionPerTrade: number
  slippagePct: number
  description: string
}

export interface RunConfig {
  strategyId: string
  symbol: string
  mode: 'BACKTEST' | 'PAPER' | 'LIVE'
  barsSource: { type: 'year', year?: number | string } | { type: 'ci' }
  capital: number
  lotSize?: number
  commissionPerTrade?: number
  slippagePct?: number
  dataTimeframe?: string
  strategyTimeframe?: string
  executionLabel?: string
  brokerAccountId?: string
  dailyLossLimitPct?: number
  weeklyLossLimitPct?: number
}

export interface RunSummary {
  runId: string
  strategyId: string
  symbol: string
  status: string
  startedAt?: string
  completedAt?: string
  mode?: string
  lane?: string
  inTrade?: boolean
  tradesCount?: number
  executionLabel?: string
  executionLabelMeta?: {
    id: string
    displayName: string
    category: string
    badgeBackgroundColor: string
    badgeTextColor: string
    brokerBacked: boolean
    countsTowardPaperPeriod: boolean
    stubWarning?: boolean
  }
}

export interface RunResult {
  runId: string
  strategyId: string
  symbol: string
  status: string
  mode?: string
  error?: string
  startedAt?: string
  completedAt?: string
  configSnapshot?: {
    mode: string
    capital: number
    commissionPerTrade?: number
    slippagePct?: number
    barsSource?: string
    strategyTimeframe?: string
    dataTimeframe?: string
  }
  result?: {
    totalTrades: number
    totalReturnPct: number
    finalEquity: number
    maxDrawdownPct: number
    sharpeRatio: number
    profitFactor: number
    winRatePct: number
    totalCommission: number
    totalSlippage: number
    periodStart?: string
    periodEnd?: string
  }
  trades?: Trade[]
  equityCurve?: number[]
  pendingOrders?: any[]
  indicators?: string[]
}

export interface Trade {
  symbol: string
  side: 'BUY' | 'SELL'
  entryPrice: number
  exitPrice: number
  quantity: number
  entryTime: string
  exitTime: string
  pnl: number
  stopLoss?: number
  takeProfit?: number
}

export interface Bar {
  time: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface BrokerAccount {
  id: string
  provider: string
  maskedAccountId: string
  configured: boolean
  host?: string
  port?: number
  defaultRestUrl?: string
}

export interface BrokerVenuePosition {
  symbol: string
  side: string
  quantity: number
  entryPrice: number
  stopLoss?: number
  takeProfit?: number
  entryTime?: string
}

export interface BrokerVenueTrade {
  id: string
  orderId?: string
  venueId: string
  provider: 'OANDA' | 'IBKR' | string
  maskedAccountId?: string
  currency?: string
  symbol: string
  state: 'OPEN' | 'CLOSED' | 'FILLED' | string
  side: 'BUY' | 'SELL' | string
  quantity: number
  entryPrice: number
  exitPrice?: number | null
  stopLoss?: number
  takeProfit?: number
  realizedPnL?: number
  unrealizedPnL?: number
  financing?: number
  openTime?: string
  closeTime?: string
  strategyTag?: string
}

export interface BrokerBalanceSnapshot {
  id: string
  provider: 'OANDA' | 'IBKR' | string
  maskedAccountId?: string
  configured?: boolean
  connected?: boolean
  environment?: 'PAPER' | 'LIVE' | string
  endpoint?: string
  host?: string
  port?: number
  balance: number
  equity: number
  currency: string
  unrealizedPnL?: number
  initialMargin?: number
  maintenanceMargin?: number
  availableFunds?: number
  buyingPower?: number
  marginUtilizationPct?: number
  dayTradesRemaining?: number
  pdtRestricted?: boolean
  positionsCount?: number
  positions?: BrokerVenuePosition[]
  tradesCount?: number
  trades?: BrokerVenueTrade[]
  error?: string
  updatedAt?: string
}

export interface PromoteGateThresholds {
  minTrades: number
  maxDrawdownPct: number
  minReturnPct: number
  goldenReturnTolerancePct: number
  paperDaysBeforeLive: number
  validationModuleEnabled: boolean
}

export interface ReconciliationAnomaly {
  type: 'MISSING_LIVE' | 'GHOST_LIVE' | 'TIME_DRIFT' | 'PRICE_DRIFT'
  orderId?: string
  message: string
  deltaPrice: number
  deltaTimeMs: number
}

export interface WeeklyStat {
  weekId: string
  totalPnl: number
  totalTrades: number
  winningTrades: number
  losingTrades: number
  winRatePct: number
  profitFactor: number
  sharpeRatio: number
  maxDrawdownPct: number
  startCapital: number
  endCapital: number
}

export interface InstrumentDefinition {
  symbol: string
  name: string
  assetClass: 'FOREX' | 'FUTURES' | 'EQUITIES' | string
  pointValue: number
  tickSize: number
  currency: string
  isCustom: boolean
  providerTicker: string
}
