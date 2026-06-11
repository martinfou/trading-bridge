export interface Strategy {
  id: string
  family: string
  defaultSymbol: string
  deployedMode?: string
  executionLabel?: string
  brokerAccountId?: string
  type?: string
  indicators?: string[]
  description?: string
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
}

export interface PromoteGateThresholds {
  minTrades: number
  maxDrawdownPct: number
  minReturnPct: number
  goldenReturnTolerancePct: number
  paperDaysBeforeLive: number
  validationModuleEnabled: boolean
}
