export interface Strategy {
  id: string
  family: string
  defaultSymbol: string
  deployedMode?: string
  executionLabel?: string
  brokerAccountId?: string
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
}

export interface Bar {
  time: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}
