import { ref, reactive } from 'vue'
import { useControlPlaneConfig, getControlPlaneHeaders } from './controlPlaneConfig'
import { useStatusBar } from './useStatusBar'
import type { Strategy, RunConfig, RunResult, Trade, RunSummary, Bar, BrokerAccount, PromoteGateThresholds, WeeklyStat, ReconciliationAnomaly, InstrumentDefinition } from '@/types/control-plane'

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
      headers: getControlPlaneHeaders({ Accept: 'application/json' }),
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
      headers: getControlPlaneHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' }),
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

async function apiPut<T>(path: string, body: unknown, baseUrl: string): Promise<T> {
  try {
    const resp = await fetch(`${baseUrl}${path}`, {
      method: 'PUT',
      headers: getControlPlaneHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' }),
      body: JSON.stringify(body),
    })
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      const errMsg = `PUT ${path} ${resp.status}: ${text.slice(0, 200)}`
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
      headers: getControlPlaneHeaders({ Accept: 'application/json' }),
    })
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      const errMsg = `DELETE ${path} ${resp.status}: ${text.slice(0, 200)}`
      useStatusBar().setStatus(errMsg, 'error')
      throw new Error(errMsg)
    }
    if (resp.status === 204) {
      return undefined as T
    }
    const text = await resp.text()
    return (text ? JSON.parse(text) : undefined) as T
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

  async function getGeneratorDatabank(): Promise<any[]> {
    try {
      const data = await apiGet<{ candidates: any[] }>('/api/generator/databank', controlPlaneUrl.value)
      return data.candidates || []
    } catch {
      return []
    }
  }

  async function deleteStrategy(strategyId: string): Promise<void> {
    const encoded = encodeURIComponent(strategyId)
    await apiDelete<void>(`/api/generator/databank/${encoded}`, controlPlaneUrl.value)
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

  async function getBars(runId: string, limit?: number, to?: string): Promise<Bar[]> {
    const params = new URLSearchParams()
    if (limit) params.append('limit', limit.toString())
    if (to) params.append('to', to)
    const query = params.toString() ? `?${params.toString()}` : ''
    const data = await apiGet<{ bars: Bar[] }>(`/api/runs/${runId}/bars${query}`, controlPlaneUrl.value)
    return data.bars
  }

  const getSentiment = async (instrument: string): Promise<any> => {
    return apiGet<any>(`/api/sentiment/${instrument}`, controlPlaneUrl.value)
  }

  async function listRuns(): Promise<RunSummary[]> {
    const data = await apiGet<{ runs: RunSummary[] }>('/api/runs', controlPlaneUrl.value)
    return data.runs
  }

  async function getControlSummary(refresh = false): Promise<any> {
    const query = refresh ? '?refresh=true' : ''
    return apiGet<any>(`/api/control/summary${query}`, controlPlaneUrl.value)
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

  async function getBrokerBalances(refresh = false): Promise<any[]> {
    const query = refresh ? '?refresh=true' : ''
    const data = await apiGet<{ balances: any[] }>(`/api/broker-accounts/balances${query}`, controlPlaneUrl.value)
    return data.balances
  }

  async function discoverOandaAccounts(params: { id?: string; token?: string; restUrl?: string }): Promise<{ id: string; tags?: string[] }[]> {
    const data = await apiPost<{ accounts: { id: string; tags?: string[] }[] }>(
      '/api/broker-accounts/oanda-accounts',
      params,
      controlPlaneUrl.value
    )
    return data.accounts || []
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
        headers: getControlPlaneHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' }),
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

  async function getRemoteCoreConfig(): Promise<{
    enabled: boolean
    url: string
    apiKeyConfigured: boolean
    apiKeyMasked: string
    serverApiKeyConfigured: boolean
    serverApiKeyMasked: string
  }> {
    return apiGet('/api/remote-core', controlPlaneUrl.value)
  }

  async function saveRemoteCoreConfig(payload: {
    enabled?: boolean
    url?: string
    apiKey?: string
    clearApiKey?: boolean
    serverApiKey?: string
    clearServerApiKey?: boolean
  }): Promise<any> {
    return apiPut('/api/remote-core', payload, controlPlaneUrl.value)
  }

  async function testRemoteCoreConnection(payload: {
    url?: string
    apiKey?: string
  }): Promise<{
    success: boolean
    url?: string
    ibkrTradesCount?: number
    laneRunsCount?: number
    message?: string
  }> {
    return apiPost('/api/remote-core/test', payload, controlPlaneUrl.value)
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

  async function getWeeklyStats(runId: string): Promise<WeeklyStat[]> {
    const data = await apiGet<{ weeklyStats: WeeklyStat[] }>(`/api/runs/${runId}/weekly-stats`, controlPlaneUrl.value)
    return data.weeklyStats
  }

  async function getAlignment(runId: string): Promise<{ anomalies: ReconciliationAnomaly[]; backtestOrders: any[]; liveOrders: any[] }> {
    return apiGet<{ anomalies: ReconciliationAnomaly[]; backtestOrders: any[]; liveOrders: any[] }>(`/api/runs/${runId}/alignment`, controlPlaneUrl.value)
  }

  async function getInstruments(assetClass?: string): Promise<InstrumentDefinition[]> {
    const query = assetClass ? `?assetClass=${encodeURIComponent(assetClass)}` : ''
    return apiGet<InstrumentDefinition[]>(`/api/instruments${query}`, controlPlaneUrl.value)
  }

  async function createInstrument(def: Partial<InstrumentDefinition>): Promise<InstrumentDefinition> {
    return apiPost<InstrumentDefinition>('/api/instruments', def, controlPlaneUrl.value)
  }

  async function deleteInstrument(symbol: string): Promise<void> {
    return apiDelete<void>(`/api/instruments/${encodeURIComponent(symbol)}`, controlPlaneUrl.value)
  }

  return {
    startRun,
    getRun,
    getWeeklyStats,
    getAlignment,
    getStrategies,
    getGeneratorDatabank,
    deleteStrategy,
    getTrades,
    getEquityCurve,
    getBars,
    listRuns,
    getControlSummary,
    killStrategy,
    getBrokerAccounts,
    getBrokerBalances,
    discoverOandaAccounts,
    saveBrokerAccounts,
    testBrokerAccount,
    getRemoteCoreConfig,
    saveRemoteCoreConfig,
    testRemoteCoreConnection,
    getMonteCarlo,
    promoteStrategy,
    getPromoteGates,
    updatePromoteGates,
    getHistoricalDataStatus,
    downloadHistoricalData,
    deleteHistoricalData,
    getInstruments,
    createInstrument,
    deleteInstrument,
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
