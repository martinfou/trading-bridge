import { ref, reactive } from 'vue'
import { useControlPlaneConfig } from './controlPlaneConfig'
import type { Strategy, RunConfig, RunResult, Trade, RunSummary, Bar } from '@/types/control-plane'

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
  const resp = await fetch(`${baseUrl}${path}`, {
    headers: { Accept: 'application/json' },
  })
  if (!resp.ok) {
    const body = await resp.text().catch(() => '')
    throw new Error(`GET ${path} ${resp.status}: ${body.slice(0, 200)}`)
  }
  return resp.json()
}

async function apiPost<T>(path: string, body: unknown, baseUrl: string): Promise<T> {
  const resp = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok) {
    const text = await resp.text().catch(() => '')
    throw new Error(`POST ${path} ${resp.status}: ${text.slice(0, 200)}`)
  }
  return resp.json()
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
    const barsSource =
      config.barsSource.type === 'ci'
        ? { type: 'ci' }
        : { type: 'year', year: config.barsSource.year?.toString() }

    return apiPost<StartRunResponse>(
      '/api/runs',
      {
        strategyId: config.strategyId,
        symbol: config.symbol,
        mode: config.mode,
        barsSource,
        capital: config.capital,
        commissionPerTrade: config.commissionPerTrade,
        slippagePct: config.slippagePct,
      },
      controlPlaneUrl.value,
    )
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

  async function listRuns(): Promise<RunSummary[]> {
    const data = await apiGet<{ runs: RunSummary[] }>('/api/runs', controlPlaneUrl.value)
    return data.runs
  }

  return {
    startRun,
    getRun,
    getStrategies,
    getTrades,
    getEquityCurve,
    getBars,
    listRuns,
    error,
    loading,
    controlPlaneUrl,
  }
}
