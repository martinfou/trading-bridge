<script setup lang="ts">
import { computed } from 'vue'
import type { Strategy } from '@/types/control-plane'

const props = defineProps<{
  strategy: Strategy
  selected?: boolean
  confirmingDelete?: boolean
}>()

const emit = defineEmits<{
  inspect: [strategy: Strategy]
  backtest: [strategy: Strategy]
  wfa: [strategy: Strategy]
  promote: [strategy: Strategy]
  delete: [strategy: Strategy]
}>()

const formattedSymbol = computed(() => {
  return (props.strategy.defaultSymbol || 'EUR_USD').replace('_', '/')
})

const symbolTheme = computed(() => {
  const sym = (props.strategy.defaultSymbol || '').toUpperCase()
  if (sym.startsWith('MGC') || sym.includes('XAU')) return 'sym-gold'
  if (sym.startsWith('MNQ') || sym.startsWith('NQ')) return 'sym-cyan'
  if (sym.startsWith('MES') || sym.startsWith('ES')) return 'sym-purple'
  if (sym.startsWith('EMD') || sym.startsWith('M2K')) return 'sym-emerald'
  return 'sym-fx'
})

const tierTheme = computed(() => {
  const t = (props.strategy.tier || '').toUpperCase()
  if (t === 'ELITE') return 'tier-elite'
  if (t === 'INSTITUTIONAL') return 'tier-inst'
  if (t === 'CORE') return 'tier-core'
  if (t === 'OVERLAY') return 'tier-overlay'
  if (t === 'SYSTEM') return 'tier-system'
  return 'tier-qualified'
})

const cleanArchetype = computed(() => {
  const raw = props.strategy.tradingStyle || props.strategy.type || 'TREND'
  return raw.replace(/_/g, ' ')
})

const sparklineData = computed(() => {
  const rawCurve = props.strategy.equityCurve
  if (!rawCurve || rawCurve.length < 2) return null
  const curve = rawCurve.filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (curve.length < 2) return null
  const w = 112
  const h = 34
  const pad = 3
  let min = curve[0]
  let max = curve[0]
  for (let i = 1; i < curve.length; i++) {
    if (curve[i] < min) min = curve[i]
    if (curve[i] > max) max = curve[i]
  }
  const range = max - min || 1
  const pts = curve.map((v, idx) => {
    const x = pad + (idx / (curve.length - 1)) * (w - pad * 2)
    const y = h - pad - ((v - min) / range) * (h - pad * 2)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const firstX = pad
  const lastX = w - pad
  const area = `M ${firstX},${h} L ${pts.join(' L ')} L ${lastX},${h} Z`
  const oosIdx = props.strategy.oosStartIndex
  const oosX =
    oosIdx != null && oosIdx > 0 && oosIdx < curve.length
      ? pad + (oosIdx / (curve.length - 1)) * (w - pad * 2)
      : null
  const isPositive = curve[curve.length - 1] >= curve[0]
  return {
    points: pts.join(' '),
    area,
    oosX,
    isPositive
  }
})

function formatPf(val?: number): string {
  if (val == null || Number.isNaN(val)) return '—'
  return val.toFixed(2)
}

function formatMoney(val?: number): string {
  if (val == null || Number.isNaN(val)) return '—'
  const sign = val >= 0 ? '+' : '-'
  const abs = Math.abs(val)
  if (abs >= 1000) {
    return `${sign}$${abs.toLocaleString('en-US', { maximumFractionDigits: 0 })}`
  }
  return `${sign}$${abs.toFixed(0)}`
}

function pfClass(val?: number): string {
  if (val == null) return ''
  if (val >= 2.0) return 'val-elite'
  if (val >= 1.4) return 'val-good'
  if (val >= 1.1) return 'val-ok'
  return 'val-warn'
}

function ddClass(val?: number): string {
  if (val == null) return ''
  if (val <= 10.0) return 'val-elite'
  if (val <= 15.0) return 'val-good'
  if (val <= 25.0) return 'val-ok'
  return 'val-warn'
}
</script>

<template>
  <div
    :class="['strategy-card', tierTheme, { 'is-selected': selected }]"
    @click="emit('inspect', strategy)"
  >
    <!-- Top Identity & Status Bar -->
    <div class="card-top-bar">
      <div class="instrument-cluster">
        <span :class="['inst-pill', symbolTheme]">{{ formattedSymbol }}</span>
        <span class="tf-pill">{{ strategy.primaryTimeframe || strategy.timeframe || 'H1' }}</span>
        <span class="arch-pill">{{ cleanArchetype }}</span>
      </div>

      <div class="status-cluster">
        <span v-if="strategy.deployedMode" class="status-pill status-live">
          <span class="pulse-dot"></span>
          {{ strategy.deployedMode }}
        </span>
        <span v-else-if="strategy.promoted" class="status-pill status-promoted">
          PROMOTED
        </span>
        <span v-if="strategy.tier" :class="['tier-pill', tierTheme]">
          {{ strategy.tier }}
          <strong v-if="strategy.compositeScore != null">{{ strategy.compositeScore.toFixed(1) }}</strong>
        </span>
      </div>
    </div>

    <!-- Title + Equity Sparkline Row -->
    <div class="card-hero-row">
      <div class="title-block">
        <div class="strategy-title">
          {{ strategy.displayName || strategy.id }}
        </div>
        <div class="strategy-code-row">
          <code class="strategy-id-code">{{ strategy.id }}</code>
        </div>
      </div>

      <div v-if="sparklineData" class="sparkline-box" title="Equity Curve (Vertical dashed line = OOS split)">
        <svg viewBox="0 0 112 34" class="sparkline-svg" preserveAspectRatio="none">
          <path
            :d="sparklineData.area"
            :fill="sparklineData.isPositive ? 'rgba(34, 197, 94, 0.12)' : 'rgba(239, 68, 68, 0.12)'"
          />
          <line
            v-if="sparklineData.oosX"
            :x1="sparklineData.oosX"
            y1="2"
            :x2="sparklineData.oosX"
            y2="32"
            stroke="rgba(251, 191, 36, 0.45)"
            stroke-width="1"
            stroke-dasharray="2,2"
          />
          <polyline
            fill="none"
            :stroke="sparklineData.isPositive ? '#22c55e' : '#ef4444'"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            :points="sparklineData.points"
          />
        </svg>
      </div>
    </div>

    <!-- 4-Cell Quantitative Metrics Strip -->
    <div class="quant-strip">
      <div class="quant-cell">
        <span class="quant-label">PROFIT FACTOR</span>
        <div class="quant-val-row">
          <span :class="['quant-val', pfClass(strategy.profitFactor)]">
            {{ formatPf(strategy.profitFactor) }}
          </span>
          <span v-if="strategy.oosProfitFactor != null" class="quant-sub">
            OOS {{ formatPf(strategy.oosProfitFactor) }}
          </span>
        </div>
      </div>

      <div class="quant-cell">
        <span class="quant-label">SHARPE</span>
        <div class="quant-val-row">
          <span :class="['quant-val', (strategy.sharpeRatio ?? 0) >= 1.2 ? 'val-elite' : 'val-ok']">
            {{ strategy.sharpeRatio != null ? strategy.sharpeRatio.toFixed(2) : '—' }}
          </span>
          <span v-if="strategy.rSquared != null" class="quant-sub">
            R² {{ strategy.rSquared.toFixed(2) }}
          </span>
        </div>
      </div>

      <div class="quant-cell">
        <span class="quant-label">MAX DD</span>
        <div class="quant-val-row">
          <span :class="['quant-val', ddClass(strategy.maxDrawdownPct)]">
            {{ strategy.maxDrawdownPct != null ? `${strategy.maxDrawdownPct.toFixed(1)}%` : '—' }}
          </span>
          <span v-if="strategy.winRatePct != null" class="quant-sub">
            WR {{ strategy.winRatePct.toFixed(0) }}%
          </span>
        </div>
      </div>

      <div class="quant-cell">
        <span class="quant-label">NET PNL</span>
        <div class="quant-val-row">
          <span :class="['quant-val', (strategy.netProfit ?? 0) >= 0 ? 'val-good' : 'val-warn']">
            {{ formatMoney(strategy.netProfit) }}
          </span>
          <span v-if="strategy.totalTrades != null" class="quant-sub">
            {{ strategy.totalTrades }} trd
          </span>
        </div>
      </div>
    </div>

    <!-- Signal Summary & Indicators -->
    <div class="rule-preview">
      <div v-if="strategy.longSignalSummary" class="signal-line" :title="strategy.longSignalSummary">
        <span class="sig-tag">RULE</span>
        <span class="sig-text">{{ strategy.longSignalSummary }}</span>
      </div>
      <div v-else-if="strategy.description" class="signal-line" :title="strategy.description">
        <span class="sig-text">{{ strategy.description }}</span>
      </div>

      <div v-if="strategy.indicators && strategy.indicators.length" class="indicator-row">
        <span
          v-for="ind in strategy.indicators.slice(0, 4)"
          :key="ind"
          class="ind-chip"
        >
          {{ ind }}
        </span>
      </div>
    </div>

    <!-- 1-Click Quick Action Footer -->
    <div class="card-footer" @click.stop>
      <button class="act-btn act-inspect" title="Open Full Strategy Inspector" @click="emit('inspect', strategy)">
        🔍 Inspect
      </button>
      <button class="act-btn act-run" title="Run Backtest in Dashboard" @click="emit('backtest', strategy)">
        ▶ Backtest
      </button>
      <button class="act-btn act-wfa" title="Run Walk-Forward Analysis" @click="emit('wfa', strategy)">
        📈 WFA
      </button>
      <button class="act-btn act-promote" title="Promote to Paper / Live Desk" @click="emit('promote', strategy)">
        🚀 Promote
      </button>
      <button
        :class="['act-btn', 'act-delete', { confirming: confirmingDelete }]"
        :title="confirmingDelete ? 'Click again to confirm delete' : 'Delete / Prune Strategy'"
        @click="emit('delete', strategy)"
      >
        {{ confirmingDelete ? '✓?' : '🗑️' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.strategy-card {
  background: linear-gradient(180deg, rgba(24, 26, 32, 0.96) 0%, rgba(17, 19, 24, 0.98) 100%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-top: 2px solid rgba(255, 255, 255, 0.14);
  border-radius: 10px;
  padding: 0.85rem 0.95rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
  cursor: pointer;
  transition: transform 0.14s ease, border-color 0.14s ease, box-shadow 0.14s ease;
  position: relative;
}

.strategy-card:hover {
  border-color: rgba(245, 158, 11, 0.45);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  transform: translateY(-1px);
}

.strategy-card.is-selected {
  border-color: var(--accent, #f59e0b);
  box-shadow: 0 0 0 1px var(--accent, #f59e0b), 0 8px 24px rgba(245, 158, 11, 0.15);
}

.strategy-card.tier-elite {
  border-top-color: #f59e0b;
}
.strategy-card.tier-inst {
  border-top-color: #38bdf8;
}
.strategy-card.tier-core {
  border-top-color: #22c55e;
}
.strategy-card.tier-overlay {
  border-top-color: #a855f7;
}
.strategy-card.tier-qualified {
  border-top-color: #64748b;
}

.card-top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.4rem;
}

.instrument-cluster,
.status-cluster {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  flex-wrap: nowrap;
}

.inst-pill {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.72rem;
  font-weight: 700;
  padding: 0.15rem 0.48rem;
  border-radius: 4px;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.sym-gold {
  background: rgba(245, 158, 11, 0.16);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.38);
}

.sym-cyan {
  background: rgba(6, 182, 212, 0.16);
  color: #22d3ee;
  border: 1px solid rgba(6, 182, 212, 0.38);
}

.sym-purple {
  background: rgba(168, 85, 247, 0.16);
  color: #c084fc;
  border: 1px solid rgba(168, 85, 247, 0.38);
}

.sym-emerald {
  background: rgba(16, 185, 129, 0.16);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.38);
}

.sym-fx {
  background: rgba(59, 130, 246, 0.16);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.38);
}

.tf-pill {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.68rem;
  font-weight: 600;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 0.12rem 0.4rem;
  border-radius: 4px;
  white-space: nowrap;
}

.arch-pill {
  font-size: 0.65rem;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.28rem;
  font-size: 0.62rem;
  font-weight: 700;
  padding: 0.12rem 0.42rem;
  border-radius: 4px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.status-live {
  background: rgba(34, 197, 94, 0.16);
  color: #4ade80;
  border: 1px solid rgba(34, 197, 94, 0.35);
}

.status-promoted {
  background: rgba(56, 189, 248, 0.14);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.3);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 6px #22c55e;
}

.tier-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.62rem;
  font-weight: 700;
  padding: 0.12rem 0.45rem;
  border-radius: 4px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.tier-pill.tier-elite {
  background: rgba(245, 158, 11, 0.16);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.35);
}

.tier-pill.tier-inst {
  background: rgba(56, 189, 248, 0.14);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.32);
}

.tier-pill.tier-core {
  background: rgba(34, 197, 94, 0.14);
  color: #4ade80;
  border: 1px solid rgba(34, 197, 94, 0.32);
}

.tier-pill.tier-overlay {
  background: rgba(168, 85, 247, 0.14);
  color: #c084fc;
  border: 1px solid rgba(168, 85, 247, 0.32);
}

.tier-pill.tier-qualified,
.tier-pill.tier-system {
  background: rgba(148, 163, 184, 0.12);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.25);
}

.card-hero-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.65rem;
}

.title-block {
  min-width: 0;
  flex: 1;
}

.strategy-title {
  font-size: 0.98rem;
  font-weight: 700;
  color: #f8fafc;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.25;
}

.strategy-code-row {
  margin-top: 0.12rem;
}

.strategy-id-code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.7rem;
  color: #64748b;
}

.sparkline-box {
  width: 112px;
  height: 34px;
  flex-shrink: 0;
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  overflow: hidden;
}

.sparkline-svg {
  width: 100%;
  height: 100%;
  display: block;
}

.quant-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.35rem;
  background: rgba(0, 0, 0, 0.28);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 7px;
  padding: 0.45rem 0.55rem;
}

.quant-cell {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.quant-label {
  font-size: 0.58rem;
  font-weight: 700;
  color: #64748b;
  letter-spacing: 0.05em;
}

.quant-val-row {
  display: flex;
  align-items: baseline;
  gap: 0.28rem;
  flex-wrap: wrap;
}

.quant-val {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.84rem;
  font-weight: 700;
  color: #e2e8f0;
}

.quant-sub {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.62rem;
  color: #94a3b8;
}

.val-elite {
  color: #4ade80;
}

.val-good {
  color: #38bdf8;
}

.val-ok {
  color: #e2e8f0;
}

.val-warn {
  color: #fbbf24;
}

.rule-preview {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.signal-line {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.72rem;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sig-tag {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.58rem;
  font-weight: 700;
  color: #f59e0b;
  background: rgba(245, 158, 11, 0.12);
  padding: 0.08rem 0.3rem;
  border-radius: 3px;
  flex-shrink: 0;
}

.sig-text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.indicator-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.28rem;
}

.ind-chip {
  font-size: 0.64rem;
  font-weight: 500;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  padding: 0.08rem 0.38rem;
  border-radius: 4px;
}

.card-footer {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding-top: 0.45rem;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  margin-top: auto;
}

.act-btn {
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: #cbd5e1;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.3rem 0.55rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.14s ease;
  white-space: nowrap;
}

.act-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.act-run {
  background: rgba(245, 158, 11, 0.15);
  border-color: rgba(245, 158, 11, 0.38);
  color: #fbbf24;
}

.act-run:hover {
  background: #f59e0b;
  color: #000;
}

.act-wfa {
  background: rgba(168, 85, 247, 0.12);
  border-color: rgba(168, 85, 247, 0.32);
  color: #c084fc;
}

.act-wfa:hover {
  background: rgba(168, 85, 247, 0.28);
  color: #fff;
}

.act-promote {
  background: rgba(34, 197, 94, 0.12);
  border-color: rgba(34, 197, 94, 0.32);
  color: #4ade80;
}

.act-promote:hover {
  background: rgba(34, 197, 94, 0.28);
  color: #fff;
}

.act-delete {
  margin-left: auto;
  padding: 0.3rem 0.45rem;
  color: #94a3b8;
}

.act-delete:hover,
.act-delete.confirming {
  background: rgba(239, 68, 68, 0.2);
  border-color: rgba(239, 68, 68, 0.5);
  color: #fca5a5;
}
</style>
