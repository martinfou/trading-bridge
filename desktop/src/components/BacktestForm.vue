<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, computed } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { Strategy, RunConfig } from '@/types/control-plane'

const props = defineProps<{
  preselectedStrategy?: string
  preselectedSymbol?: string
}>()

const emit = defineEmits<{
  runsStart: [runs: { symbol: string, runId: string }[]]
  error: [message: string]
}>()

const { getStrategies, startRun, loading, error, getBrokerAccounts } = useControlPlane()

const strategies = ref<Strategy[]>([])
const selectedStrategy = ref('')
const selectedSymbols = ref<string[]>([])
const selectedYear = ref<number | string>(2025)
const capital = ref(1000)
const lotSize = ref(0.01)
const commission = ref(0.07)
const slippage = ref(0.0001)

const dataTimeframe = ref('H1')
const strategyTimeframe = ref('H1')

// Run mode & Broker Account credentials state
const runMode = ref<'BACKTEST' | 'PAPER_OANDA' | 'LIVE_OANDA'>('BACKTEST')
const selectedAccountId = ref('default')
const maxDrawdownLimit = ref(10.0)
const dailyLossLimitPct = ref(5.0)
const weeklyLossLimitPct = ref(10.0)
const accounts = ref<any[]>([])

const timeframeScale: Record<string, number> = {
  'M1': 1,
  'M30': 30,
  'H1': 60,
  'D1': 1440
}

const isValidTimeframe = computed(() => {
  const dVal = timeframeScale[dataTimeframe.value] || 0
  const sVal = timeframeScale[strategyTimeframe.value] || 0
  return dVal <= sVal
})

const dropdownRef = ref<HTMLElement | null>(null)
const dropdownOpen = ref(false)

function handleClickOutside(event: MouseEvent) {
  if (dropdownRef.value && !dropdownRef.value.contains(event.target as Node)) {
    dropdownOpen.value = false
  }
}

onMounted(async () => {
  document.addEventListener('click', handleClickOutside)
  try {
    const [strats, accs] = await Promise.all([
      getStrategies(),
      getBrokerAccounts().catch(() => [])
    ])
    strategies.value = strats
    accounts.value = accs
  } catch (e: any) {
    emit('error', `Failed to load initialization data: ${e.message}`)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

watch(() => props.preselectedStrategy, (val) => {
  if (val && strategies.value.some((s) => s.id === val)) {
    selectedStrategy.value = val
    onStrategyChange()
  }
}, { immediate: true })

watch(() => props.preselectedSymbol, (val) => {
  if (val && symbolOptions.includes(val)) {
    selectedSymbols.value = [val]
  }
}, { immediate: true })

const years = Array.from({ length: 17 }, (_, i) => 2010 + i)
const symbolOptions = [
  'EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF',
  'AUD/USD', 'NZD/USD', 'USD/CAD', 'EUR/JPY',
  'GBP/JPY', 'XAU/USD',
]

function toggleSymbol(sym: string) {
  const idx = selectedSymbols.value.indexOf(sym)
  if (idx >= 0) {
    selectedSymbols.value.splice(idx, 1)
  } else {
    selectedSymbols.value.push(sym)
  }
}

function onStrategyChange() {
  const s = strategies.value.find((st) => st.id === selectedStrategy.value)
  if (s?.defaultSymbol) {
    const formatted = s.defaultSymbol.replace(/_/g, '/')
    if (symbolOptions.includes(formatted)) {
      selectedSymbols.value = [formatted]
    }
  }
}

async function run() {
  if (!selectedStrategy.value || selectedSymbols.value.length === 0) return

  try {
    const runPromises = selectedSymbols.value.map(async (sym) => {
      const config: any = {
        strategyId: selectedStrategy.value,
        symbol: sym.replace('/', '_'),
        mode: runMode.value === 'BACKTEST' ? 'BACKTEST' : (runMode.value === 'PAPER_OANDA' ? 'PAPER' : 'LIVE'),
        barsSource: { type: 'year', year: selectedYear.value },
        capital: capital.value,
        lotSize: lotSize.value,
        commissionPerTrade: commission.value,
        slippagePct: slippage.value,
        dataTimeframe: dataTimeframe.value,
        strategyTimeframe: strategyTimeframe.value,
      }
      if (runMode.value !== 'BACKTEST') {
        config.executionLabel = runMode.value === 'PAPER_OANDA' ? 'PAPER_OANDA' : 'LIVE_OANDA'
        config.brokerAccountId = selectedAccountId.value
        config.dailyLossLimitPct = dailyLossLimitPct.value
        config.weeklyLossLimitPct = weeklyLossLimitPct.value
      }
      const res = await startRun(config)
      return { symbol: sym, runId: res.runId }
    })

    const started = await Promise.all(runPromises)
    emit('runsStart', started)
  } catch (e: any) {
    emit('error', `Execution launch failed: ${e.message}`)
  }
}
</script>

<template>
  <div class="backtest-form">
    <div class="form-row">
      <div class="field">
        <label>Execution Mode</label>
        <select v-model="runMode">
          <option value="BACKTEST">Backtest</option>
          <option value="PAPER_OANDA">OANDA Paper Trading</option>
          <option value="LIVE_OANDA">OANDA Live Trading</option>
        </select>
      </div>
      <div v-if="runMode !== 'BACKTEST'" class="field">
        <label>Broker Account</label>
        <select v-model="selectedAccountId">
          <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
            {{ acc.id }} ({{ acc.provider }} - {{ acc.maskedAccountId }})
          </option>
        </select>
      </div>
      <div v-if="runMode !== 'BACKTEST'" class="field">
        <label>Max Drawdown Kill Switch (%)</label>
        <input v-model.number="maxDrawdownLimit" type="number" min="1" max="50" step="0.5" disabled />
        <span style="font-size: 0.65rem; color: var(--accent); margin-top: 0.15rem; display: block;">⚠️ 10.0% default limit active</span>
      </div>
    </div>

    <div v-if="runMode !== 'BACKTEST'" class="form-row">
      <div class="field">
        <label>Daily Loss Limit (%)</label>
        <input v-model.number="dailyLossLimitPct" type="number" min="0.1" max="50" step="0.1" />
      </div>
      <div class="field">
        <label>Weekly Loss Limit (%)</label>
        <input v-model.number="weeklyLossLimitPct" type="number" min="0.1" max="50" step="0.1" />
      </div>
    </div>

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
      <div class="field" ref="dropdownRef">
        <label>Pairs</label>
        <div class="multiselect-container">
          <div class="multiselect-trigger" @click="dropdownOpen = !dropdownOpen">
            <span v-if="selectedSymbols.length === 0" class="placeholder">Select pairs...</span>
            <span v-else-if="selectedSymbols.length === 1">{{ selectedSymbols[0] }}</span>
            <span v-else>{{ selectedSymbols.length }} pairs selected</span>
            <span class="arrow" :class="{ open: dropdownOpen }">▼</span>
          </div>
          <div v-if="dropdownOpen" class="multiselect-dropdown">
            <label v-for="sym in symbolOptions" :key="sym" class="multiselect-item">
              <input
                type="checkbox"
                :checked="selectedSymbols.includes(sym)"
                @change="toggleSymbol(sym)"
              />
              <span class="item-label">{{ sym }}</span>
            </label>
          </div>
        </div>
      </div>
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Year</label>
        <select v-model="selectedYear">
          <option value="all">All Years</option>
          <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
        </select>
      </div>
    </div>

    <div class="form-row inputs-row">
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Capital ($)</label>
        <input v-model.number="capital" type="number" min="1000" step="1000" />
      </div>
      <div class="field">
        <label>Lot Size</label>
        <input v-model.number="lotSize" type="number" min="0.001" step="0.01" />
      </div>
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Commission ($)</label>
        <input v-model.number="commission" type="number" min="0" step="0.01" />
      </div>
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Slippage (%)</label>
        <input v-model.number="slippage" type="number" min="0" step="0.0001" />
      </div>
    </div>

    <div class="form-row">
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Data Timeframe</label>
        <select v-model="dataTimeframe">
          <option value="M1">M1 (1 Minute)</option>
          <option value="H1">H1 (1 Hour)</option>
        </select>
      </div>
      <div class="field">
        <label>Strategy Timeframe</label>
        <select v-model="strategyTimeframe">
          <option value="M1">M1 (1 Minute)</option>
          <option value="M30">M30 (30 Minutes)</option>
          <option value="H1">H1 (1 Hour)</option>
          <option value="D1">D1 (1 Day)</option>
        </select>
      </div>
      <div v-if="runMode === 'BACKTEST'" class="field explanation-field">
        <label>Data vs Strategy Timeframe</label>
        <div class="timeframe-warning-info">
          H1 data runs faster; M1 data is more realistic but takes longer.
        </div>
      </div>
    </div>

    <div v-if="runMode === 'BACKTEST' && !isValidTimeframe" class="form-error">
      Validation Error: Data Timeframe cannot be higher than Strategy Timeframe.
    </div>

    <div v-if="error" class="form-error">{{ error }}</div>

    <button class="run-btn" :disabled="loading || !selectedStrategy || selectedSymbols.length === 0 || !isValidTimeframe" @click="run">
      <span v-if="loading" class="spinner"></span>
      <span v-else>
        {{ runMode === 'BACKTEST' ? '▶ Run Backtest' : (runMode === 'PAPER_OANDA' ? '▶ Start Paper Trading' : '▶ Start Live Trading') }}
      </span>
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

.inputs-row {
  grid-template-columns: repeat(4, 1fr);
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

/* Custom Multiselect styles */
.multiselect-container {
  position: relative;
  width: 100%;
}

.multiselect-trigger {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem 0.65rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  user-select: none;
  min-height: 38px;
  width: 100%;
}

.multiselect-trigger:hover {
  border-color: var(--accent);
}

.placeholder {
  color: var(--text-secondary);
}

.arrow {
  font-size: 0.65rem;
  transition: transform 0.15s;
  color: var(--text-secondary);
}

.arrow.open {
  transform: rotate(180deg);
}

.multiselect-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 6px;
  margin-top: 4px;
  max-height: 250px;
  overflow-y: auto;
  z-index: 50;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.5);
  padding: 0.25rem;
}

.multiselect-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.5rem;
  border-radius: 4px;
  cursor: pointer;
  color: var(--text-primary);
  font-size: 0.875rem;
  transition: background 0.15s;
  user-select: none;
}

.multiselect-item:hover {
  background: var(--bg-card);
}

.multiselect-item input[type="checkbox"] {
  accent-color: var(--accent);
  width: 1rem;
  height: 1rem;
  cursor: pointer;
}

.item-label {
  font-size: 0.85rem;
}

.timeframe-warning-info {
  font-size: 0.8rem;
  color: var(--text-secondary);
  line-height: 1.3;
  display: flex;
  align-items: center;
  height: 100%;
  padding-top: 0.25rem;
}
</style>
