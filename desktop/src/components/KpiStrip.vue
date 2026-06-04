<script setup lang="ts">
defineProps<{
  sharpe: number | null
  profitFactor: number | null
  maxDd: number | null
  totalTrades: number | null
  winRate: number | null
  totalReturn: number | null
  finalEquity: number | null
}>()
</script>

<template>
  <div class="kpi-strip">
    <div class="kpi" :class="sharpe !== null && sharpe >= 1 ? 'good' : sharpe !== null && sharpe < 0 ? 'bad' : ''">
      <span class="kpi-label">Sharpe</span>
      <span class="kpi-value">{{ sharpe !== null ? sharpe.toFixed(2) : '—' }}</span>
    </div>
    <div class="kpi" :class="profitFactor !== null && profitFactor >= 1.5 ? 'good' : profitFactor !== null && profitFactor < 1 ? 'bad' : ''">
      <span class="kpi-label">Profit Factor</span>
      <span class="kpi-value">{{ profitFactor !== null ? profitFactor.toFixed(2) : '—' }}</span>
    </div>
    <div class="kpi" :class="maxDd !== null && maxDd <= 15 ? 'good' : maxDd !== null && maxDd > 25 ? 'bad' : ''">
      <span class="kpi-label">Max DD %</span>
      <span class="kpi-value">{{ maxDd !== null ? maxDd.toFixed(2) : '—' }}</span>
    </div>
    <div class="kpi">
      <span class="kpi-label">Trades</span>
      <span class="kpi-value">{{ totalTrades !== null ? totalTrades : '—' }}</span>
    </div>
    <div class="kpi" :class="winRate !== null && winRate >= 50 ? 'good' : winRate !== null && winRate < 30 ? 'bad' : ''">
      <span class="kpi-label">Win Rate %</span>
      <span class="kpi-value">{{ winRate !== null ? winRate.toFixed(1) : '—' }}</span>
    </div>
    <div class="kpi" :class="totalReturn !== null && totalReturn > 0 ? 'good' : totalReturn !== null && totalReturn < 0 ? 'bad' : ''">
      <span class="kpi-label">Return %</span>
      <span class="kpi-value">{{ totalReturn !== null ? totalReturn.toFixed(2) : '—' }}</span>
    </div>
    <div class="kpi">
      <span class="kpi-label">Final Equity</span>
      <span class="kpi-value">{{ finalEquity !== null ? '$' + finalEquity.toLocaleString() : '—' }}</span>
    </div>
  </div>
</template>

<style scoped>
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.kpi {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.75rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.kpi-label {
  font-size: 0.65rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.kpi-value {
  font-size: 1.15rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}

.kpi.good { border-left: 3px solid var(--success); }
.kpi.bad { border-left: 3px solid var(--danger); }
</style>
