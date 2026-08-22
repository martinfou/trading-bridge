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

const normalizedFolds = computed(() => {
  return (props.folds || []).map((f: any, idx: number) => {
    const isSharpe = f.inSampleSharpe ?? f.isSharpe ?? 0
    const oosSharpe = f.outOfSampleSharpe ?? f.oosSharpe ?? 0
    const wfe = f.wfe ?? (isSharpe > 0 ? oosSharpe / isSharpe : 0)
    return {
      index: f.foldIndex ?? f.index ?? idx,
      isStart: f.inSampleStart || f.isStart || '',
      isEnd: f.inSampleEnd || f.isEnd || '',
      oosStart: f.outOfSampleStart || f.oosStart || '',
      oosEnd: f.outOfSampleEnd || f.oosEnd || '',
      isSharpe,
      oosSharpe,
      wfe,
      params: f.selectedParameters || f.chosenParameters || {}
    }
  })
})

const totalFolds = computed(() => normalizedFolds.value.length)

function formatDate(isoStr: string): string {
  if (!isoStr) return ''
  return isoStr.split('T')[0]
}
</script>

<template>
  <div class="wfa-timeline-card">
    <div class="timeline-header">
      <div class="timeline-title">
        <span class="timeline-dot"></span>
        <h4>Walk-Forward Fold Timeline ({{ totalFolds }} Folds)</h4>
      </div>
      <div class="timeline-legend">
        <div class="legend-item">
          <span class="legend-box is"></span>
          <span>In-Sample Training (IS)</span>
        </div>
        <div class="legend-item">
          <span class="legend-box oos"></span>
          <span>Out-of-Sample Testing (OOS)</span>
        </div>
      </div>
    </div>

    <div class="folds-list">
      <div
        v-for="fold in normalizedFolds"
        :key="fold.index"
        class="fold-row"
      >
        <div class="fold-meta">
          <span class="fold-badge">Fold #{{ fold.index + 1 }}</span>
          <div class="fold-metrics">
            <span class="metric">IS Sharpe: <strong>{{ fold.isSharpe.toFixed(2) }}</strong></span>
            <span class="metric">OOS Sharpe: <strong class="text-oos">{{ fold.oosSharpe.toFixed(2) }}</strong></span>
            <span class="metric">WFE: <strong :class="fold.wfe >= 0.6 ? 'text-green' : 'text-amber'">{{ (fold.wfe * 100).toFixed(0) }}%</strong></span>
          </div>
        </div>

        <!-- Visual Bar -->
        <div class="split-bar-track">
          <div
            class="split-bar is-bar"
            style="width: 70%"
            :title="`IS: ${formatDate(fold.isStart)} to ${formatDate(fold.isEnd)}`"
          >
            <span>IS: {{ formatDate(fold.isStart) }}</span>
            <span>{{ formatDate(fold.isEnd) }}</span>
          </div>
          <div
            class="split-bar oos-bar"
            style="width: 30%"
            :title="`OOS: ${formatDate(fold.oosStart)} to ${formatDate(fold.oosEnd)}`"
          >
            <span>OOS</span>
            <span>{{ formatDate(fold.oosEnd) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wfa-timeline-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.timeline-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.timeline-title h4 {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.timeline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--info);
  box-shadow: 0 0 8px var(--info);
}

.timeline-legend {
  display: flex;
  gap: 1.25rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.legend-box {
  width: 12px;
  height: 12px;
  border-radius: 2px;
}

.legend-box.is {
  background: rgba(59, 130, 246, 0.8);
  border: 1px solid #3b82f6;
}

.legend-box.oos {
  background: rgba(16, 185, 129, 0.8);
  border: 1px solid #10b981;
}

.folds-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.fold-row {
  background: rgba(10, 10, 10, 0.6);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.75rem;
  transition: border-color 0.2s;
}

.fold-row:hover {
  border-color: rgba(255, 255, 255, 0.2);
}

.fold-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
  font-size: 0.8rem;
}

.fold-badge {
  font-weight: 600;
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.05);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  border: 1px solid var(--border);
}

.fold-metrics {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.metric strong {
  font-family: monospace;
  color: #93c5fd;
}

.metric strong.text-oos {
  color: #6ee7b7;
}

.metric strong.text-green {
  color: #34d399;
}

.metric strong.text-amber {
  color: #fbbf24;
}

.split-bar-track {
  width: 100%;
  display: flex;
  height: 24px;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.1);
  font-family: monospace;
  font-size: 0.7rem;
}

.split-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.5rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.is-bar {
  background: rgba(37, 99, 235, 0.75);
  border-right: 1px solid rgba(147, 197, 253, 0.5);
  color: #dbeafe;
}

.oos-bar {
  background: rgba(5, 150, 105, 0.75);
  color: #d1fae5;
}
</style>
