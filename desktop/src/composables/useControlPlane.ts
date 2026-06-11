import { ref, reactive } from 'vue'
import { useControlPlaneConfig } from './controlPlaneConfig'
import { useStatusBar } from './useStatusBar'
import type { Strategy, RunConfig, RunResult, Trade, RunSummary, Bar, BrokerAccount, PromoteGateThresholds } from '@/types/control-plane'

interface StartRunResponse {
  runId: string
  status: string
}

interface ApiResponse<T> {
  data: T | null
  error: string | null
  loading: boolean
}

async function apiGet<T>(path: string, baseUrl: string): Promise<T> {
  try {
    const resp = await fetch(`${baseUrl}${path}`, {
      headers: { Accept: 'application/json' },
    })
    if (!resp.ok) {
      const body = await resp.text().catch(() => '')
      const errMsg = `GET ${path} ${resp.status}: ${body.slice(0, 200)}`
      useStatusBar().setStatus(errMsg, 'error')
      throw new Error(errMsg)
    }
    return await resp.json()
  } catch (err: any) {
    if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError')) {
      const cleanMsg = `Connection to Control Plane at ${baseUrl} failed.`
      useStatusBar().setStatus(cleanMsg, 'error')
    } else {
      useStatusBar().setStatus(err.message, 'error')
    }
    throw err
  }
}

async function apiPost<T>(path: string, body: unknown, baseUrl: string): Promise<T> {
  try {
    const resp = await fetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    })
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      const errMsg = `POST ${path} ${resp.status}: ${text.slice(0, 200)}`
      useStatusBar().setStatus(errMsg, 'error')
      throw new Error(errMsg)
    }
    return await resp.json()
  } catch (err: any) {
    if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError')) {
      const cleanMsg = `Connection to Control Plane at ${baseUrl} failed.`
      useStatusBar().setStatus(cleanMsg, 'error')
    } else {
      useStatusBar().setStatus(err.message, 'error')
    }
    throw err
  }
}

async function apiDelete<T>(path: string, baseUrl: string): Promise<T> {
  try {
    const resp = await fetch(`${baseUrl}${path}`, {
      method: 'DELETE',
      headers: { Accept: 'application/json' },
    })
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      const errMsg = `DELETE ${path} ${resp.status}: ${text.slice(0, 200)}`
      useStatusBar().setStatus(errMsg, 'error')
      throw new Error(errMsg)
    }
    return await resp.json()
  } catch (err: any) {
    if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError')) {
      const cleanMsg = `Connection to Control Plane at ${baseUrl} failed.`
      useStatusBar().setStatus(cleanMsg, 'error')
    } else {
      useStatusBar().setStatus(err.message, 'error')
    }
    throw err
  }
}

export function useControlPlane() {
  const { controlPlaneUrl } = useControlPlaneConfig()
  const error = ref<string | null>(null)
  const loading = ref(false)

  function wrap<T>(fn: () => Promise<T>): ApiResponse<T> {
    const result: ApiResponse<T> = reactive({ data: null, error: null, loading: true })
    loading.value = true
    error.value = null
    fn()
      .then((d) => {
        result.data = d
        result.loading = false
      })
      .catch((e: Error) => {
        result.error = e.message
        error.value = e.message
        result.loading = false
      })
      .finally(() => {
        loading.value = false
        result.loading = false
      })
    return result
  }

  async function startRun(config: RunConfig): Promise<StartRunResponse> {
    loading.value = true
    error.value = null
    try {
      const barsSource =
        config.barsSource.type === 'ci'
          ? { type: 'ci' }
          : { type: 'year', year: config.barsSource.year?.toString() }

      return await apiPost<StartRunResponse>(
        '/api/runs',
        {
          strategyId: config.strategyId,
          symbol: config.symbol,
          mode: config.mode,
          barsSource,
          capital: config.capital,
          lotSize: config.lotSize,
          commissionPerTrade: config.commissionPerTrade,
          slippagePct: config.slippagePct,
          dataTimeframe: config.dataTimeframe,
          strategyTimeframe: config.strategyTimeframe,
          executionLabel: config.executionLabel,
          brokerAccountId: config.brokerAccountId,
          dailyLossLimitPct: config.dailyLossLimitPct,
          weeklyLossLimitPct: config.weeklyLossLimitPct,
        },
        controlPlaneUrl.value,
      )
    } catch (e: any) {
      error.value = e.message
      throw e
    } finally {
      loading.value = false
    }
  }

  async function getRun(runId: string): Promise<RunResult> {
    return apiGet<RunResult>(`/api/runs/${runId}`, controlPlaneUrl.value)
  }

  async function getStrategies(): Promise<Strategy[]> {
    const data = await apiGet<{ strategies: Strategy[] }>('/api/strategies', controlPlaneUrl.value)
    return data.strategies
  }

  async function getTrades(runId: string): Promise<Trade[]> {
    const data = await apiGet<{ trades: Trade[] }>(`/api/runs/${runId}/trades`, controlPlaneUrl.value)
    return data.trades
  }

  async function getEquityCurve(runId: string): Promise<number[]> {
    const data = await apiGet<{ equityCurve: number[] }>(
      `/api/runs/${runId}/equity-curve`,
      controlPlaneUrl.value,
    )
    return data.equityCurve
  }

  async function getBars(runId: string): Promise<Bar[]> {
    const data = await apiGet<{ bars: Bar[] }>(`/api/runs/${runId}/bars`, controlPlaneUrl.value)
    return data.bars
  }

  const getSentiment = async (instrument: string): Promise<any> => {
    return apiGet<any>(`/api/sentiment/${instrument}`, controlPlaneUrl.value)
  }

  async function listRuns(): Promise<RunSummary[]> {
    const data = await apiGet<{ runs: RunSummary[] }>('/api/runs', controlPlaneUrl.value)
    return data.runs
  }

  async function getControlSummary(): Promise<any> {
    return apiGet<any>('/api/control/summary', controlPlaneUrl.value)
  }

  async function killStrategy(
    strategyId: string,
    actor: string = 'operator',
    reason: string = 'Deactivated via control room'
  ): Promise<any> {
    return apiPost<any>(`/api/strategies/${strategyId}/kill`, { actor, reason }, controlPlaneUrl.value)
  }

  async function getBrokerAccounts(): Promise<BrokerAccount[]> {
    const data = await apiGet<{ accounts: BrokerAccount[] }>('/api/broker-accounts', controlPlaneUrl.value)
    return data.accounts
  }

  async function getBrokerBalances(): Promise<any[]> {
    const data = await apiGet<{ balances: any[] }>('/api/broker-accounts/balances', controlPlaneUrl.value)
    return data.balances
  }

  async function promoteStrategy(
    strategyId: string,
    targetMode: 'PAPER' | 'LIVE',
    runId?: string,
    executionLabel?: string,
    brokerAccountId?: string
  ): Promise<any> {
    const path = `/api/strategies/${strategyId}/promote`
    try {
      const resp = await fetch(`${controlPlaneUrl.value}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ targetMode, runId, executionLabel, brokerAccountId }),
      })
      if (resp.status === 422 || resp.ok) {
        return resp.json()
      }
      const text = await resp.text().catch(() => '')
      throw new Error(`POST ${path} ${resp.status}: ${text.slice(0, 200)}`)
    } catch (e: any) {
      useStatusBar().setStatus(e.message, 'error')
      throw e
    }
  }

  async function getPromoteGates(): Promise<PromoteGateThresholds> {
    return apiGet<PromoteGateThresholds>('/api/promote-gates/thresholds', controlPlaneUrl.value)
  }

  async function updatePromoteGates(thresholds: PromoteGateThresholds): Promise<PromoteGateThresholds> {
    return apiPost<PromoteGateThresholds>('/api/promote-gates/thresholds', thresholds, controlPlaneUrl.value)
  }

  async function getHistoricalDataStatus(tf: string): Promise<any> {
    return apiGet<any>(`/api/historical-data/status?tf=${tf}`, controlPlaneUrl.value)
  }

  async function downloadHistoricalData(params: {
    pair?: string
    year?: number
    tf?: string
    syncMode?: boolean
  }): Promise<any> {
    return apiPost<any>('/api/historical-data/download', params, controlPlaneUrl.value)
  }

  async function deleteHistoricalData(params: {
    pair: string
    year: number
    tf: string
  }): Promise<any> {
    return apiPost<any>('/api/historical-data/delete', params, controlPlaneUrl.value)
  }

  async function saveBrokerAccounts(accounts: any[]): Promise<any> {
    return apiPost<any>('/api/broker-accounts', { accounts }, controlPlaneUrl.value)
  }

  async function testBrokerAccount(account: any): Promise<any> {
    return apiPost<any>('/api/broker-accounts/test', account, controlPlaneUrl.value)
  }

  async function getMonteCarlo(runId: string, runs = 1000, blockSize = 3): Promise<any> {
    return apiGet<any>(`/api/runs/${runId}/monte-carlo?runs=${runs}&blockSize=${blockSize}`, controlPlaneUrl.value)
  }

  async function listBacktests(filters?: {
    symbol?: string
    strategyId?: string
    minSharpe?: number
    minProfitFactor?: number
    sortBy?: string
    sortOrder?: string
    limit?: number
    offset?: number
  }): Promise<{ total: number; limit: number; offset: number; items: any[] }> {
    const params = new URLSearchParams()
    if (filters) {
      if (filters.symbol) params.append('symbol', filters.symbol)
      if (filters.strategyId) params.append('strategyId', filters.strategyId)
      if (filters.minSharpe != null) params.append('minSharpe', filters.minSharpe.toString())
      if (filters.minProfitFactor != null) params.append('minProfitFactor', filters.minProfitFactor.toString())
      if (filters.sortBy) params.append('sortBy', filters.sortBy)
      if (filters.sortOrder) params.append('sortOrder', filters.sortOrder)
      if (filters.limit != null) params.append('limit', filters.limit.toString())
      if (filters.offset != null) params.append('offset', filters.offset.toString())
    }
    const query = params.toString() ? `?${params.toString()}` : ''
    return apiGet<{ total: number; limit: number; offset: number; items: any[] }>(
      `/api/backtests${query}`,
      controlPlaneUrl.value
    )
  }

  async function getBacktestDetails(runId: string): Promise<any> {
    return apiGet<any>(`/api/backtests/${runId}`, controlPlaneUrl.value)
  }

  async function getHeatmap(filters: {
    strategyId: string
    symbol?: string
    xParam: string
    yParam: string
    metric?: string
  }): Promise<any> {
    const params = new URLSearchParams()
    params.append('strategyId', filters.strategyId)
    if (filters.symbol) params.append('symbol', filters.symbol)
    params.append('xParam', filters.xParam)
    params.append('yParam', filters.yParam)
    if (filters.metric) params.append('metric', filters.metric)
    return apiGet<any>(`/api/backtests/analytics/heatmap?${params.toString()}`, controlPlaneUrl.value)
  }

  async function getPareto(filters: {
    strategyId: string
    symbol?: string
    metricX?: string
    metricY?: string
  }): Promise<any> {
    const params = new URLSearchParams()
    params.append('strategyId', filters.strategyId)
    if (filters.symbol) params.append('symbol', filters.symbol)
    if (filters.metricX) params.append('metricX', filters.metricX)
    if (filters.metricY) params.append('metricY', filters.metricY)
    return apiGet<any>(`/api/backtests/analytics/pareto?${params.toString()}`, controlPlaneUrl.value)
  }

  async function deleteBacktest(runId: string): Promise<any> {
    return apiDelete<any>(`/api/backtests/${runId}`, controlPlaneUrl.value)
  }

  async function deleteAllBacktests(): Promise<any> {
    return apiDelete<any>('/api/backtests', controlPlaneUrl.value)
  }

  return {
    startRun,
    getRun,
    getStrategies,
    getTrades,
    getEquityCurve,
    getBars,
    listRuns,
    getControlSummary,
    killStrategy,
    getBrokerAccounts,
    getBrokerBalances,
    saveBrokerAccounts,
    testBrokerAccount,
    getMonteCarlo,
    promoteStrategy,
    getPromoteGates,
    updatePromoteGates,
    getHistoricalDataStatus,
    downloadHistoricalData,
    deleteHistoricalData,
    listBacktests,
    getBacktestDetails,
    getHeatmap,
    getPareto,
    deleteBacktest,
    deleteAllBacktests,
    getSentiment,
    error,
    loading,
    controlPlaneUrl,
  }
}
