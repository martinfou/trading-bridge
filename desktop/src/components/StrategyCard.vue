<script setup lang="ts">
import { ref } from 'vue'
import type { Strategy } from '@/types/control-plane'
import { useRouter } from 'vue-router'
import PromoteModal from './PromoteModal.vue'

const props = defineProps<{
  strategy: Strategy
  expanded?: boolean
}>()

const emit = defineEmits<{
  toggleExpand: [id: string]
}>()

const router = useRouter()
const showPromoteModal = ref(false)

const familyColors: Record<string, string> = {
  PROP: '#6366f1',
  SQ_IMPORTED: '#f59e0b',
  GENERATED: '#22c55e',
  EXAMPLE: '#a855f7',
}

function familyColor(family: string): string {
  return familyColors[family] || '#888'
}

function runBacktest() {
  const symbol = props.strategy.defaultSymbol?.replace(/_/g, '/') || 'EUR/USD'
  router.push(`/dashboard?strategyId=${props.strategy.id}&symbol=${symbol}`)
}

function onPromoted() {
  // Stay on the current page instead of routing to the trading desk
}
</script>

<template>
  <div
    :class="['strategy-card', { expanded }]"
    :style="{ borderLeftColor: familyColor(strategy.family) }"
  >
    <div class="card-header" @click="emit('toggleExpand', strategy.id)">
      <div class="card-info">
        <div class="card-title-row">
          <span class="family-badge" :style="{ background: familyColor(strategy.family) }">
            {{ strategy.family }}
          </span>
          <span class="strategy-name">{{ strategy.id }}</span>
        </div>
        <div class="card-meta">
          <span class="meta-item">📊 {{ strategy.defaultSymbol || '—' }}</span>
          <span v-if="strategy.type" class="meta-item type-badge">🏷️ {{ strategy.type }}</span>
          <span v-if="strategy.deployedMode" class="meta-item deploy-badge">
            🟢 {{ strategy.deployedMode }}
          </span>
        </div>
      </div>
      <span class="expand-icon">{{ expanded ? '▾' : '▸' }}</span>
    </div>

    <div v-if="expanded" class="card-detail">
      <!-- Strategy Description -->
      <div v-if="strategy.description" class="description-block">
        <p class="description-text">{{ strategy.description }}</p>
      </div>

      <!-- Indicators Used -->
      <div v-if="strategy.indicators && strategy.indicators.length" class="indicators-block">
        <span class="detail-label">Indicators Used</span>
        <div class="indicator-chips">
          <span v-for="ind in strategy.indicators" :key="ind" class="indicator-chip">
            {{ ind }}
          </span>
        </div>
      </div>

      <div class="detail-grid">
        <div class="detail-item">
          <span class="detail-label">Strategy ID</span>
          <code class="detail-value">{{ strategy.id }}</code>
        </div>
        <div class="detail-item">
          <span class="detail-label">Family</span>
          <span class="detail-value">{{ strategy.family }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Default Symbol</span>
          <span class="detail-value">{{ strategy.defaultSymbol || '—' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Execution Label</span>
          <span class="detail-value">{{ strategy.executionLabel || '—' }}</span>
        </div>
      </div>

      <div v-if="strategy.deployedMode" class="deploy-info">
        <h4>Deployment</h4>
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">Mode</span>
            <span class="detail-value">{{ strategy.deployedMode }}</span>
          </div>
          <div v-if="strategy.brokerAccountId" class="detail-item">
            <span class="detail-label">Broker Account</span>
            <span class="detail-value">{{ strategy.brokerAccountId }}</span>
          </div>
        </div>
      </div>

      <div class="actions-row">
        <button class="run-btn" @click="runBacktest">
          ▶ Run Backtest
        </button>
        <button class="promote-btn" @click="showPromoteModal = true">
          🚀 Promote Strategy
        </button>
      </div>

      <PromoteModal
        :strategyId="strategy.id"
        :show="showPromoteModal"
        @close="showPromoteModal = false"
        @promoted="onPromoted"
      />
    </div>
  </div>
</template>

<style scoped>
.strategy-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-left: 3px solid #888;
  border-radius: 8px;
  overflow: hidden;
  transition: all 0.15s;
}

.strategy-card:hover {
  border-color: #444;
}

.strategy-card.expanded {
  border-left-width: 3px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 0.85rem 1rem;
  cursor: pointer;
}

.card-info {
  flex: 1;
  min-width: 0;
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.4rem;
}

.family-badge {
  display: inline-block;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
  font-size: 0.65rem;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.strategy-name {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-all;
}

.card-meta {
  display: flex;
  gap: 0.75rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
}

.deploy-badge {
  color: var(--success);
  font-weight: 600;
}

.type-badge {
  color: var(--accent);
  font-weight: 500;
}

.description-block {
  background: rgba(255, 255, 255, 0.02);
  border-left: 2px solid var(--border);
  padding: 0.5rem 0.75rem;
  margin-bottom: 0.85rem;
  border-radius: 0 4px 4px 0;
}

.description-text {
  font-size: 0.825rem;
  line-height: 1.4;
  color: var(--text-secondary);
  margin: 0;
}

.indicators-block {
  margin-bottom: 0.85rem;
}

.indicator-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  margin-top: 0.3rem;
}

.indicator-chip {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.15rem 0.45rem;
  font-size: 0.725rem;
  color: var(--text-primary);
  font-weight: 500;
}

.expand-icon {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
  flex-shrink: 0;
}

.card-detail {
  padding: 0 1rem 1rem;
  border-top: 1px solid var(--border);
  padding-top: 0.75rem;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.6rem;
  margin-bottom: 0.75rem;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.detail-label {
  font-size: 0.65rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.detail-value {
  font-size: 0.85rem;
  color: var(--text-primary);
}

code.detail-value {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.8rem;
  background: var(--bg-primary);
  padding: 0.15rem 0.4rem;
  border-radius: 3px;
  word-break: break-all;
}

.deploy-info {
  margin-bottom: 0.75rem;
}

.deploy-info h4 {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--success);
  margin-bottom: 0.4rem;
}

.run-btn {
  background: var(--accent);
  color: #000;
  border: none;
  border-radius: 6px;
  padding: 0.5rem 1.25rem;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.run-btn:hover {
  background: var(--accent-hover);
}

.actions-row {
  display: flex;
  gap: 0.5rem;
}

.promote-btn {
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--accent);
  border-radius: 6px;
  padding: 0.5rem 1.25rem;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.promote-btn:hover {
  background: rgba(217, 119, 6, 0.08);
}
</style>
