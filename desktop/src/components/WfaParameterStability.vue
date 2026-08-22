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
  const first = props.folds[0].selectedParameters
  return Object.keys(first || {})
})

function getParamValues(name: string): number[] {
  return props.folds.map(f => f.selectedParameters[name] ?? 0)
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
  <div class="wfa-param-stability bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-base font-semibold text-slate-100 flex items-center gap-2">
        <span class="w-2.5 h-2.5 rounded-full bg-purple-400"></span>
        Parameter Stability & Drift Matrix
      </h3>
      <span class="text-xs text-slate-400">Robustness Gate: CV &lt; 35%</span>
    </div>

    <div v-if="paramNames.length === 0" class="text-xs text-slate-500 py-4 text-center">
      No optimized parameters recorded in this run.
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-xs text-left border-collapse">
        <thead>
          <tr class="border-b border-slate-800 text-slate-400">
            <th class="py-2.5 px-3">Parameter</th>
            <th v-for="f in folds" :key="f.foldIndex" class="py-2.5 px-2 text-center">
              Fold #{{ f.foldIndex + 1 }}
            </th>
            <th class="py-2.5 px-3 text-right">Mean ± StdDev</th>
            <th class="py-2.5 px-3 text-center">Status</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-800/60 font-mono">
          <tr v-for="pName in paramNames" :key="pName" class="hover:bg-slate-800/30">
            <td class="py-2.5 px-3 font-medium text-slate-300">{{ pName }}</td>
            <td
              v-for="f in folds"
              :key="f.foldIndex"
              class="py-2.5 px-2 text-center text-slate-200"
            >
              {{ f.selectedParameters[pName] }}
            </td>
            <td class="py-2.5 px-3 text-right text-slate-300">
              {{ getParamStats(pName).mean }} <span class="text-slate-500">± {{ getParamStats(pName).stdDev }}</span>
            </td>
            <td class="py-2.5 px-3 text-center">
              <span
                v-if="getParamStats(pName).stable"
                class="px-2 py-0.5 rounded-full text-[10px] bg-emerald-950 text-emerald-400 border border-emerald-800"
              >
                Stable
              </span>
              <span
                v-else
                class="px-2 py-0.5 rounded-full text-[10px] bg-amber-950 text-amber-400 border border-amber-800"
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
