<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { Strategy, RunConfig } from '@/types/control-plane'

const props = defineProps<{
  preselectedStrategy?: string
  preselectedSymbol?: string
}>()

const emit = defineEmits<{
  runStart: [runId: string]
  error: [message: string]
}>()

const { getStrategies, startRun, loading, error } = useControlPlane()

const strategies = ref<Strategy[]>([])
const selectedStrategy = ref('')
const selectedSymbol = ref('')
const selectedYear = ref(2025)
const capital = ref(100000)
const commission = ref(0.07)
const slippage = ref(0.0001)

watch(() => props.preselectedStrategy, (val) => {
  if (val && strategies.value.some((s) => s.id === val)) {
    selectedStrategy.value = val
    onStrategyChange()
  }
}, { immediate: true })

watch(() => props.preselectedSymbol, (val) => {
  if (val && symbolOptions.includes(val)) {
    selectedSymbol.value = val
  }
}, { immediate: true })

const years = Array.from({ length: 17 }, (_, i) => 2010 + i)
const symbolOptions = [
  'EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF',
  'AUD/USD', 'NZD/USD', 'USD/CAD', 'EUR/JPY',
  'GBP/JPY', 'XAU/USD',
]

onMounted(async () => {
  try {
    strategies.value = await getStrategies()
  } catch (e: any) {
    emit('error', `Failed to load strategies: ${e.message}`)
  }
})

function onStrategyChange() {
  const s = strategies.value.find((st) => st.id === selectedStrategy.value)
  if (s?.defaultSymbol) {
    const formatted = s.defaultSymbol.replace(/_/g, '/')
    if (symbolOptions.includes(formatted)) {
      selectedSymbol.value = formatted
    }
  }
}

async function run() {
  if (!selectedStrategy.value || !selectedSymbol.value) return

  const config: RunConfig = {
    strategyId: selectedStrategy.value,
    symbol: selectedSymbol.value.replace('/', '_'),
    mode: 'BACKTEST',
    barsSource: { type: 'year', year: selectedYear.value },
    capital: capital.value,
    commissionPerTrade: commission.value,
    slippagePct: slippage.value,
  }

  try {
    const result = await startRun(config)
    emit('runStart', result.runId)
  } catch (e: any) {
    emit('error', `Backtest failed: ${e.message}`)
  }
}
</script>

<template>
  <div class="backtest-form">
    <div class="form-row">
      <div class="field">
        <label>Strategy</label>
        <select v-model="selectedStrategy" @change="onStrategyChange">
          <option value="" disabled>Select a strategy...</option>
          <option v-for="s in strategies" :key="s.id" :value="s.id">
            {{ s.id }} ({{ s.family }})
          </option>
        </select>
      </div>
      <div class="field">
        <label>Pair</label>
        <select v-model="selectedSymbol">
          <option value="" disabled>Select pair...</option>
          <option v-for="sym in symbolOptions" :key="sym" :value="sym">
            {{ sym }}
          </option>
        </select>
      </div>
      <div class="field">
        <label>Year</label>
        <select v-model="selectedYear">
          <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
        </select>
      </div>
    </div>

    <div class="form-row">
      <div class="field">
        <label>Capital ($)</label>
        <input v-model.number="capital" type="number" min="1000" step="1000" />
      </div>
      <div class="field">
        <label>Commission ($)</label>
        <input v-model.number="commission" type="number" min="0" step="0.01" />
      </div>
      <div class="field">
        <label>Slippage (%)</label>
        <input v-model.number="slippage" type="number" min="0" step="0.0001" />
      </div>
    </div>

    <div v-if="error" class="form-error">{{ error }}</div>

    <button class="run-btn" :disabled="loading || !selectedStrategy || !selectedSymbol" @click="run">
      <span v-if="loading" class="spinner"></span>
      <span v-else>▶ Run Backtest</span>
    </button>
  </div>
</template>

<style scoped>
.backtest-form {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
  margin-bottom: 1rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

select, input {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem 0.65rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  outline: none;
  transition: border-color 0.15s;
}

select:focus, input:focus {
  border-color: var(--accent);
}

select option {
  background: var(--bg-secondary);
}

.form-error {
  color: var(--danger);
  font-size: 0.8rem;
  margin-bottom: 0.75rem;
}

.run-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  background: var(--accent);
  color: #000;
  border: none;
  border-radius: 6px;
  padding: 0.6rem 1.5rem;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.run-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.run-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  display: inline-block;
  width: 1rem;
  height: 1rem;
  border: 2px solid rgba(0,0,0,0.3);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }
</style>
