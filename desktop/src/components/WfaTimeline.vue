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

const totalFolds = computed(() => props.folds.length)

function formatDate(isoStr: string): string {
  if (!isoStr) return ''
  return isoStr.split('T')[0]
}
</script>

<template>
  <div class="wfa-timeline bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-base font-semibold text-slate-100 flex items-center gap-2">
        <span class="w-2.5 h-2.5 rounded-full bg-cyan-400"></span>
        Walk-Forward Fold Timeline ({{ totalFolds }} Folds)
      </h3>
      <div class="flex items-center gap-4 text-xs">
        <div class="flex items-center gap-1.5">
          <span class="w-3 h-3 rounded bg-blue-600/80 border border-blue-500"></span>
          <span class="text-slate-300">In-Sample (Training)</span>
        </div>
        <div class="flex items-center gap-1.5">
          <span class="w-3 h-3 rounded bg-emerald-600/80 border border-emerald-500"></span>
          <span class="text-slate-300">Out-of-Sample (Testing)</span>
        </div>
      </div>
    </div>

    <div class="space-y-3.5 mt-4">
      <div
        v-for="fold in folds"
        :key="fold.foldIndex"
        class="fold-row bg-slate-950/60 border border-slate-800/80 rounded-lg p-3 hover:border-slate-700 transition"
      >
        <div class="flex items-center justify-between text-xs mb-2">
          <span class="font-bold text-slate-200">Fold #{{ fold.foldIndex + 1 }}</span>
          <div class="flex items-center gap-4 text-xs">
            <span class="text-slate-400">IS Sharpe: <strong class="text-blue-400 font-mono">{{ fold.inSampleSharpe.toFixed(2) }}</strong></span>
            <span class="text-slate-400">OOS Sharpe: <strong class="text-emerald-400 font-mono">{{ fold.outOfSampleSharpe.toFixed(2) }}</strong></span>
            <span class="text-slate-400">WFE: <strong :class="fold.wfe >= 0.6 ? 'text-emerald-400' : 'text-amber-400'" class="font-mono">{{ (fold.wfe * 100).toFixed(0) }}%</strong></span>
          </div>
        </div>

        <!-- Visual Split Bar -->
        <div class="w-full flex h-6 rounded-md overflow-hidden bg-slate-800/50 border border-slate-700/50 text-[10px] font-mono">
          <div
            class="bg-blue-600/70 border-r border-blue-400/50 flex items-center px-2 text-blue-100 truncate justify-between"
            style="width: 70%"
            :title="`IS: ${formatDate(fold.inSampleStart)} to ${formatDate(fold.inSampleEnd)}`"
          >
            <span>IS: {{ formatDate(fold.inSampleStart) }}</span>
            <span>{{ formatDate(fold.inSampleEnd) }}</span>
          </div>
          <div
            class="bg-emerald-600/70 flex items-center px-2 text-emerald-100 truncate justify-between"
            style="width: 30%"
            :title="`OOS: ${formatDate(fold.outOfSampleStart)} to ${formatDate(fold.outOfSampleEnd)}`"
          >
            <span>OOS</span>
            <span>{{ formatDate(fold.outOfSampleEnd) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wfa-timeline {
  font-family: inherit;
}
</style>
