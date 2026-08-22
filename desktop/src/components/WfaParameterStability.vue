<script setup lang="ts">
import { computed } from 'vue'

export interface WfaFoldResult {
  foldIndex: number
  inSampleStart: string
  inSampleEnd: string
  outOfSampleStart: string
  outOfSampleEnd: string
  inSampleSharpe: number
  outOfSampleSharpe: number
  inSampleReturnPct: number
  outOfSampleReturnPct: number
  wfe: number
  selectedParameters: Record<string, number>
}

const props = defineProps<{
  folds: WfaFoldResult[]
}>()

const paramNames = computed(() => {
  if (!props.folds || props.folds.length === 0) return []
  for (const f of props.folds as any[]) {
    const params = f.selectedParameters || f.chosenParameters
    if (params && Object.keys(params).length > 0) {
      return Object.keys(params)
    }
  }
  return []
})

function getParamValues(name: string): number[] {
  return (props.folds as any[]).map(f => {
    const params = f.selectedParameters || f.chosenParameters || {}
    return params[name] ?? 0
  })
}

function getParamStats(name: string) {
  const vals = getParamValues(name)
  if (vals.length === 0) return { min: 0, max: 0, mean: 0, variance: 0, stable: true }
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const mean = vals.reduce((a, b) => a + b, 0) / vals.length
  const variance = vals.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / vals.length
  const stdDev = Math.sqrt(variance)
  const cv = mean !== 0 ? (stdDev / Math.abs(mean)) : 0
  return {
    min,
    max,
    mean: Number(mean.toFixed(2)),
    stdDev: Number(stdDev.toFixed(2)),
    stable: cv < 0.35
  }
}
</script>

<template>
  <div class="stability-card">
    <div class="stability-header">
      <div class="stability-title">
        <span class="stability-dot"></span>
        <h4>Parameter Stability & Drift Matrix</h4>
      </div>
      <span class="stability-hint">Robustness Gate: CV &lt; 35%</span>
    </div>

    <div v-if="paramNames.length === 0" class="empty-state">
      No optimized parameters recorded in this run.
    </div>

    <div v-else class="table-wrapper">
      <table class="stability-table">
        <thead>
          <tr>
            <th class="text-left">Parameter</th>
            <th v-for="f in folds" :key="f.foldIndex" class="text-center">
              Fold #{{ f.foldIndex + 1 }}
            </th>
            <th class="text-right">Mean ± StdDev</th>
            <th class="text-center">Status</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="pName in paramNames" :key="pName">
            <td class="param-name">{{ pName }}</td>
            <td
              v-for="f in folds"
              :key="f.foldIndex"
              class="fold-val text-center"
            >
              {{ f.selectedParameters[pName] }}
            </td>
            <td class="stats-col text-right">
              {{ getParamStats(pName).mean }} <span class="std-dev">± {{ getParamStats(pName).stdDev }}</span>
            </td>
            <td class="status-col text-center">
              <span
                v-if="getParamStats(pName).stable"
                class="status-pill stable"
              >
                Stable
              </span>
              <span
                v-else
                class="status-pill warning"
              >
                Drift High
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.stability-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
}

.stability-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.stability-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.stability-title h4 {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.stability-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--asset-futures);
  box-shadow: 0 0 8px var(--asset-futures);
}

.stability-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.empty-state {
  font-size: 0.8rem;
  color: var(--text-secondary);
  text-align: center;
  padding: 1rem;
}

.table-wrapper {
  overflow-x: auto;
}

.stability-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.75rem;
}

.stability-table th {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  font-weight: 500;
}

.stability-table td {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  font-family: monospace;
}

.stability-table tr:hover {
  background: rgba(255, 255, 255, 0.02);
}

.param-name {
  font-family: inherit !important;
  font-weight: 500;
  color: var(--text-primary);
}

.fold-val {
  color: var(--text-primary);
}

.stats-col {
  color: var(--text-primary);
}

.std-dev {
  color: var(--text-muted);
}

.status-pill {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 9999px;
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
}

.status-pill.stable {
  background: rgba(16, 185, 129, 0.15);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.4);
}

.status-pill.warning {
  background: rgba(245, 158, 11, 0.15);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.4);
}

.text-left { text-align: left; }
.text-center { text-align: center; }
.text-right { text-align: right; }
</style>
