<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, computed } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import { useCostPresets } from '@/composables/useCostPresets'
import { useStrategyCatalog } from '@/composables/useStrategyCatalog'
import type { Strategy, RunConfig, InstrumentDefinition } from '@/types/control-plane'
import StrategySelector from '@/components/StrategySelector.vue'
import { RotateCcw, AlertTriangle, Zap } from '@lucide/vue'

const props = defineProps<{
  preselectedStrategy?: string
  preselectedSymbol?: string
  isDownloading?: boolean
}>()

const emit = defineEmits<{
  runsStart: [runs: { symbol: string, runId: string }[]]
  error: [message: string, context?: { symbol: string, year: string, tf: string }]
}>()

const years = Array.from({ length: 17 }, (_, i) => 2010 + i)

export interface FormInstrument {
  symbol: string
  displayName: string
  assetClass: 'FUTURES' | 'FOREX' | 'EQUITY' | 'OTHER'
  defaultCapital?: number
  defaultLotSize?: number
  defaultCommission?: number
}

const DEFAULT_INSTRUMENTS: FormInstrument[] = [
  // CME Micro & Mini Futures
  { symbol: 'MES', displayName: 'MES — Micro E-mini S&P 500 ($5/pt)', assetClass: 'FUTURES', defaultCapital: 50000, defaultLotSize: 1, defaultCommission: 0.62 },
  { symbol: 'MNQ', displayName: 'MNQ — Micro E-mini Nasdaq 100 ($2/pt)', assetClass: 'FUTURES', defaultCapital: 50000, defaultLotSize: 1, defaultCommission: 0.62 },
  { symbol: 'M2K', displayName: 'M2K — Micro E-mini Russell 2000 ($5/pt)', assetClass: 'FUTURES', defaultCapital: 50000, defaultLotSize: 1, defaultCommission: 0.62 },
  { symbol: 'EMD', displayName: 'EMD — E-mini S&P MidCap 400 ($100/pt)', assetClass: 'FUTURES', defaultCapital: 50000, defaultLotSize: 1, defaultCommission: 1.25 },

  // Forex Majors & Crosses
  { symbol: 'EUR/USD', displayName: 'EUR/USD — Euro / US Dollar', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'GBP/USD', displayName: 'GBP/USD — British Pound / US Dollar', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'USD/JPY', displayName: 'USD/JPY — US Dollar / Japanese Yen', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'USD/CHF', displayName: 'USD/CHF — US Dollar / Swiss Franc', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'AUD/USD', displayName: 'AUD/USD — Australian Dollar / US Dollar', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'NZD/USD', displayName: 'NZD/USD — New Zealand Dollar / US Dollar', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'USD/CAD', displayName: 'USD/CAD — US Dollar / Canadian Dollar', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'EUR/JPY', displayName: 'EUR/JPY — Euro / Japanese Yen', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'GBP/JPY', displayName: 'GBP/JPY — British Pound / Japanese Yen', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },
  { symbol: 'XAU/USD', displayName: 'XAU/USD — Spot Gold', assetClass: 'FOREX', defaultCapital: 1000, defaultLotSize: 0.01, defaultCommission: 0.07 },

  // US Small/Mid Cap Equities & ETFs
  { symbol: 'IJR', displayName: 'IJR — iShares Core S&P Small-Cap ETF', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
  { symbol: 'VB', displayName: 'VB — Vanguard Small-Cap ETF', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
  { symbol: 'SCHA', displayName: 'SCHA — Schwab U.S. Small-Cap ETF', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
  { symbol: 'PLTR', displayName: 'PLTR — Palantir Technologies', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
  { symbol: 'TSLA', displayName: 'TSLA — Tesla Inc.', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
  { symbol: 'SOXL', displayName: 'SOXL — Direxion Daily Semi Bull 3X', assetClass: 'EQUITY', defaultCapital: 10000, defaultLotSize: 10, defaultCommission: 0.00 },
]

const { getStrategies, startRun, loading, error, getBrokerAccounts, getInstruments } = useControlPlane()
const { getPresetForSymbol, getPresetForAssetClass } = useCostPresets()
const { isCompatibleBasket, inferAssetClass, getStrategy } = useStrategyCatalog()

const isSubmitting = ref(false)
const strategies = ref<Strategy[]>([])
const instruments = ref<FormInstrument[]>(DEFAULT_INSTRUMENTS)
const selectedStrategy = ref('')
const selectedSymbols = ref<string[]>([])
const selectedYear = ref<number | string>(2025)
const yearSelectionMode = ref<'single' | 'range' | 'all'>('single')
const selectedStartYear = ref(2020)
const selectedEndYear = ref(2025)

const isCostUserModified = ref<boolean>(false)
const capital = ref(1000)
const lotSize = ref(0.01)
const commission = ref(0.07)
const slippage = ref(0.0001)

const activeCostPreset = computed(() => {
  if (selectedSymbols.value.length > 0) {
    return getPresetForSymbol(selectedSymbols.value[0])
  }
  const s = getStrategy(selectedStrategy.value)
  if (s && s.assetClasses && s.assetClasses.length > 0) {
    return getPresetForAssetClass(s.assetClasses[0] as any)
  }
  return getPresetForSymbol(s?.defaultSymbol || 'EUR_USD')
})

const basketCompatibility = computed(() => {
  return isCompatibleBasket(selectedSymbols.value)
})

const dataTimeframe = ref('H1')
const strategyTimeframe = ref('H1')

// Run mode & Broker Account credentials state
const runMode = ref<'BACKTEST' | 'PAPER_IBKR' | 'LIVE_IBKR' | 'PAPER_OANDA' | 'LIVE_OANDA' | 'PAPER_STUB'>('BACKTEST')
const selectedAccountId = ref('default')
const maxDrawdownLimit = ref(10.0)
const dailyLossLimitPct = ref(5.0)
const weeklyLossLimitPct = ref(10.0)
const accounts = ref<any[]>([])

const filteredStrategies = computed(() => {
  if (runMode.value === 'BACKTEST') {
    return strategies.value
  }
  if (runMode.value === 'PAPER_IBKR') {
    return strategies.value.filter(
      (s) => (s.deployedMode === 'PAPER' && s.executionLabel === 'PAPER_IBKR') || !s.deployedMode,
    )
  }
  if (runMode.value === 'LIVE_IBKR') {
    return strategies.value.filter(
      (s) => s.deployedMode === 'LIVE' && s.executionLabel === 'LIVE_IBKR',
    )
  }
  if (runMode.value === 'PAPER_OANDA') {
    return strategies.value.filter(
      (s) => (s.deployedMode === 'PAPER' && s.executionLabel === 'PAPER_OANDA') || !s.deployedMode,
    )
  }
  if (runMode.value === 'LIVE_OANDA') {
    return strategies.value.filter(
      (s) => s.deployedMode === 'LIVE' && s.executionLabel === 'LIVE_OANDA',
    )
  }
  return strategies.value
})

const groupedInstruments = computed(() => {
  const futures = instruments.value.filter(i => i.assetClass === 'FUTURES')
  const forex = instruments.value.filter(i => i.assetClass === 'FOREX')
  const equities = instruments.value.filter(i => i.assetClass === 'EQUITY' || i.assetClass === 'OTHER')
  return [
    { title: '⚡ CME Futures (IBKR)', items: futures },
    { title: '💱 Forex Majors & Crosses', items: forex },
    { title: '📈 US Equities & ETFs', items: equities }
  ].filter(g => g.items.length > 0)
})

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

const isValidYearRange = computed(() => {
  if (yearSelectionMode.value !== 'range') return true
  return Number(selectedStartYear.value) <= Number(selectedEndYear.value)
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
    const [strats, accs, insts] = await Promise.all([
      getStrategies(),
      getBrokerAccounts().catch(() => []),
      getInstruments().catch(() => [])
    ])
    strategies.value = strats
    accounts.value = accs

    // Merge custom server instruments with default ones
    if (insts && insts.length > 0) {
      const mergedMap = new Map<string, FormInstrument>()
      DEFAULT_INSTRUMENTS.forEach(i => mergedMap.set(i.symbol, i))
      insts.forEach((inst: InstrumentDefinition) => {
        const sym = inst.symbol.replace(/_/g, '/')
        if (!mergedMap.has(sym)) {
          mergedMap.set(sym, {
            symbol: sym,
            displayName: `${sym} — ${inst.name || inst.symbol}`,
            assetClass: (inst.assetClass as any) || 'EQUITY',
            defaultCapital: inst.assetClass === 'FUTURES' ? 50000 : 10000,
            defaultLotSize: 1,
            defaultCommission: 0.0
          })
        }
      })
      instruments.value = Array.from(mergedMap.values())
    }

    // Restore from localStorage
    const savedRunMode = localStorage.getItem('bt_runMode')
    if (savedRunMode) runMode.value = savedRunMode as any

    // If preselectedStrategy is passed, default execution mode to BACKTEST so it's not filtered out
    if (props.preselectedStrategy) {
      runMode.value = 'BACKTEST'
    } else {
      const savedStrategy = localStorage.getItem('bt_selectedStrategy')
      if (savedStrategy && filteredStrategies.value.some(s => s.id === savedStrategy)) {
        selectedStrategy.value = savedStrategy
      }
    }

    if (props.preselectedSymbol) {
      const formatted = props.preselectedSymbol.replace(/_/g, '/')
      selectedSymbols.value = [formatted]
    } else {
      const savedSymbols = localStorage.getItem('bt_selectedSymbols')
      if (savedSymbols) {
        try {
          selectedSymbols.value = JSON.parse(savedSymbols)
        } catch {}
      }
    }
    const savedYearMode = localStorage.getItem('bt_yearSelectionMode')
    if (savedYearMode) {
      yearSelectionMode.value = savedYearMode as any
    }
    const savedYear = localStorage.getItem('bt_selectedYear')
    if (savedYear) {
      selectedYear.value = savedYear === 'all' ? 'all' : (parseInt(savedYear) || 2025)
    }
    const savedStartYear = localStorage.getItem('bt_selectedStartYear')
    if (savedStartYear) {
      selectedStartYear.value = parseInt(savedStartYear) || 2020
    }
    const savedEndYear = localStorage.getItem('bt_selectedEndYear')
    if (savedEndYear) {
      selectedEndYear.value = parseInt(savedEndYear) || 2025
    }
    const savedCapital = localStorage.getItem('bt_capital')
    if (savedCapital) capital.value = parseInt(savedCapital) || 1000
    const savedLotSize = localStorage.getItem('bt_lotSize')
    if (savedLotSize) lotSize.value = parseFloat(savedLotSize) || 0.01
    const savedCommission = localStorage.getItem('bt_commission')
    if (savedCommission) commission.value = parseFloat(savedCommission) || 0.07
    const savedSlippage = localStorage.getItem('bt_slippage')
    if (savedSlippage) slippage.value = parseFloat(savedSlippage) || 0.0001
    const savedDataTf = localStorage.getItem('bt_dataTimeframe')
    if (savedDataTf) dataTimeframe.value = savedDataTf
    const savedStratTf = localStorage.getItem('bt_strategyTimeframe')
    if (savedStratTf) strategyTimeframe.value = savedStratTf
    const savedAccountId = localStorage.getItem('bt_accountId')
    if (savedAccountId) selectedAccountId.value = savedAccountId
  } catch (e: any) {
    emit('error', `Failed to load initialization data: ${e.message}`)
  }
})

// Setup watchers to save to localStorage
watch(selectedStrategy, (val) => localStorage.setItem('bt_selectedStrategy', val))
watch(selectedSymbols, (val) => {
  localStorage.setItem('bt_selectedSymbols', JSON.stringify(val))
  if (val.length === 1) {
    applyInstrumentDefaults(val[0])
  }
}, { deep: true })
watch(yearSelectionMode, (val) => localStorage.setItem('bt_yearSelectionMode', val))
watch(selectedYear, (val) => localStorage.setItem('bt_selectedYear', val.toString()))
watch(selectedStartYear, (val) => localStorage.setItem('bt_selectedStartYear', val.toString()))
watch(selectedEndYear, (val) => localStorage.setItem('bt_selectedEndYear', val.toString()))
watch(capital, (val) => localStorage.setItem('bt_capital', val.toString()))
watch(lotSize, (val) => localStorage.setItem('bt_lotSize', val.toString()))
watch(commission, (val) => localStorage.setItem('bt_commission', val.toString()))
watch(slippage, (val) => localStorage.setItem('bt_slippage', val.toString()))
watch(dataTimeframe, (val) => localStorage.setItem('bt_dataTimeframe', val))
watch(strategyTimeframe, (val) => localStorage.setItem('bt_strategyTimeframe', val))
watch(runMode, (val) => {
  localStorage.setItem('bt_runMode', val)
  const isValid = filteredStrategies.value.some(s => s.id === selectedStrategy.value)
  if (!isValid) {
    selectedStrategy.value = ''
    selectedSymbols.value = []
  }
  // Auto-switch broker account default based on runMode
  if (val === 'PAPER_IBKR' || val === 'LIVE_IBKR') {
    const ibkrAcc = accounts.value.find(a => a.provider === 'IBKR' || a.id.includes('ibkr'))
    if (ibkrAcc) selectedAccountId.value = ibkrAcc.id
  } else if (val === 'PAPER_OANDA' || val === 'LIVE_OANDA') {
    const oandaAcc = accounts.value.find(a => a.provider === 'OANDA' || a.id.includes('oanda'))
    if (oandaAcc) selectedAccountId.value = oandaAcc.id
  }
})
watch(selectedAccountId, (val) => localStorage.setItem('bt_accountId', val))

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

watch([() => props.preselectedStrategy, strategies], ([val, list]) => {
  if (val && list.some((s) => s.id === val)) {
    selectedStrategy.value = val
    if (!props.preselectedSymbol) {
      onStrategyChange()
    }
  }
}, { immediate: true })

watch(() => props.preselectedSymbol, (val) => {
  if (val) {
    const formatted = val.replace(/_/g, '/')
    selectedSymbols.value = [formatted]
    applyInstrumentDefaults(formatted)
  }
}, { immediate: true })

function applyCostPreset(force = false) {
  if (!force && isCostUserModified.value) {
    return
  }
  const preset = activeCostPreset.value
  if (preset) {
    capital.value = preset.capital
    lotSize.value = preset.lotSize
    commission.value = preset.commissionPerTrade
    slippage.value = preset.slippagePct
    if (force) {
      isCostUserModified.value = false
    }
  }
}

function onManualCostInput() {
  isCostUserModified.value = true
}

function resetToPreset() {
  applyCostPreset(true)
}

function applyInstrumentDefaults(sym: string) {
  applyCostPreset(false)
}

function toggleSymbol(sym: string) {
  const idx = selectedSymbols.value.indexOf(sym)
  if (idx >= 0) {
    selectedSymbols.value.splice(idx, 1)
  } else {
    selectedSymbols.value.push(sym)
  }
  applyCostPreset(false)
}

function onStrategySelected(strat: Strategy) {
  selectedStrategy.value = strat.id
  if (strat.recommendedSymbols && strat.recommendedSymbols.length > 0) {
    const formatted = strat.recommendedSymbols[0].replace(/_/g, '/')
    selectedSymbols.value = [formatted]
  } else if (strat.defaultSymbol) {
    const formatted = strat.defaultSymbol.replace(/_/g, '/')
    selectedSymbols.value = [formatted]
  }
  if (strat.timeframeSuitability && strat.timeframeSuitability.length > 0) {
    strategyTimeframe.value = strat.timeframeSuitability[0]
  }
  applyCostPreset(false)
}

function onStrategyChange() {
  const s = getStrategy(selectedStrategy.value)
  if (s) {
    onStrategySelected(s)
  }
}

async function run() {
  if (isSubmitting.value) return
  if (!selectedStrategy.value || selectedSymbols.value.length === 0) return
  if (!basketCompatibility.value.compatible) return

  isSubmitting.value = true
  try {
    const yearSpec = yearSelectionMode.value === 'all'
      ? 'all'
      : (yearSelectionMode.value === 'range'
          ? `${selectedStartYear.value}-${selectedEndYear.value}`
          : selectedYear.value.toString())

    const isBacktest = runMode.value === 'BACKTEST'
    const mode = isBacktest ? 'BACKTEST' : (runMode.value.startsWith('LIVE') ? 'LIVE' : 'PAPER')

    const runPromises = selectedSymbols.value.map(async (sym) => {
      const config: any = {
        strategyId: selectedStrategy.value,
        symbol: sym.replace('/', '_'),
        mode: mode,
        barsSource: { type: 'year', year: yearSpec },
        capital: capital.value,
        lotSize: lotSize.value,
        commissionPerTrade: commission.value,
        slippagePct: slippage.value,
        dataTimeframe: dataTimeframe.value,
        strategyTimeframe: strategyTimeframe.value,
      }
      if (!isBacktest) {
        config.executionLabel = runMode.value
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
    const yearSpec = yearSelectionMode.value === 'all'
      ? 'all'
      : (yearSelectionMode.value === 'range'
          ? `${selectedStartYear.value}-${selectedEndYear.value}`
          : selectedYear.value.toString())
    emit('error', `Execution launch failed: ${e.message}`, {
      symbol: selectedSymbols.value[0]?.replace('/', '_').toLowerCase() || 'mes',
      year: yearSpec,
      tf: dataTimeframe.value.toLowerCase()
    })
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="backtest-form">
    <div class="form-row">
      <div class="field">
        <label>Execution Mode</label>
        <select v-model="runMode">
          <option value="BACKTEST">Backtest (Simulation — All Assets)</option>
          <option value="PAPER_IBKR">Interactive Brokers (IBKR) Paper Trading</option>
          <option value="LIVE_IBKR">Interactive Brokers (IBKR) Live Trading</option>
          <option value="PAPER_OANDA">OANDA Paper Trading</option>
          <option value="LIVE_OANDA">OANDA Live Trading</option>
          <option value="PAPER_STUB">Offline Paper Simulation (Stub)</option>
        </select>
      </div>
      <div v-if="runMode !== 'BACKTEST' && runMode !== 'PAPER_STUB'" class="field">
        <label>Broker Account</label>
        <select v-model="selectedAccountId">
          <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
            {{ acc.id }} ({{ acc.provider }} - {{ acc.maskedAccountId || acc.host + ':' + acc.port }})
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
      <div class="field strategy-field">
        <label>Strategy</label>
        <StrategySelector
          v-model="selectedStrategy"
          @change="onStrategySelected"
        />
      </div>
      <div class="field" ref="dropdownRef">
        <label>Instruments / Pairs</label>
        <div class="multiselect-container">
          <div class="multiselect-trigger" @click="dropdownOpen = !dropdownOpen">
            <span v-if="selectedSymbols.length === 0" class="placeholder">Select instruments...</span>
            <span v-else-if="selectedSymbols.length === 1">{{ selectedSymbols[0] }}</span>
            <span v-else>{{ selectedSymbols.length }} instruments selected</span>
            <span class="arrow" :class="{ open: dropdownOpen }">▼</span>
          </div>
          <div v-if="dropdownOpen" class="multiselect-dropdown">
            <div v-for="group in groupedInstruments" :key="group.title" class="multiselect-group">
              <div class="group-header">{{ group.title }}</div>
              <label v-for="item in group.items" :key="item.symbol" class="multiselect-item">
                <input
                  type="checkbox"
                  :checked="selectedSymbols.includes(item.symbol)"
                  @change="toggleSymbol(item.symbol)"
                />
                <span class="item-label">{{ item.displayName }}</span>
              </label>
            </div>
          </div>
        </div>
      </div>
      <div v-if="runMode === 'BACKTEST'" class="field">
        <label>Backtest Period</label>
        <div style="display: flex; flex-direction: column; gap: 0.35rem;">
          <select v-model="yearSelectionMode" style="width: 100%;">
            <option value="single">Single Year</option>
            <option value="range">Year Range</option>
            <option value="all">All History</option>
          </select>
          
          <select v-if="yearSelectionMode === 'single'" v-model="selectedYear" style="width: 100%;">
            <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
          </select>
          
          <div v-if="yearSelectionMode === 'range'" style="display: grid; grid-template-columns: 1fr 1fr; gap: 0.35rem;">
            <select v-model="selectedStartYear" style="width: 100%; font-size: 0.8rem; padding: 0.4rem 0.5rem;">
              <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
            </select>
            <select v-model="selectedEndYear" style="width: 100%; font-size: 0.8rem; padding: 0.4rem 0.5rem;">
              <option v-for="y in years" :key="y" :value="y">{{ y }}</option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <!-- Basket Compatibility Warning (Red Team Hardened) -->
    <div v-if="!basketCompatibility.compatible" class="form-warning basket-warning">
      <AlertTriangle :size="16" class="warning-icon" />
      <span>{{ basketCompatibility.reason }}</span>
    </div>

    <!-- Cost & Sizing Section with Auto-Preset Engine -->
    <div v-if="runMode === 'BACKTEST'" class="cost-preset-card">
      <div class="cost-preset-header">
        <div class="preset-info">
          <span class="preset-badge">
            <Zap :size="13" /> Preset: {{ activeCostPreset.name }}
          </span>
          <span class="preset-desc">{{ activeCostPreset.description }}</span>
        </div>
        <button
          v-if="isCostUserModified"
          type="button"
          class="btn-reset-preset"
          @click="resetToPreset"
        >
          <RotateCcw :size="12" /> Reset to preset
        </button>
      </div>

      <div class="form-row inputs-row">
        <div class="field">
          <label>Capital ($)</label>
          <input v-model.number="capital" type="number" min="100" step="1000" @input="onManualCostInput" />
        </div>
        <div class="field">
          <label>Lot Size / Contracts ({{ activeCostPreset.unitLabel }})</label>
          <input v-model.number="lotSize" type="number" min="0.001" :step="activeCostPreset.assetClass === 'FUTURES' ? 1 : 0.01" @input="onManualCostInput" />
        </div>
        <div class="field">
          <label>Commission ($)</label>
          <input v-model.number="commission" type="number" min="0" step="0.01" @input="onManualCostInput" />
        </div>
        <div class="field">
          <label>Slippage (%)</label>
          <input v-model.number="slippage" type="number" min="0" step="0.00001" @input="onManualCostInput" />
        </div>
      </div>
    </div>

    <div v-else class="form-row inputs-row">
      <div class="field">
        <label>Lot Size / Contracts ({{ activeCostPreset.unitLabel }})</label>
        <input v-model.number="lotSize" type="number" min="0.001" :step="activeCostPreset.assetClass === 'FUTURES' ? 1 : 0.01" />
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

    <div v-if="runMode === 'BACKTEST' && !isValidYearRange" class="form-error">
      Validation Error: Start Year cannot be greater than End Year.
    </div>

    <div v-if="error" class="form-error">{{ error }}</div>

    <div style="display: flex; flex-direction: column; gap: 0.75rem;">
      <button class="run-btn" :disabled="loading || isSubmitting || props.isDownloading || !selectedStrategy || selectedSymbols.length === 0 || !isValidTimeframe || !isValidYearRange || !basketCompatibility.compatible" @click="run">
        <span v-if="loading || isSubmitting" class="spinner"></span>
        <span v-if="loading || isSubmitting">
          Starting (ingestion may take a moment)...
        </span>
        <span v-else-if="props.isDownloading">
          ⏳ Ingestion in progress...
        </span>
        <span v-else>
          {{ runMode === 'BACKTEST' ? '▶ Run Backtest' : (runMode.startsWith('LIVE') ? '▶ Start Live Trading' : '▶ Start Paper Trading') }}
        </span>
      </button>

      <div v-if="isSubmitting" class="banner info">
        ℹ️ Launching execution. If historical price files are missing for the selected instrument(s)/year, they will be auto-downloaded first. This can take up to a minute.
      </div>
    </div>
  </div>
</template>

<style scoped>
.backtest-form {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
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
  max-height: 280px;
  overflow-y: auto;
  z-index: 50;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.5);
  padding: 0.25rem;
}

.multiselect-group {
  margin-bottom: 0.4rem;
}

.group-header {
  font-size: 0.7rem;
  font-weight: 700;
  color: var(--accent);
  padding: 0.35rem 0.5rem 0.2rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.multiselect-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.5rem;
  border-radius: 4px;
  cursor: pointer;
  color: var(--text-primary);
  font-size: 0.85rem;
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

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  font-size: 0.85rem;
  border: 1px solid transparent;
}
.banner.info {
  background: #0f1d2d;
  color: #93c5fd;
  border-color: #1e3a5f;
}

.basket-warning {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.35);
  color: #f87171;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 0.85rem;
  margin-top: 0.5rem;
}

.basket-warning .warning-icon {
  flex-shrink: 0;
  color: #ef4444;
}

.cost-preset-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-top: 0.75rem;
  margin-bottom: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cost-preset-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.preset-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.preset-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 0.75rem;
  font-weight: 600;
  background: rgba(41, 98, 255, 0.15);
  color: #2962ff;
  border: 1px solid rgba(41, 98, 255, 0.3);
  padding: 2px 8px;
  border-radius: 4px;
}

.preset-desc {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.btn-reset-preset {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: var(--text-secondary);
  font-size: 0.75rem;
  padding: 3px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-reset-preset:hover {
  background: var(--border);
  color: var(--text-primary);
  border-color: var(--accent);
}
</style>
