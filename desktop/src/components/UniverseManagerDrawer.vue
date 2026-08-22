<template>
  <div v-if="isOpen" class="drawer-backdrop" @click.self="$emit('close')">
    <aside class="universe-drawer">
      <!-- Drawer Header -->
      <div class="drawer-header">
        <div class="header-info">
          <div class="title-row">
            <span class="icon">⚡</span>
            <h2>Manage Universe & Minicaps</h2>
          </div>
          <p class="subtitle">Add custom small-caps, micro-futures, and equities with instant historical data ingestion.</p>
        </div>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- Drawer Body -->
      <div class="drawer-body">
        <!-- Quick Preset Baskets -->
        <section class="section preset-baskets">
          <div class="section-header">
            <span class="section-title">1-CLICK PRESET BASKETS</span>
          </div>
          <div class="basket-buttons">
            <button class="basket-btn" @click="importBasket('smallcap')" :disabled="loading">
              <span class="b-icon">🧺</span>
              <span class="b-text">
                <strong>Small-Cap Core Basket</strong>
                <small>IJR, VB, SCHA</small>
              </span>
            </button>
            <button class="basket-btn" @click="importBasket('momentum')" :disabled="loading">
              <span class="b-icon">⚡</span>
              <span class="b-text">
                <strong>High-Beta Momentum</strong>
                <small>PLTR, TSLA, SOXL</small>
              </span>
            </button>
            <button class="basket-btn" @click="importBasket('megacap')" :disabled="loading">
              <span class="b-icon">🏢</span>
              <span class="b-text">
                <strong>Mega-Cap Leaders</strong>
                <small>MSFT, NVDA, GOOGL</small>
              </span>
            </button>
          </div>
        </section>

        <!-- Add Custom Ticker Form -->
        <section class="section add-instrument-form">
          <div class="section-header">
            <span class="section-title">ADD CUSTOM INSTRUMENT</span>
          </div>
          <form class="form-grid" @submit.prevent="handleSubmit">
            <div class="form-group symbol-group">
              <label>Ticker Symbol *</label>
              <input
                v-model="form.symbol"
                type="text"
                class="form-input mono"
                placeholder="e.g. IJR, PLTR, CL"
                maxlength="10"
                required
              />
            </div>

            <div class="form-group">
              <label>Asset Class *</label>
              <select v-model="form.assetClass" class="form-select">
                <option value="EQUITIES">US Equities / Minicap / ETF</option>
                <option value="FUTURES">CME / NYMEX Futures</option>
                <option value="FOREX">Forex Currency Pair</option>
              </select>
            </div>

            <div class="form-group full-width">
              <label>Description / Name *</label>
              <input
                v-model="form.name"
                type="text"
                class="form-input"
                placeholder="e.g. iShares Core S&P Small-Cap ETF"
                required
              />
            </div>

            <div class="form-group">
              <label>Point Multiplier ($/pt)</label>
              <input
                v-model.number="form.pointValue"
                type="number"
                step="0.1"
                min="0.1"
                class="form-input mono"
                required
              />
            </div>

            <div class="form-group">
              <label>Tick Size</label>
              <input
                v-model.number="form.tickSize"
                type="number"
                step="0.0001"
                min="0.00001"
                class="form-input mono"
                required
              />
            </div>

            <div class="form-actions full-width">
              <button type="submit" class="submit-btn" :disabled="loading || !form.symbol || !form.name">
                <span v-if="loading">Saving...</span>
                <span v-else>➕ Register Instrument</span>
              </button>
            </div>
          </form>
          <div v-if="formError" class="alert error">{{ formError }}</div>
          <div v-if="formSuccess" class="alert success">{{ formSuccess }}</div>
        </section>

        <!-- Active Instruments Table -->
        <section class="section active-instruments">
          <div class="section-header space-between">
            <span class="section-title">ACTIVE UNIVERSE ({{ filteredInstruments.length }})</span>
            <div class="filter-pills">
              <button
                :class="['pill-btn', { active: activeFilter === 'ALL' }]"
                @click="activeFilter = 'ALL'"
              >
                All
              </button>
              <button
                :class="['pill-btn equities', { active: activeFilter === 'EQUITIES' }]"
                @click="activeFilter = 'EQUITIES'"
              >
                Equities
              </button>
              <button
                :class="['pill-btn futures', { active: activeFilter === 'FUTURES' }]"
                @click="activeFilter = 'FUTURES'"
              >
                Futures
              </button>
              <button
                :class="['pill-btn forex', { active: activeFilter === 'FOREX' }]"
                @click="activeFilter = 'FOREX'"
              >
                Forex
              </button>
            </div>
          </div>

          <div class="instruments-list">
            <div
              v-for="inst in filteredInstruments"
              :key="inst.symbol"
              class="instrument-card"
              :class="inst.assetClass.toLowerCase()"
            >
              <div class="card-left">
                <div class="badge-row">
                  <span class="symbol-badge" :class="inst.assetClass.toLowerCase()">{{ inst.symbol }}</span>
                  <span class="type-tag">{{ inst.assetClass }}</span>
                  <span v-if="inst.isCustom" class="custom-tag">CUSTOM</span>
                </div>
                <div class="inst-name">{{ inst.name }}</div>
                <div class="inst-specs">
                  <span>Multiplier: ${{ inst.pointValue }}/pt</span>
                  <span>•</span>
                  <span>Tick: {{ inst.tickSize }}</span>
                  <span>•</span>
                  <span>CCY: {{ inst.currency }}</span>
                </div>
              </div>
              <div class="card-right">
                <button
                  v-if="inst.isCustom"
                  class="delete-btn"
                  title="Remove custom instrument"
                  @click="handleDelete(inst.symbol)"
                >
                  ✕ Remove
                </button>
                <span v-else class="builtin-badge">Built-in</span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { InstrumentDefinition } from '@/types/control-plane'

const props = defineProps<{
  isOpen: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'updated'): void
}>()

const { getInstruments, createInstrument, deleteInstrument } = useControlPlane()

const instruments = ref<InstrumentDefinition[]>([])
const loading = ref(false)
const formError = ref<string | null>(null)
const formSuccess = ref<string | null>(null)
const activeFilter = ref<'ALL' | 'EQUITIES' | 'FUTURES' | 'FOREX'>('ALL')

const form = ref({
  symbol: '',
  name: '',
  assetClass: 'EQUITIES',
  pointValue: 1.0,
  tickSize: 0.01
})

watch(() => form.value.symbol, (val) => {
  if (val) {
    form.value.symbol = val.toUpperCase().replace(/\s+/g, '')
  }
})

watch(() => form.value.assetClass, (val) => {
  if (val === 'EQUITIES') {
    form.value.pointValue = 1.0
    form.value.tickSize = 0.01
  } else if (val === 'FUTURES') {
    form.value.pointValue = 5.0
    form.value.tickSize = 0.25
  } else if (val === 'FOREX') {
    form.value.pointValue = 1.0
    form.value.tickSize = 0.0001
  }
})

const filteredInstruments = computed(() => {
  if (activeFilter.value === 'ALL') return instruments.value
  return instruments.value.filter(i => i.assetClass.toUpperCase() === activeFilter.value)
})

async function fetchInstruments() {
  try {
    loading.value = true
    instruments.value = await getInstruments()
  } catch (err: any) {
    formError.value = err.message
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  formError.value = null
  formSuccess.value = null
  try {
    loading.value = true
    const sym = form.value.symbol.trim().toUpperCase()
    await createInstrument({
      symbol: sym,
      name: form.value.name.trim(),
      assetClass: form.value.assetClass,
      pointValue: form.value.pointValue,
      tickSize: form.value.tickSize,
      currency: 'USD',
      isCustom: true,
      providerTicker: sym
    })
    formSuccess.value = `Successfully registered ${sym}!`
    form.value.symbol = ''
    form.value.name = ''
    await fetchInstruments()
    emit('updated')
  } catch (err: any) {
    formError.value = err.message
  } finally {
    loading.value = false
  }
}

async function handleDelete(symbol: string) {
  if (!confirm(`Are you sure you want to remove ${symbol} from the universe?`)) return
  formError.value = null
  formSuccess.value = null
  try {
    loading.value = true
    await deleteInstrument(symbol)
    formSuccess.value = `Removed ${symbol}.`
    await fetchInstruments()
    emit('updated')
  } catch (err: any) {
    formError.value = err.message
  } finally {
    loading.value = false
  }
}

async function importBasket(basketType: 'smallcap' | 'momentum' | 'megacap') {
  formError.value = null
  formSuccess.value = null
  loading.value = true

  const baskets: Record<string, Partial<InstrumentDefinition>[]> = {
    smallcap: [
      { symbol: 'IJR', name: 'iShares Core S&P Small-Cap ETF', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'VB', name: 'Vanguard Small-Cap ETF', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'SCHA', name: 'Schwab U.S. Small-Cap ETF', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 }
    ],
    momentum: [
      { symbol: 'PLTR', name: 'Palantir Technologies Inc.', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'TSLA', name: 'Tesla Inc.', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'SOXL', name: 'Direxion Semiconductor Bull 3X ETF', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 }
    ],
    megacap: [
      { symbol: 'MSFT', name: 'Microsoft Corporation', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'NVDA', name: 'NVIDIA Corporation', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 },
      { symbol: 'GOOGL', name: 'Alphabet Inc. (Google Class A)', assetClass: 'EQUITIES', pointValue: 1.0, tickSize: 0.01 }
    ]
  }

  const items = baskets[basketType] || []
  try {
    for (const item of items) {
      await createInstrument({
        ...item,
        currency: 'USD',
        isCustom: true,
        providerTicker: item.symbol
      })
    }
    formSuccess.value = `Imported ${items.length} instruments successfully!`
    await fetchInstruments()
    emit('updated')
  } catch (err: any) {
    formError.value = err.message
  } finally {
    loading.value = false
  }
}

watch(() => props.isOpen, (open) => {
  if (open) {
    fetchInstruments()
    formError.value = null
    formSuccess.value = null
  }
})

onMounted(() => {
  if (props.isOpen) {
    fetchInstruments()
  }
})
</script>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
  animation: fadeIn 0.2s ease-out;
}

.universe-drawer {
  width: 520px;
  max-width: 90vw;
  height: 100%;
  background: #0d0e12;
  border-left: 1px solid #1f232b;
  display: flex;
  flex-direction: column;
  box-shadow: -10px 0 30px rgba(0, 0, 0, 0.5);
  animation: slideIn 0.25s ease-out;
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.drawer-header {
  padding: 1.25rem 1.5rem;
  background: #12141a;
  border-bottom: 1px solid #1f232b;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.header-info h2 {
  margin: 0;
  font-size: 1.15rem;
  color: #f8fafc;
  font-weight: 700;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.title-row .icon {
  color: #f59e0b;
  font-size: 1.2rem;
}

.subtitle {
  margin: 0.35rem 0 0;
  font-size: 0.8rem;
  color: #94a3b8;
  line-height: 1.35;
}

.close-btn {
  background: transparent;
  border: 1px solid #2d3748;
  color: #94a3b8;
  font-size: 1rem;
  cursor: pointer;
  padding: 0.35rem 0.6rem;
  border-radius: 6px;
  transition: all 0.15s ease;
}

.close-btn:hover {
  background: #f43f5e;
  border-color: #f43f5e;
  color: white;
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.section {
  background: #141720;
  border: 1px solid #1e2330;
  border-radius: 10px;
  padding: 1.2rem;
}

.section-header {
  margin-bottom: 0.85rem;
}

.section-header.space-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-title {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #64748b;
  text-transform: uppercase;
}

.basket-buttons {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.6rem;
}

.basket-btn {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: #1a1e2b;
  border: 1px solid #262d3d;
  border-radius: 8px;
  padding: 0.65rem 0.85rem;
  color: #e2e8f0;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;
}

.basket-btn:hover:not(:disabled) {
  background: #23293a;
  border-color: #3b82f6;
  transform: translateY(-1px);
}

.b-icon {
  font-size: 1.25rem;
}

.b-text {
  display: flex;
  flex-direction: column;
}

.b-text strong {
  font-size: 0.85rem;
  color: #f1f5f9;
}

.b-text small {
  font-size: 0.72rem;
  color: #94a3b8;
  font-family: 'JetBrains Mono', monospace;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.form-group.full-width {
  grid-column: span 2;
}

.form-group label {
  font-size: 0.72rem;
  color: #94a3b8;
  font-weight: 600;
}

.form-input, .form-select {
  background: #0a0b0e;
  border: 1px solid #262d3d;
  border-radius: 6px;
  padding: 0.5rem 0.65rem;
  color: #f8fafc;
  font-size: 0.82rem;
}

.form-input.mono {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
}

.form-input:focus, .form-select:focus {
  outline: none;
  border-color: #f59e0b;
}

.submit-btn {
  width: 100%;
  background: #f59e0b;
  border: none;
  color: #000;
  font-weight: 700;
  padding: 0.65rem;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.85rem;
  transition: all 0.15s ease;
}

.submit-btn:hover:not(:disabled) {
  background: #fbbf24;
}

.submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.alert {
  margin-top: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  font-size: 0.78rem;
}

.alert.error {
  background: rgba(244, 63, 94, 0.15);
  border: 1px solid #f43f5e;
  color: #fda4af;
}

.alert.success {
  background: rgba(16, 185, 129, 0.15);
  border: 1px solid #10b981;
  color: #6ee7b7;
}

.filter-pills {
  display: flex;
  gap: 0.35rem;
}

.pill-btn {
  background: #1a1e2b;
  border: 1px solid #262d3d;
  color: #94a3b8;
  font-size: 0.7rem;
  padding: 0.2rem 0.5rem;
  border-radius: 12px;
  cursor: pointer;
}

.pill-btn.active {
  background: #f59e0b;
  color: #000;
  font-weight: 700;
  border-color: #f59e0b;
}

.pill-btn.equities.active {
  background: #06b6d4;
  border-color: #06b6d4;
  color: #000;
}

.pill-btn.futures.active {
  background: #a855f7;
  border-color: #a855f7;
  color: #fff;
}

.pill-btn.forex.active {
  background: #f59e0b;
  border-color: #f59e0b;
  color: #000;
}

.instruments-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 280px;
  overflow-y: auto;
  padding-right: 0.25rem;
}

.instrument-card {
  background: #0d0f14;
  border: 1px solid #1e2433;
  border-radius: 8px;
  padding: 0.75rem 0.85rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  transition: all 0.15s ease;
}

.instrument-card:hover {
  border-color: #334155;
}

.instrument-card.equities {
  border-left: 3px solid #06b6d4;
}

.instrument-card.futures {
  border-left: 3px solid #a855f7;
}

.instrument-card.forex {
  border-left: 3px solid #f59e0b;
}

.badge-row {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  margin-bottom: 0.25rem;
}

.symbol-badge {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.78rem;
  font-weight: 700;
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
}

.symbol-badge.equities {
  background: rgba(6, 182, 212, 0.2);
  color: #22d3ee;
}

.symbol-badge.futures {
  background: rgba(168, 85, 247, 0.2);
  color: #c084fc;
}

.symbol-badge.forex {
  background: rgba(245, 158, 11, 0.2);
  color: #fbbf24;
}

.type-tag {
  font-size: 0.65rem;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.custom-tag {
  font-size: 0.62rem;
  font-weight: 700;
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid #f59e0b;
  color: #f59e0b;
  padding: 0.05rem 0.35rem;
  border-radius: 3px;
}

.inst-name {
  font-size: 0.8rem;
  color: #cbd5e1;
  margin-bottom: 0.25rem;
}

.inst-specs {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.68rem;
  color: #64748b;
  font-family: 'JetBrains Mono', monospace;
}

.delete-btn {
  background: rgba(244, 63, 94, 0.15);
  border: 1px solid #f43f5e;
  color: #fda4af;
  font-size: 0.7rem;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.delete-btn:hover {
  background: #f43f5e;
  color: white;
}

.builtin-badge {
  font-size: 0.65rem;
  color: #475569;
  font-family: 'JetBrains Mono', monospace;
}
</style>
