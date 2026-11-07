<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useControlPlane } from '../composables/useControlPlane'
import { useControlPlaneConfig } from '../composables/controlPlaneConfig'
import { useRunWebSocket } from '../composables/useRunWebSocket'
import TradeChart from '../components/TradeChart.vue'
import TradeTable from '../components/TradeTable.vue'
import KpiStrip from '../components/KpiStrip.vue'
import EquityChart from '../components/EquityChart.vue'
import OrderBook from '../components/OrderBook.vue'
import type { Bar, Trade, BrokerBalanceSnapshot, BrokerVenueTrade } from '@/types/control-plane'

const selectedCurrency = ref<string>(localStorage.getItem('trading_display_currency') || 'USD')
watch(selectedCurrency, (val) => {
  localStorage.setItem('trading_display_currency', val)
})

const currencyFactors = ref<Record<string, { rate: number; symbol: string }>>({
  USD: { rate: 1.0, symbol: '$' },
  CAD: { rate: 1.40, symbol: 'C$' },
  EUR: { rate: 0.92, symbol: '€' },
  GBP: { rate: 0.78, symbol: '£' },
  JPY: { rate: 155.0, symbol: '¥' }
})

async function fetchExchangeRates() {
  try {
    const res = await fetch('https://open.er-api.com/v6/latest/USD')
    if (!res.ok) throw new Error('API response not OK')
    const data = await res.json()
    if (data && data.rates) {
      if (data.rates.CAD) currencyFactors.value.CAD.rate = data.rates.CAD
      if (data.rates.EUR) currencyFactors.value.EUR.rate = data.rates.EUR
      if (data.rates.GBP) currencyFactors.value.GBP.rate = data.rates.GBP
      if (data.rates.JPY) currencyFactors.value.JPY.rate = data.rates.JPY
      console.log('Real-time exchange rates updated:', currencyFactors.value)
    }
  } catch (err) {
    console.warn('Failed to fetch real-time exchange rates, using defaults:', err)
  }
}

function convertCurrencyAmount(value: number | null | undefined, fromCurrency: string = 'USD', toCurrency: string = selectedCurrency.value): number {
  if (value === null || value === undefined || !Number.isFinite(value)) return 0
  const fromCurr = (fromCurrency || 'USD').toUpperCase()
  const toCurr = (toCurrency || 'USD').toUpperCase()
  if (fromCurr === toCurr) return value
  const fromRate = currencyFactors.value[fromCurr]?.rate || 1.0
  const toRate = currencyFactors.value[toCurr]?.rate || 1.0
  return (value / fromRate) * toRate
}

function formatNativeCurrency(value: number | null | undefined, currency: string = 'USD'): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  const curr = (currency || 'USD').toUpperCase()
  const symbol = currencyFactors.value[curr]?.symbol || (curr + ' ')
  return `${symbol}${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatCurrencyVal(value: number | null | undefined, fromCurrency: string = 'USD'): string {
  if (value === null || value === undefined) return '—'
  const toCurr = selectedCurrency.value
  const symbol = currencyFactors.value[toCurr]?.symbol || '$'
  const converted = convertCurrencyAmount(value, fromCurrency, toCurr)
  return `${symbol}${converted.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatCurrencyValNoDecimals(value: number | null | undefined, fromCurrency: string = 'USD'): string {
  if (value === null || value === undefined) return '—'
  const toCurr = selectedCurrency.value
  const symbol = currencyFactors.value[toCurr]?.symbol || '$'
  const converted = convertCurrencyAmount(value, fromCurrency, toCurr)
  return `${symbol}${Math.round(converted).toLocaleString()}`
}

function formatInstrumentPrice(price: number | string | null | undefined, symbol?: string): string {
  if (price === null || price === undefined || price === '') return '—'
  const num = typeof price === 'number' ? price : Number(price)
  if (!Number.isFinite(num) || num <= 0) return '—'
  const sym = (symbol || '').toUpperCase()
  if (sym.includes('JPY')) {
    return num.toFixed(3)
  }
  const twoDecTokens = ['XAU', 'XAG', 'MES', 'MNQ', 'ES', 'NQ', 'YM', 'RTY', 'GC', 'SI', 'CL', 'NG', 'ZB', 'ZN', 'US30', 'SPX', 'NAS', 'WTI', 'BRENT']
  if (twoDecTokens.some(tok => sym === tok || sym.startsWith(tok + '_') || sym.startsWith(tok + '/') || sym.endsWith('_' + tok))) {
    return num.toFixed(2)
  }
  if (num >= 100) {
    return num.toFixed(2)
  }
  return num.toFixed(5)
}

const { getControlSummary, killStrategy, getBars, getTrades, getEquityCurve, getBrokerAccounts, getBrokerBalances, discoverOandaAccounts, saveBrokerAccounts, testBrokerAccount } = useControlPlane()
const route = useRoute()
const router = useRouter()

const summary = ref<any>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const selectedRunId = ref<string | null>(null)
const activeTab = ref<'overview' | 'chart' | 'trades' | 'positions' | 'sentiment' | 'pending'>('chart')
const tradeChartRef = ref<any>(null)
const currentBid = ref<number | null>(null)
const currentAsk = ref<number | null>(null)
const expandedVenueId = ref<string | null>(null)

// Broker balances state
const brokerBalances = ref<BrokerBalanceSnapshot[]>([])

const connectedVenues = computed(() => {
  return (brokerBalances.value || []).filter(b => b.connected !== false)
})

const showDeskTrades = ref(true)
const selectedTradeVenueFilter = ref<string>('ALL')
const selectedTradeStateFilter = ref<'ALL' | 'OPEN' | 'CLOSED'>('ALL')
const selectedTradeStrategyFilter = ref<string>('ALL')

function cleanStrategyTag(raw?: string | null): string {
  if (!raw) return ''
  return raw.trim().replace(/_(MGC|MNQ|MES|M2K|MCL|GC|NQ|ES|GBPJPY|GBP_JPY|USDCHF|USD_CHF|USDJPY|USD_JPY|EURUSD|EUR_USD)[A-Z0-9]*$/i, '')
}

const allBrokerTrades = computed<BrokerVenueTrade[]>(() => {
  const out: BrokerVenueTrade[] = []
  for (const venue of brokerBalances.value || []) {
    if (venue.trades && venue.trades.length > 0) {
      for (const t of venue.trades) {
        out.push({
          ...t,
          venueId: t.venueId || venue.id,
          provider: t.provider || venue.provider,
          maskedAccountId: t.maskedAccountId || venue.maskedAccountId || '',
          currency: t.currency || venue.currency || 'USD'
        })
      }
    }
  }
  return out.sort((a, b) => {
    const ta = a.closeTime || a.openTime || ''
    const tb = b.closeTime || b.openTime || ''
    return tb.localeCompare(ta)
  })
})

function isTradeClosedState(state?: string): boolean {
  const s = (state || '').toUpperCase()
  return s === 'CLOSED' || s === 'FILLED'
}

const filteredBrokerTrades = computed<BrokerVenueTrade[]>(() => {
  return allBrokerTrades.value.filter(t => {
    if (selectedTradeVenueFilter.value !== 'ALL' && t.venueId !== selectedTradeVenueFilter.value) {
      return false
    }
    if (selectedTradeStateFilter.value === 'OPEN' && (t.state || '').toUpperCase() !== 'OPEN') {
      return false
    }
    if (selectedTradeStateFilter.value === 'CLOSED' && !isTradeClosedState(t.state)) {
      return false
    }
    if (selectedTradeStrategyFilter.value !== 'ALL' && cleanStrategyTag(t.strategyTag) !== selectedTradeStrategyFilter.value) {
      return false
    }
    return true
  })
})

function filterTradesByVenue(venueId: string) {
  showDeskTrades.value = true
  selectedTradeVenueFilter.value = venueId
  selectedTradeStrategyFilter.value = 'ALL'
}

function filterTradesByStrategy(strategyId: string) {
  showDeskTrades.value = true
  const cleaned = cleanStrategyTag(strategyId)
  selectedTradeStrategyFilter.value = selectedTradeStrategyFilter.value === cleaned ? 'ALL' : cleaned
}

const totalOpenPositionsCount = computed(() => {
  return connectedVenues.value.reduce(
    (sum, b) => sum + (b.positionsCount ?? (b.positions ? b.positions.length : 0)),
    0
  )
})

const openBrokerTradesCount = computed(() => {
  return allBrokerTrades.value.filter(t => (t.state || '').toUpperCase() === 'OPEN').length
})

const closedBrokerTradesCount = computed(() => {
  return allBrokerTrades.value.filter(t => isTradeClosedState(t.state)).length
})

const totalTrackedRealizedPnL = computed(() => {
  return allBrokerTrades.value
    .filter(t => isTradeClosedState(t.state))
    .reduce((sum, t) => sum + convertCurrencyAmount(t.realizedPnL || 0, t.currency || 'USD', selectedCurrency.value), 0)
})

const trackedWinRatePct = computed(() => {
  const closed = allBrokerTrades.value.filter(t => isTradeClosedState(t.state))
  if (!closed.length) return 0
  const wins = closed.filter(t => (t.realizedPnL || 0) > 0).length
  return (wins / closed.length) * 100.0
})

interface BrokerStrategySummary {
  strategyId: string
  symbol: string
  provider: string
  venueId: string
  maskedAccountId: string
  currency: string
  tradesCount: number
  openTradesCount: number
  winsCount: number
  realizedPnLNative: number
  realizedPnLDisplay: number
  unrealizedPnLNative: number
  lastTradeAt: string
}

const brokerStrategySummaries = computed<BrokerStrategySummary[]>(() => {
  const map = new Map<string, BrokerStrategySummary>()
  for (const t of allBrokerTrades.value) {
    const strat = cleanStrategyTag(t.strategyTag)
    if (!strat) continue
    const key = `${t.venueId}::${strat}`
    let entry = map.get(key)
    if (!entry) {
      entry = {
        strategyId: strat,
        symbol: t.symbol,
        provider: t.provider || 'OANDA',
        venueId: t.venueId,
        maskedAccountId: t.maskedAccountId || '',
        currency: t.currency || 'USD',
        tradesCount: 0,
        openTradesCount: 0,
        winsCount: 0,
        realizedPnLNative: 0,
        realizedPnLDisplay: 0,
        unrealizedPnLNative: 0,
        lastTradeAt: t.closeTime || t.openTime || ''
      }
      map.set(key, entry)
    }
    const isOpen = (t.state || '').toUpperCase() === 'OPEN'
    if (isOpen) {
      entry.openTradesCount++
      entry.unrealizedPnLNative += t.unrealizedPnL || 0
    } else if (isTradeClosedState(t.state)) {
      entry.tradesCount++
      if ((t.realizedPnL || 0) > 0) entry.winsCount++
      entry.realizedPnLNative += t.realizedPnL || 0
      entry.realizedPnLDisplay += convertCurrencyAmount(t.realizedPnL || 0, t.currency || 'USD', selectedCurrency.value)
    }
    const ts = t.closeTime || t.openTime || ''
    if (ts > entry.lastTradeAt) {
      entry.lastTradeAt = ts
    }
  }
  return Array.from(map.values()).sort((a, b) => {
    if (a.provider !== b.provider) return a.provider.localeCompare(b.provider)
    return b.realizedPnLDisplay - a.realizedPnLDisplay
  })
})

const unifiedStrategyProviders = computed(() => {
  const map = new Map<string, 'IBKR' | 'OANDA'>()
  for (const r of liveRuns.value) {
    if (r.strategyId) {
      map.set(cleanStrategyTag(r.strategyId), resolveRunProvider(r) === 'IBKR' ? 'IBKR' : 'OANDA')
    }
  }
  for (const s of brokerStrategySummaries.value) {
    if (s.strategyId && !map.has(s.strategyId)) {
      map.set(s.strategyId, s.provider.toUpperCase() === 'IBKR' ? 'IBKR' : 'OANDA')
    }
  }
  return map
})

const activeStrategiesCount = computed(() => unifiedStrategyProviders.value.size)

const activeStrategiesBreakdown = computed(() => {
  const entries = Array.from(unifiedStrategyProviders.value.values())
  if (entries.length === 0) return '0 Running'
  const ibkrCount = entries.filter(p => p === 'IBKR').length
  const oandaCount = entries.length - ibkrCount
  return `${ibkrCount} IBKR • ${oandaCount} OANDA`
})

const totalBrokerBalance = computed(() => {
  if (!connectedVenues.value.length) return 0
  return connectedVenues.value.reduce(
    (sum, b) => sum + convertCurrencyAmount(b.balance || 0, b.currency || 'USD', selectedCurrency.value),
    0
  )
})

const totalBrokerEquity = computed(() => {
  if (!connectedVenues.value.length) return 0
  return connectedVenues.value.reduce(
    (sum, b) => sum + convertCurrencyAmount(b.equity ?? b.balance ?? 0, b.currency || 'USD', selectedCurrency.value),
    0
  )
})

const totalUnrealizedPnL = computed(() => {
  if (!connectedVenues.value.length) return 0
  return connectedVenues.value.reduce(
    (sum, b) => sum + convertCurrencyAmount(b.unrealizedPnL || 0, b.currency || 'USD', selectedCurrency.value),
    0
  )
})

const maintMarginTotal = computed(() => {
  if (!connectedVenues.value.length) return 0.0
  return connectedVenues.value.reduce(
    (sum, b) => sum + convertCurrencyAmount(b.maintenanceMargin || 0, b.currency || 'USD', selectedCurrency.value),
    0
  )
})

const initMarginTotal = computed(() => {
  if (!connectedVenues.value.length) return 0.0
  return connectedVenues.value.reduce(
    (sum, b) => sum + convertCurrencyAmount(b.initialMargin || 0, b.currency || 'USD', selectedCurrency.value),
    0
  )
})

const availableFundsTotal = computed(() => {
  if (!connectedVenues.value.length) return 0.0
  return connectedVenues.value.reduce((sum, b) => {
    const avail = (b.availableFunds !== undefined && b.availableFunds !== null && b.availableFunds >= 0)
      ? b.availableFunds
      : Math.max(0, (b.equity ?? b.balance ?? 0) - (b.initialMargin || 0))
    return sum + convertCurrencyAmount(avail, b.currency || 'USD', selectedCurrency.value)
  }, 0)
})

const marginUtilizationPct = computed(() => {
  const eq = totalBrokerEquity.value
  return eq > 0 ? (maintMarginTotal.value / eq) * 100.0 : 0.0
})

const pdtStatus = computed(() => {
  const ibkrVenues = connectedVenues.value.filter(b => (b.provider || '').toUpperCase() === 'IBKR')
  if (!ibkrVenues.length) {
    return { label: connectedVenues.value.length ? 'Unlimited (FX)' : '—', tone: 'text-info', restricted: false }
  }
  const restricted = ibkrVenues.some(b => b.pdtRestricted)
  if (restricted) {
    return { label: 'PDT Restricted', tone: 'text-danger', restricted: true }
  }
  const minDayTrades = ibkrVenues
    .map(b => b.dayTradesRemaining ?? -1)
    .filter(d => d >= 0)
  const allAbove25k = ibkrVenues.every(b => convertCurrencyAmount(b.equity || 0, b.currency || 'USD', 'USD') >= 25000)
  if (allAbove25k || !minDayTrades.length) {
    return { label: 'Unlimited', tone: 'text-success', restricted: false }
  }
  const rem = Math.min(...minDayTrades)
  return {
    label: `${rem} Left`,
    tone: rem <= 1 ? 'text-warning' : 'text-success',
    restricted: false
  }
})

const { connect, disconnect, lastEvent } = useRunWebSocket()

watch(lastEvent, async (event) => {
  if (!event) return
  if (event.type === 'BAR' && event.payload) {
    if (event.payload.bid !== undefined) {
      currentBid.value = event.payload.bid as number
    }
    if (event.payload.ask !== undefined) {
      currentAsk.value = event.payload.ask as number
    }
    if (event.payload.bar) {
      const newBar = event.payload.bar as Bar
      const lastBar = inspectBars.value[inspectBars.value.length - 1]
      if (lastBar && lastBar.time === newBar.time) {
        lastBar.open = newBar.open
        lastBar.high = newBar.high
        lastBar.low = newBar.low
        lastBar.close = newBar.close
      } else {
        inspectBars.value.push(newBar)
      }

      if (tradeChartRef.value) {
        tradeChartRef.value.updateBar(newBar)
      }
    }
  }
  if (event.type === 'FILL' || event.type === 'ORDER_SUBMITTED') {
    await fetchSummary()
  }
})

// Telemetry polling timer
let pollTimer: any = null

// Accounts configuration state
const accounts = ref<any[]>([])
const loadingAccounts = ref(false)
const showAccountsConfig = ref(false)
const savingAccounts = ref(false)
const accountsError = ref<string | null>(null)
const accountsSuccess = ref<string | null>(null)
const editingAccount = ref<any | null>(null)
const discoveringOanda = ref(false)
const discoveredOandaAccounts = ref<{ id: string; tags?: string[] }[]>([])

async function fetchBalances(force = false) {
  try {
    brokerBalances.value = await getBrokerBalances(force)
  } catch (err) {
    console.error('Failed to fetch broker balances:', err)
  }
}

function toggleVenuePositions(venueId: string) {
  expandedVenueId.value = expandedVenueId.value === venueId ? null : venueId
}

async function openVenueConfig(venue: BrokerBalanceSnapshot) {
  showAccountsConfig.value = true
  await fetchAccounts()
  const match = accounts.value.find((a: any) => a.id === venue.id)
  if (match) {
    startEditAccount(match)
  } else {
    startEditAccount({
      id: venue.id,
      provider: venue.provider,
      configured: venue.configured ?? true,
      maskedAccountId: venue.maskedAccountId || '',
      host: venue.host || '127.0.0.1',
      port: venue.port || 4002
    })
  }
}

// Connection testing state
const testingConnection = ref(false)
const testResult = ref<{
  success: boolean
  balance?: number
  equity?: number
  availableFunds?: number
  positionsCount?: number
  dayTradesRemaining?: number
  currency?: string
  message?: string
} | null>(null)

async function testAccountConnection() {
  if (!editingAccount.value) return
  testingConnection.value = true
  testResult.value = null
  accountsError.value = null
  accountsSuccess.value = null
  try {
    const payload = {
      id: editingAccount.value.id,
      provider: editingAccount.value.provider,
      token: editingAccount.value.token ? editingAccount.value.token.trim() || null : null,
      accountId: editingAccount.value.accountId ? editingAccount.value.accountId.trim() || null : null,
      host: editingAccount.value.host,
      port: editingAccount.value.port,
      defaultRestUrl: editingAccount.value.provider === 'OANDA'
        ? (editingAccount.value.defaultRestUrl || 'https://api-fxpractice.oanda.com')
        : null
    }
    const res = await testBrokerAccount(payload)
    if (res && res.success) {
      testResult.value = {
        success: true,
        balance: res.balance,
        equity: res.equity,
        availableFunds: res.availableFunds,
        positionsCount: res.positionsCount,
        dayTradesRemaining: res.dayTradesRemaining,
        currency: res.currency
      }
      await fetchBalances(true)
    } else {
      testResult.value = {
        success: false,
        message: res.error || 'Connection check failed'
      }
    }
  } catch (err: any) {
    testResult.value = {
      success: false,
      message: err.message
    }
  } finally {
    testingConnection.value = false
  }
}

async function handleDiscoverOandaAccounts() {
  if (!editingAccount.value) return
  discoveringOanda.value = true
  accountsError.value = null
  try {
    const list = await discoverOandaAccounts({
      id: editingAccount.value.id || undefined,
      token: editingAccount.value.token?.trim() || undefined,
      restUrl: editingAccount.value.defaultRestUrl || 'https://api-fxpractice.oanda.com'
    })
    const sorted = [...list].sort((a, b) => (b.id || '').localeCompare(a.id || ''))
    discoveredOandaAccounts.value = sorted
    if (sorted.length > 0 && (!editingAccount.value.accountId || editingAccount.value.accountId.startsWith('*'))) {
      editingAccount.value.accountId = sorted[0].id
    }
  } catch (err: any) {
    accountsError.value = 'Failed to discover OANDA accounts: ' + err.message
  } finally {
    discoveringOanda.value = false
  }
}

async function fetchSummary(forceBalances = false) {
  try {
    const data = await getControlSummary()
    summary.value = data
    error.value = null
    if (selectedRunId.value) {
      const exists = liveRuns.value.some((r: any) => r.runId === selectedRunId.value)
      if (!exists) {
        deselectRun()
      } else {
        await updateInspectTelemetry(true, true)
      }
    }
    await fetchBalances(forceBalances)
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function fetchAccounts() {
  loadingAccounts.value = true
  try {
    accounts.value = await getBrokerAccounts()
  } catch (err: any) {
    accountsError.value = err.message
  } finally {
    loadingAccounts.value = false
  }
}

function toggleAccountsConfig() {
  showAccountsConfig.value = !showAccountsConfig.value
  if (showAccountsConfig.value) {
    fetchAccounts()
    editingAccount.value = null
    accountsError.value = null
    accountsSuccess.value = null
  }
}

function startEditAccount(acc?: any) {
  testResult.value = null
  discoveredOandaAccounts.value = []
  if (acc) {
    const cleanRestUrl = (acc.defaultRestUrl || 'https://api-fxpractice.oanda.com').replace(/\/v3\/?$/, '')
    editingAccount.value = {
      id: acc.id,
      provider: acc.provider,
      token: acc.configured ? '********' : '',
      accountId: acc.maskedAccountId && acc.maskedAccountId !== '****' ? acc.maskedAccountId : '',
      host: acc.host || '127.0.0.1',
      port: acc.port || (acc.provider === 'IBKR' ? 4002 : 7497),
      defaultRestUrl: cleanRestUrl,
      isNew: false
    }
  } else {
    editingAccount.value = {
      id: '',
      provider: 'IBKR',
      token: '',
      accountId: '',
      host: '127.0.0.1',
      port: 4002,
      defaultRestUrl: 'https://api-fxpractice.oanda.com',
      isNew: true
    }
  }
}

async function saveAccountChanges() {
  if (!editingAccount.value) return
  if (!editingAccount.value.id.trim()) {
    alert('Account ID is required')
    return
  }
  
  savingAccounts.value = true
  accountsError.value = null
  accountsSuccess.value = null

  try {
    const currentMasked = await getBrokerAccounts()
    const accountsPayload: any[] = []
    let exists = false

    currentMasked.forEach(acc => {
      if (acc.id === editingAccount.value.id) {
        exists = true
        accountsPayload.push({
          id: editingAccount.value.id,
          provider: editingAccount.value.provider,
          token: editingAccount.value.token?.trim() || null,
          accountId: editingAccount.value.accountId?.trim() || null,
          host: editingAccount.value.host,
          port: editingAccount.value.port,
          defaultRestUrl: editingAccount.value.provider === 'OANDA' ? editingAccount.value.defaultRestUrl : null
        })
      } else {
        accountsPayload.push({
          id: acc.id,
          provider: acc.provider,
          token: null,
          accountId: null,
          host: null,
          port: null,
          defaultRestUrl: null
        })
      }
    })

    if (!exists) {
      accountsPayload.push({
        id: editingAccount.value.id,
        provider: editingAccount.value.provider,
        token: editingAccount.value.token?.trim() || null,
        accountId: editingAccount.value.accountId?.trim() || null,
        host: editingAccount.value.host,
        port: editingAccount.value.port,
        defaultRestUrl: editingAccount.value.provider === 'OANDA' ? editingAccount.value.defaultRestUrl : null
      })
    }

    await saveBrokerAccounts(accountsPayload)
    accountsSuccess.value = 'Broker configuration updated successfully!'
    editingAccount.value = null
    await Promise.all([fetchAccounts(), fetchBalances(true)])
  } catch (err: any) {
    accountsError.value = err.message
  } finally {
    savingAccounts.value = false
  }
}

async function deleteAccount(id: string) {
  if (id === 'default') {
    alert('Cannot delete the default account entry')
    return
  }
  if (!confirm(`Are you sure you want to remove account configuration "${id}"?`)) return
  
  savingAccounts.value = true
  accountsError.value = null
  accountsSuccess.value = null
  try {
    const current = await getBrokerAccounts()
    const filtered = current
      .filter(acc => acc.id !== id)
      .map(acc => ({
        id: acc.id,
        provider: acc.provider,
        token: null,
        accountId: null,
        host: null,
        port: null
      }))
    await saveBrokerAccounts(filtered)
    accountsSuccess.value = `Account "${id}" removed.`
    await fetchAccounts()
  } catch (err: any) {
    accountsError.value = err.message
  } finally {
    savingAccounts.value = false
  }
}

// Filter runs to display only live & paper strategies
const liveRuns = computed(() => {
  if (!summary.value || !summary.value.runs) return []
  return [...summary.value.runs]
    .filter((r: any) => r.mode === 'LIVE' || r.mode === 'PAPER')
    .sort((a: any, b: any) => {
      // Sort by mode (LIVE then PAPER)
      const modeA = a.mode || ''
      const modeB = b.mode || ''
      const modeComp = modeA.localeCompare(modeB)
      if (modeComp !== 0) return modeComp

      // Sort by symbol
      const symA = a.symbol || ''
      const symB = b.symbol || ''
      const symComp = symA.localeCompare(symB)
      if (symComp !== 0) return symComp

      // Sort by strategyId
      const stratA = a.strategyId || ''
      const stratB = b.strategyId || ''
      const stratComp = stratA.localeCompare(stratB)
      if (stratComp !== 0) return stratComp

      // Fallback to runId for strict determinism
      const idA = a.runId || ''
      const idB = b.runId || ''
      return idA.localeCompare(idB)
    })
})

const selectedRun = computed(() => {
  if (!selectedRunId.value || !liveRuns.value.length) return null
  return liveRuns.value.find((r: any) => r.runId === selectedRunId.value) || null
})

// Statistics computed from active strategies
const stats = computed(() => {
  const list = liveRuns.value
  const activeLive = list.filter((r: any) => r.mode === 'LIVE' && r.status === 'RUNNING').length
  const activePaper = list.filter((r: any) => r.mode === 'PAPER' && r.status === 'RUNNING').length
  
  let totalAllocated = 0
  let staleCount = 0
  let breachCount = 0

  list.forEach((r: any) => {
    if (r.configSnapshot && r.configSnapshot.capital) {
      totalAllocated += r.configSnapshot.capital
    }
    if (r.isStale) staleCount++
    if (r.dailyDdBreached) breachCount++
  })

  return { activeLive, activePaper, totalAllocated, staleCount, breachCount }
})

// Inspection detailed data
const inspectBars = ref<Bar[]>([])
const inspectTrades = ref<Trade[]>([])
const inspectEquity = ref<number[]>([])
const loadingInspect = ref(false)
const isLoadingOlderBars = ref(false)
const inspectError = ref<string | null>(null)

async function handleLoadMoreBars(oldestTimestamp: string) {
  if (!selectedRunId.value || isLoadingOlderBars.value) return
  isLoadingOlderBars.value = true
  try {
    const olderBars = await getBars(selectedRunId.value, 500, oldestTimestamp)
    if (olderBars && olderBars.length > 0) {
      inspectBars.value = [...olderBars, ...inspectBars.value]
    }
  } catch (e: any) {
    console.error('Failed to load older bars:', e)
  } finally {
    isLoadingOlderBars.value = false
  }
}

const { controlPlaneUrl } = useControlPlaneConfig()

function isSyntheticLaneRun(runId: string | null | undefined): boolean {
  return typeof runId === 'string' && runId.startsWith('lane-')
}

async function selectRun(runId: string) {
  selectedRunId.value = runId
  disconnect()
  if (!isSyntheticLaneRun(runId)) {
    connect(runId)
  } else if (selectedRun.value?.strategyId) {
    filterTradesByStrategy(selectedRun.value.strategyId)
  }
  currentBid.value = null
  currentAsk.value = null
  
  // Push runId to query parameters to sync URL
  router.replace({ query: { ...route.query, runId } })

  await updateInspectTelemetry()
  if (selectedRun.value) {
    currentBid.value = selectedRun.value.lastBid || null
    currentAsk.value = selectedRun.value.lastAsk || null
  }
}

function deselectRun() {
  selectedRunId.value = null
  disconnect()
  currentBid.value = null
  currentAsk.value = null
  
  // Clean runId from query parameters
  router.replace({ query: { ...route.query, runId: undefined } })
}

async function updateInspectTelemetry(background = false, skipBars = false) {
  if (!selectedRunId.value) return
  const runId = selectedRunId.value

  if (isSyntheticLaneRun(runId)) {
    const stratId = cleanStrategyTag(selectedRun.value?.strategyId)
    const matched = allBrokerTrades.value
      .filter(t => cleanStrategyTag(t.strategyTag) === stratId)
      .map(t => ({
        symbol: t.symbol,
        side: t.side === 'BUY' ? 'LONG' : 'SHORT',
        entryTime: t.openTime,
        exitTime: t.closeTime || t.openTime,
        entryPrice: t.entryPrice,
        exitPrice: t.exitPrice ?? t.entryPrice,
        quantity: t.quantity,
        pnl: t.realizedPnL,
        exitReason: t.state
      })) as unknown as Trade[]
    inspectBars.value = []
    inspectTrades.value = matched
    inspectEquity.value = []
    loadingInspect.value = false
    inspectError.value = null
    return
  }

  if (!background) {
    loadingInspect.value = true
    inspectError.value = null
  }

  try {
    const [barsData, tradesData, equityData] = await Promise.all([
      skipBars ? Promise.resolve([] as Bar[]) : getBars(runId).catch(() => []),
      getTrades(runId).catch(() => []),
      getEquityCurve(runId).catch(() => [])
    ])
    if (selectedRunId.value === runId) {
      if (!skipBars) {
        inspectBars.value = barsData
      }
      inspectTrades.value = tradesData
      inspectEquity.value = equityData
      if (selectedRun.value) {
        currentBid.value = selectedRun.value.lastBid || null
        currentAsk.value = selectedRun.value.lastAsk || null
      }
    }
  } catch (e: any) {
    if (!background) {
      inspectError.value = 'Failed to load strategy telemetry details: ' + e.message
    } else {
      console.error('Failed to background-refresh strategy inspection details:', e)
    }
  } finally {
    if (!background) {
      loadingInspect.value = false
    }
  }
}

async function triggerKill(runRecord: any) {
  const confirmed = confirm(`Are you sure you want to trigger the Kill Switch for strategy "${runRecord.strategyId}" (${runRecord.symbol})? This will immediately halt trading execution.`)
  if (!confirmed) return
  
  try {
    await killStrategy(runRecord.strategyId)
    alert(`Kill signal sent successfully to strategy deactivation registry.`)
    fetchSummary()
  } catch (e: any) {
    alert(`Deactivation failed: ${e.message}`)
  }
}

function formatSeconds(seconds?: number): string {
  if (seconds === undefined || seconds === null || seconds <= 0) return '00:00:00'
  const h = Math.floor(seconds / 3600).toString().padStart(2, '0')
  const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0')
  const s = Math.floor(seconds % 60).toString().padStart(2, '0')
  return `${h}:${m}:${s}`
}

function formatPosition(netSide: string, netQuantity?: number): string {
  if (!netSide || netSide === 'FLAT' || !netQuantity) return 'FLAT'
  const qty = netQuantity >= 1000 ? `${(netQuantity / 1000).toFixed(0)}k` : netQuantity.toString()
  return `${netSide} ${qty}`
}

function formatPositionAge(entryTimeStr?: string): string {
  if (!entryTimeStr) return '—'
  try {
    const entry = new Date(entryTimeStr).getTime()
    if (entry < 86400000) return '—'
    const diffMs = Date.now() - entry
    if (diffMs < 0) return 'Just now'
    const diffSec = Math.floor(diffMs / 1000)
    if (diffSec < 60) return `${diffSec}s`
    const diffMin = Math.floor(diffSec / 60)
    if (diffMin < 60) return `${diffMin}m`
    const diffH = Math.floor(diffMin / 60)
    if (diffH < 24) return `${diffH}h ${diffMin % 60}m`
    const diffD = Math.floor(diffH / 24)
    return `${diffD}d ${diffH % 24}h`
  } catch {
    return '—'
  }
}

function formatTime(timeStr?: string): string {
  if (!timeStr) return '—'
  try {
    const d = new Date(timeStr)
    if (isNaN(d.getTime()) || d.getTime() < 86400000) return '—'
    const pad = (n: number) => n.toString().padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return '—'
  }
}

function resolveRunProvider(run: any): 'IBKR' | 'OANDA' | 'SIM' {
  if (!run) return 'OANDA'
  const label = (run.executionLabel || run.configSnapshot?.resolvedExecutionLabel || '').toUpperCase()
  if (label.includes('IBKR')) return 'IBKR'
  if (label.includes('OANDA')) return 'OANDA'
  const acctId = (run.configSnapshot?.brokerAccountId || '').toLowerCase()
  if (acctId.includes('ibkr')) return 'IBKR'
  const matchedAcc = accounts.value.find((a: any) => a.id === run.configSnapshot?.brokerAccountId)
  if (matchedAcc && matchedAcc.provider) {
    return matchedAcc.provider.toUpperCase() === 'IBKR' ? 'IBKR' : 'OANDA'
  }
  const matchedBal = brokerBalances.value.find((b: any) => b.id === run.configSnapshot?.brokerAccountId)
  if (matchedBal && matchedBal.provider) {
    return matchedBal.provider.toUpperCase() === 'IBKR' ? 'IBKR' : 'OANDA'
  }
  if (label.includes('STUB') || label.includes('BACKTEST')) return 'SIM'
  return 'OANDA'
}

function resolveRunCurrency(run: any): string {
  if (!run) return 'USD'
  if (resolveRunProvider(run) === 'IBKR') return 'USD'
  const acctId = run.configSnapshot?.brokerAccountId
  if (acctId) {
    const bal = brokerBalances.value.find(b => b.id === acctId)
    if (bal?.currency) return bal.currency
  }
  return 'USD'
}

function resolveRunAccountBadge(run: any): string {
  if (!run) return ''
  if (run.maskedAccountId && run.maskedAccountId !== '****') {
    return run.maskedAccountId
  }
  const acctId = run.configSnapshot?.brokerAccountId
  if (acctId) {
    const bal = brokerBalances.value.find(b => b.id === acctId)
    if (bal?.maskedAccountId && bal.maskedAccountId !== '****') {
      return `${acctId} (${bal.maskedAccountId})`
    }
    return acctId
  }
  return ''
}

function selectIbkrPortPreset(port: number) {
  if (!editingAccount.value) return
  editingAccount.value.port = port
}

watch(controlPlaneUrl, () => {
  fetchSummary()
  fetchBalances(true)
  if (selectedRunId.value && !isSyntheticLaneRun(selectedRunId.value)) {
    disconnect()
    connect(selectedRunId.value)
  }
})


onMounted(async () => {
  await fetchExchangeRates()
  await fetchSummary()
  const runId = route.query.runId
  if (typeof runId === 'string') {
    selectRun(runId)
  }
  if (route.query.setupBroker === 'true') {
    showAccountsConfig.value = true
    fetchAccounts()
    editingAccount.value = null
    accountsError.value = null
    accountsSuccess.value = null
  }
  pollTimer = setInterval(fetchSummary, 10000)
})

watch(() => route.query.runId, (newRunId) => {
  if (typeof newRunId === 'string') {
    selectRun(newRunId)
  }
})

watch(() => route.query.setupBroker, (newSetupBroker) => {
  if (newSetupBroker === 'true') {
    showAccountsConfig.value = true
    fetchAccounts()
    editingAccount.value = null
    accountsError.value = null
    accountsSuccess.value = null
  }
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  disconnect()
})
</script>

<template>
  <div class="view-container">
    <div class="header">
      <div class="title-section">
        <h2>Trading Desk 📡</h2>
        <p class="subtitle">Prop-shop real-time operations board</p>
      </div>
      <div style="display: flex; gap: 0.5rem; align-items: center;">
        <div class="currency-select-wrapper" style="display: flex; align-items: center; gap: 0.35rem; margin-right: 0.5rem;">
          <span style="font-size: 0.8rem; color: var(--text-secondary);">Display:</span>
          <select v-model="selectedCurrency" style="background: var(--bg-secondary); border: 1px solid var(--border); color: var(--text-primary); border-radius: 4px; padding: 0.35rem 0.5rem; font-size: 0.8rem; outline: none; cursor: pointer;">
            <option value="USD">USD ($)</option>
            <option value="CAD">CAD (C$)</option>
            <option value="EUR">EUR (€)</option>
            <option value="GBP">GBP (£)</option>
            <option value="JPY">JPY (¥)</option>
          </select>
        </div>
        <button class="btn btn-secondary" @click="toggleAccountsConfig">
          🔑 {{ showAccountsConfig ? 'Close Keys Panel' : 'Broker Accounts' }}
        </button>
        <button class="btn btn-secondary" @click="fetchSummary(true)" :disabled="loading">
          Refresh Telemetry
        </button>
      </div>
    </div>

    <!-- Broker Accounts panel -->
    <div v-if="showAccountsConfig" class="accounts-panel mb-6">
      <div class="panel-header">
        <h3>Broker Accounts & Credentials Manager</h3>
        <button class="btn btn-accent btn-sm" @click="startEditAccount()">+ Add Account</button>
      </div>

      <div v-if="accountsError" class="banner error mb-4">{{ accountsError }}</div>
      <div v-if="accountsSuccess" class="banner success mb-4">{{ accountsSuccess }}</div>

      <div v-if="loadingAccounts" class="loading-state py-4">
        <div class="spinner-small"></div>
        <p>Loading accounts configuration...</p>
      </div>

      <div v-else class="accounts-list-container">
        <table class="accounts-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Provider</th>
              <th>Account ID</th>
              <th>Endpoint / Port</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="acc in accounts" :key="acc.id">
              <td><strong>{{ acc.id }}</strong></td>
              <td>
                <span :class="['provider-badge', acc.provider?.toLowerCase()]">{{ acc.provider }}</span>
              </td>
              <td class="font-mono">{{ acc.maskedAccountId }}</td>
              <td class="font-mono text-xs" style="color: var(--text-secondary);">
                <template v-if="acc.provider === 'IBKR'">
                  {{ acc.host || '127.0.0.1' }}:{{ acc.port || 4002 }}
                </template>
                <template v-else>
                  {{ acc.defaultRestUrl?.includes('fxtrade') ? 'Live (fxtrade)' : 'Practice (fxpractice)' }}
                </template>
              </td>
              <td>
                <span :class="['status-badge', acc.configured ? 'configured' : 'missing']">
                  {{ acc.configured ? 'Active Credentials' : 'Credentials Missing' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 0.5rem;">
                  <button class="btn btn-secondary btn-sm" @click="startEditAccount(acc)">Edit / Test</button>
                  <button v-if="acc.id !== 'default'" class="btn btn-danger btn-sm" @click="deleteAccount(acc.id)">Delete</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Editing account section -->
      <div v-if="editingAccount" class="editing-modal-overlay" @click.self="editingAccount = null">
        <div class="editing-modal-card">
          <div class="panel-header">
            <h4>{{ editingAccount.isNew ? 'Add Broker Account' : 'Edit Broker Account: ' + editingAccount.id }}</h4>
            <button class="btn-close" @click="editingAccount = null">×</button>
          </div>
          <div class="editing-form py-4">
            <div class="field mb-3">
              <label>Account Profile ID</label>
              <input v-model="editingAccount.id" type="text" :disabled="!editingAccount.isNew" placeholder="e.g. ibkr-paper or oanda-live" />
            </div>
            <div class="field mb-3">
              <label>Broker Venue Provider</label>
              <select v-model="editingAccount.provider" :disabled="!editingAccount.isNew">
                <option value="IBKR">Interactive Brokers (TWS / IB Gateway)</option>
                <option value="OANDA">OANDA (v20 REST & Streaming)</option>
              </select>
            </div>
            
            <!-- OANDA specific -->
            <div v-if="editingAccount.provider === 'OANDA'">
              <div class="field mb-3">
                <label>OANDA Environment</label>
                <select v-model="editingAccount.defaultRestUrl">
                  <option value="https://api-fxpractice.oanda.com">Practice / Paper (api-fxpractice.oanda.com)</option>
                  <option value="https://api-fxtrade.oanda.com">Live Trading (api-fxtrade.oanda.com)</option>
                </select>
              </div>
              <div class="field mb-3">
                <label>API Key / Token</label>
                <input v-model="editingAccount.token" type="password" placeholder="•••••••••••••••• (Leave blank to keep existing)" />
              </div>
              <div class="field mb-3">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.35rem;">
                  <label style="margin-bottom: 0;">OANDA Account ID</label>
                  <button
                    type="button"
                    class="btn-link-xs"
                    :disabled="discoveringOanda"
                    @click="handleDiscoverOandaAccounts"
                  >
                    {{ discoveringOanda ? 'Discovering…' : '🔍 Discover Accounts from Token' }}
                  </button>
                </div>
                <input v-model="editingAccount.accountId" type="text" placeholder="e.g. 101-002-4729622-012 (Leave **** to keep existing)" />
                <div v-if="discoveredOandaAccounts.length > 0" class="preset-pills mt-2">
                  <button
                    v-for="oa in discoveredOandaAccounts"
                    :key="oa.id"
                    type="button"
                    :class="['preset-pill', { active: editingAccount.accountId === oa.id }]"
                    @click="editingAccount.accountId = oa.id"
                  >
                    {{ oa.id }}
                  </button>
                </div>
              </div>
            </div>

            <!-- IBKR specific -->
            <div v-if="editingAccount.provider === 'IBKR'">
              <div class="field mb-3">
                <label>IBKR Account ID</label>
                <input v-model="editingAccount.accountId" type="text" placeholder="e.g. DUR967322 (Paper) or U1234567 (Live)" />
                <div class="field-hint">
                  Required for multi-account / FA profiles and position reconciliation. Leave as <code>****</code> to keep existing.
                </div>
              </div>
              <div class="field mb-3">
                <label>TWS / IB Gateway Host</label>
                <input v-model="editingAccount.host" type="text" placeholder="127.0.0.1" />
              </div>
              <div class="field mb-3">
                <label>TWS / IB Gateway Socket Port</label>
                <input v-model.number="editingAccount.port" type="number" placeholder="4002" />
                <div class="preset-pills mt-2">
                  <button type="button" :class="['preset-pill', { active: editingAccount.port === 4002 }]" @click="selectIbkrPortPreset(4002)">
                    4002 • Gateway Paper
                  </button>
                  <button type="button" :class="['preset-pill', { active: editingAccount.port === 4001 }]" @click="selectIbkrPortPreset(4001)">
                    4001 • Gateway Live
                  </button>
                  <button type="button" :class="['preset-pill', { active: editingAccount.port === 7497 }]" @click="selectIbkrPortPreset(7497)">
                    7497 • TWS Paper
                  </button>
                  <button type="button" :class="['preset-pill', { active: editingAccount.port === 7496 }]" @click="selectIbkrPortPreset(7496)">
                    7496 • TWS Live
                  </button>
                </div>
                <div class="field-hint">
                  Remote IB Gateway over SSH: <code>ssh -f -N -L {{ editingAccount.port || 4002 }}:127.0.0.1:{{ editingAccount.port || 4002 }} srv108</code>
                </div>
              </div>
            </div>

            <!-- Connection test feedback card -->
            <div v-if="testResult" :class="['banner my-3', testResult.success ? 'success' : 'error']">
              <div v-if="testResult.success">
                <strong>✓ Connection Verified!</strong>
                <div class="test-metrics-grid mt-2">
                  <span>Net Liq / NAV: <strong>{{ formatNativeCurrency(testResult.equity ?? testResult.balance, testResult.currency) }}</strong></span>
                  <span>Cash Balance: <strong>{{ formatNativeCurrency(testResult.balance, testResult.currency) }}</strong></span>
                  <span v-if="testResult.availableFunds !== undefined">Free Margin: <strong>{{ formatNativeCurrency(testResult.availableFunds, testResult.currency) }}</strong></span>
                  <span v-if="testResult.positionsCount !== undefined">Open Positions: <strong>{{ testResult.positionsCount }}</strong></span>
                </div>
              </div>
              <div v-else>
                <strong>Connection Failed:</strong> {{ testResult.message }}
              </div>
            </div>

            <div class="form-actions pt-4" style="display: flex; gap: 0.5rem; justify-content: flex-end;">
              <button class="btn btn-secondary btn-sm" @click="editingAccount = null">Cancel</button>
              <button 
                class="btn btn-secondary btn-sm" 
                :disabled="testingConnection || savingAccounts" 
                @click="testAccountConnection"
              >
                <span v-if="testingConnection" class="spinner-small" style="margin-right: 0.25rem;"></span>
                <span>Test Connection</span>
              </button>
              <button class="btn btn-accent btn-sm" :disabled="savingAccounts || testingConnection" @click="saveAccountChanges">
                <span v-if="savingAccounts" class="spinner-small"></span>
                <span v-else>Save Configuration</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="error" class="banner error mb-4">{{ error }}</div>

    <!-- Executive Operational & Portfolio Telemetry Ribbon (6 Cards, Non-Clipping) -->
    <div class="stats-strip mb-6">
      <div class="stat-card">
        <span class="stat-label" title="Combined Net Liquidation / NAV across all connected OANDA & IBKR accounts, converted to display currency.">Portfolio Net Liq</span>
        <span class="stat-value text-accent">
          {{ totalBrokerEquity > 0 ? formatCurrencyVal(totalBrokerEquity, selectedCurrency) : '—' }}
        </span>
        <span class="stat-sub">
          Cash: {{ totalBrokerBalance > 0 ? formatCurrencyVal(totalBrokerBalance, selectedCurrency) : '—' }}
        </span>
      </div>
      <div class="stat-card">
        <span class="stat-label" title="Real-time unrealized P&L and open positions across connected venues.">Floating P&L</span>
        <span :class="['stat-value', totalUnrealizedPnL > 0 ? 'text-success' : totalUnrealizedPnL < 0 ? 'text-danger' : '']">
          {{ connectedVenues.length > 0 ? ((totalUnrealizedPnL > 0 ? '+' : '') + formatCurrencyVal(totalUnrealizedPnL, selectedCurrency)) : '—' }}
        </span>
        <span class="stat-sub">
          {{ totalOpenPositionsCount }} Open Pos • {{ openBrokerTradesCount }} Active Legs
        </span>
      </div>
      <div class="stat-card">
        <span class="stat-label" title="Cumulative realized P&L across tracked broker trades & executions.">Realized P&L (Trades)</span>
        <span :class="['stat-value', totalTrackedRealizedPnL > 0 ? 'text-success' : totalTrackedRealizedPnL < 0 ? 'text-danger' : '']">
          {{ closedBrokerTradesCount > 0 ? ((totalTrackedRealizedPnL > 0 ? '+' : '') + formatCurrencyVal(totalTrackedRealizedPnL, selectedCurrency)) : '—' }}
        </span>
        <span class="stat-sub">
          {{ closedBrokerTradesCount }} Closed • {{ trackedWinRatePct.toFixed(0) }}% Win Rate
        </span>
      </div>
      <div class="stat-card">
        <span class="stat-label" title="Real-time maintenance margin divided by net liquidation across connected brokers.">Margin Util & Buffer</span>
        <span :class="['stat-value', marginUtilizationPct > 80 ? 'text-danger' : marginUtilizationPct > 60 ? 'text-warning' : 'text-info']">
          {{ connectedVenues.length > 0 ? marginUtilizationPct.toFixed(1) + '%' : '—' }}
        </span>
        <span class="stat-sub">
          Free: {{ availableFundsTotal > 0 ? formatCurrencyValNoDecimals(availableFundsTotal, selectedCurrency) : '—' }}
        </span>
      </div>
      <div class="stat-card">
        <span class="stat-label" title="Active strategies detected across running lanes and broker execution tags.">Active Strategies</span>
        <span class="stat-value text-success">{{ activeStrategiesCount }}</span>
        <span class="stat-sub">{{ activeStrategiesBreakdown }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label" title="Desk heartbeat health, drawdown circuit breakers, and FINRA Pattern Day Trader status.">Desk Health & PDT</span>
        <span :class="['stat-value', stats.staleCount > 0 || stats.breachCount > 0 ? 'text-danger' : 'text-success']">
          {{ stats.staleCount === 0 && stats.breachCount === 0 ? 'Healthy' : `${stats.staleCount} Stale / ${stats.breachCount} DD` }}
        </span>
        <span class="stat-sub">
          PDT: <strong :class="pdtStatus.tone">{{ pdtStatus.label }}</strong> • {{ connectedVenues.length }}/{{ brokerBalances.length }} Venues
        </span>
      </div>
    </div>

    <!-- Connected Broker Venues Strip (IBKR & OANDA) -->
    <div v-if="brokerBalances.length > 0" class="venues-section mb-6">
      <div class="venues-section-header">
        <div style="display: flex; align-items: center; gap: 0.5rem;">
          <h4>Connected Broker Venues</h4>
          <span class="venue-count-pill">{{ connectedVenues.length }} / {{ brokerBalances.length }} Online</span>
        </div>
        <span class="venues-hint">Live account telemetry across Interactive Brokers & OANDA</span>
      </div>
      <div class="venues-grid">
        <div
          v-for="venue in brokerBalances"
          :key="venue.id"
          :class="['venue-card', (venue.provider || '').toLowerCase(), { offline: venue.connected === false }]"
        >
          <div class="venue-card-top">
            <div class="venue-identity">
              <span :class="['provider-badge', (venue.provider || '').toLowerCase()]">{{ venue.provider }}</span>
              <strong class="venue-id">{{ venue.id }}</strong>
              <span v-if="venue.maskedAccountId" class="venue-acct-mask">{{ venue.maskedAccountId }}</span>
              <span v-if="venue.environment" :class="['env-pill', venue.environment.toLowerCase()]">{{ venue.environment }}</span>
            </div>
            <div class="venue-status">
              <span :class="['conn-pill', venue.connected === false ? 'offline' : 'online']">
                <span class="conn-dot"></span>
                {{ venue.connected === false ? 'OFFLINE' : 'ONLINE' }}
              </span>
            </div>
          </div>

          <div class="venue-endpoint-bar">
            <span class="font-mono">{{ venue.endpoint || (venue.provider === 'IBKR' ? '127.0.0.1:4002' : 'OANDA v20') }}</span>
            <span class="venue-curr-tag">{{ venue.currency || 'USD' }}</span>
          </div>

          <div v-if="venue.connected === false" class="venue-offline-box">
            <p class="venue-err-msg">{{ venue.error || 'Unable to reach broker gateway.' }}</p>
            <div style="display: flex; gap: 0.5rem; margin-top: 0.5rem;">
              <button class="btn btn-secondary btn-sm" @click="openVenueConfig(venue)">Configure / Test</button>
              <button class="btn btn-secondary btn-sm" @click="fetchBalances(true)">Retry</button>
            </div>
          </div>

          <div v-else class="venue-metrics-grid">
            <div class="venue-metric">
              <span class="vm-label">Net Liquidation / NAV</span>
              <span class="vm-val text-accent">{{ formatNativeCurrency(venue.equity ?? venue.balance, venue.currency) }}</span>
              <span v-if="venue.currency && venue.currency !== selectedCurrency" class="vm-sub">
                ≈ {{ formatCurrencyVal(venue.equity ?? venue.balance, venue.currency) }} {{ selectedCurrency }}
              </span>
            </div>
            <div class="venue-metric">
              <span class="vm-label">Cash Balance</span>
              <span class="vm-val">{{ formatNativeCurrency(venue.balance, venue.currency) }}</span>
            </div>
            <div class="venue-metric">
              <span class="vm-label">Unrealized P&L</span>
              <span :class="['vm-val', (venue.unrealizedPnL || 0) > 0 ? 'text-success' : (venue.unrealizedPnL || 0) < 0 ? 'text-danger' : '']">
                {{ (venue.unrealizedPnL || 0) > 0 ? '+' : '' }}{{ formatNativeCurrency(venue.unrealizedPnL || 0, venue.currency) }}
              </span>
            </div>
            <div class="venue-metric">
              <span class="vm-label">Free Margin / Avail</span>
              <span class="vm-val text-success">{{ formatNativeCurrency(venue.availableFunds ?? venue.equity, venue.currency) }}</span>
            </div>
            <div class="venue-metric">
              <span class="vm-label">Margin Used (Init / Maint)</span>
              <span class="vm-val">
                {{ formatNativeCurrency(venue.initialMargin || 0, venue.currency) }}
                <span class="vm-sub-inline">({{ (venue.marginUtilizationPct || 0).toFixed(1) }}%)</span>
              </span>
            </div>
            <div class="venue-metric">
              <span class="vm-label">Positions & Trades</span>
              <div style="display: flex; align-items: center; gap: 0.45rem; flex-wrap: wrap;">
                <span class="vm-val">{{ venue.positionsCount ?? (venue.positions ? venue.positions.length : 0) }} open</span>
                <button
                  v-if="venue.positions && venue.positions.length > 0"
                  type="button"
                  class="btn-link-xs"
                  @click="toggleVenuePositions(venue.id)"
                >
                  {{ expandedVenueId === venue.id ? 'Hide Pos' : 'View Pos' }}
                </button>
                <span class="vm-sub-inline">• {{ venue.tradesCount ?? (venue.trades ? venue.trades.length : 0) }} trades</span>
                <button
                  v-if="venue.trades && venue.trades.length > 0"
                  type="button"
                  class="btn-link-xs"
                  @click="filterTradesByVenue(venue.id)"
                >
                  Filter Trades
                </button>
              </div>
            </div>
          </div>

          <!-- Expandable Direct Broker Positions Drawer -->
          <div v-if="expandedVenueId === venue.id && venue.positions && venue.positions.length > 0" class="venue-positions-drawer">
            <table class="venue-pos-table">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>Side</th>
                  <th class="num">Qty</th>
                  <th class="num">Avg Entry</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(vp, vpi) in venue.positions" :key="vpi">
                  <td class="font-mono font-bold">{{ vp.symbol }}</td>
                  <td>
                    <span :class="['side-badge', (vp.side || '').toLowerCase()]">{{ vp.side }}</span>
                  </td>
                  <td class="num font-mono">{{ vp.quantity?.toLocaleString() }}</td>
                  <td class="num font-mono">{{ formatInstrumentPrice(vp.entryPrice, vp.symbol) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Real-time Margin & Risk Meter -->
    <div class="margin-meter-card mb-6">
      <div class="meter-header">
        <div class="meter-title">
          <span class="meter-dot" :class="marginUtilizationPct > 80 ? 'danger' : marginUtilizationPct > 60 ? 'warning' : 'healthy'"></span>
          <h4>Portfolio Margin Utilization & Risk Buffer ({{ selectedCurrency }})</h4>
        </div>
        <div class="meter-stats">
          <span>Maintenance: <strong>{{ formatCurrencyVal(maintMarginTotal, selectedCurrency) }}</strong></span>
          <span>Initial: <strong>{{ formatCurrencyVal(initMarginTotal, selectedCurrency) }}</strong></span>
          <span>Available Free: <strong class="text-success">{{ formatCurrencyVal(availableFundsTotal, selectedCurrency) }}</strong></span>
          <span>Floating P&L: <strong :class="totalUnrealizedPnL > 0 ? 'text-success' : totalUnrealizedPnL < 0 ? 'text-danger' : ''">{{ formatCurrencyVal(totalUnrealizedPnL, selectedCurrency) }}</strong></span>
          <span>Utilization: <strong :class="marginUtilizationPct > 80 ? 'text-danger' : marginUtilizationPct > 60 ? 'text-warning' : 'text-success'">{{ marginUtilizationPct.toFixed(1) }}%</strong></span>
        </div>
      </div>
      <div class="meter-bar-track">
        <div 
          class="meter-bar-fill"
          :style="{ width: Math.min(100, Math.max(connectedVenues.length ? 2 : 0, marginUtilizationPct)) + '%' }"
          :class="marginUtilizationPct > 80 ? 'danger' : marginUtilizationPct > 60 ? 'warning' : 'healthy'"
        ></div>
      </div>
    </div>

    <!-- Broker Trades & Executions (IBKR & OANDA) -->
    <div v-if="brokerBalances.length > 0" class="desk-trades-section mb-6">
      <div class="desk-trades-header">
        <div style="display: flex; align-items: center; gap: 0.65rem; flex-wrap: wrap;">
          <h4>Broker Trades & Executions</h4>
          <span class="venue-count-pill">{{ filteredBrokerTrades.length }} / {{ allBrokerTrades.length }} Trades</span>
          <div class="preset-pills">
            <button
              type="button"
              :class="['preset-pill', { active: selectedTradeVenueFilter === 'ALL' }]"
              @click="selectedTradeVenueFilter = 'ALL'"
            >
              All Venues ({{ allBrokerTrades.length }})
            </button>
            <button
              v-for="v in brokerBalances"
              :key="'tf-' + v.id"
              type="button"
              :class="['preset-pill', { active: selectedTradeVenueFilter === v.id }]"
              @click="selectedTradeVenueFilter = v.id"
            >
              {{ v.id }} {{ v.maskedAccountId || '' }} ({{ v.trades ? v.trades.length : 0 }})
            </button>
            <button
              v-if="selectedTradeStrategyFilter !== 'ALL'"
              type="button"
              class="preset-pill active"
              title="Click to clear strategy filter"
              @click="selectedTradeStrategyFilter = 'ALL'"
            >
              Strategy: {{ selectedTradeStrategyFilter }} ×
            </button>
          </div>
        </div>
        <div style="display: flex; align-items: center; gap: 0.5rem;">
          <div class="preset-pills">
            <button
              type="button"
              :class="['preset-pill', { active: selectedTradeStateFilter === 'ALL' }]"
              @click="selectedTradeStateFilter = 'ALL'"
            >
              All States
            </button>
            <button
              type="button"
              :class="['preset-pill', { active: selectedTradeStateFilter === 'OPEN' }]"
              @click="selectedTradeStateFilter = 'OPEN'"
            >
              Open
            </button>
            <button
              type="button"
              :class="['preset-pill', { active: selectedTradeStateFilter === 'CLOSED' }]"
              @click="selectedTradeStateFilter = 'CLOSED'"
            >
              Closed / Filled
            </button>
          </div>
          <button type="button" class="btn btn-secondary btn-sm" @click="showDeskTrades = !showDeskTrades">
            {{ showDeskTrades ? 'Collapse' : 'Expand' }}
          </button>
        </div>
      </div>

      <div v-if="showDeskTrades" class="desk-trades-body">
        <div v-if="filteredBrokerTrades.length === 0" class="empty-trades-note">
          No broker trades match the selected filter.
        </div>
        <div v-else class="desk-trades-table-wrap">
          <table class="venue-pos-table desk-trades-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Venue</th>
                <th>State</th>
                <th>Instrument</th>
                <th>Side</th>
                <th class="num">Units / Qty</th>
                <th class="num">Entry Price</th>
                <th class="num">Close Price</th>
                <th class="num">SL / TP</th>
                <th class="num">P&L (Native)</th>
                <th>Strategy / Tag</th>
                <th>Opened / Closed</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(tr, tri) in filteredBrokerTrades" :key="tr.venueId + '-' + tr.id + '-' + tri">
                <td class="font-mono text-xs" style="color: var(--text-secondary);">#{{ tr.id }}</td>
                <td>
                  <div style="display: flex; align-items: center; gap: 0.35rem;">
                    <span :class="['provider-badge', (tr.provider || '').toLowerCase()]">{{ tr.provider }}</span>
                    <span class="font-mono text-xs">{{ tr.maskedAccountId || tr.venueId }}</span>
                  </div>
                </td>
                <td>
                  <span :class="['trade-state-pill', (tr.state || '').toLowerCase()]">{{ tr.state }}</span>
                </td>
                <td class="font-mono font-bold">{{ tr.symbol }}</td>
                <td>
                  <span :class="['side-badge', (tr.side || '').toLowerCase()]">{{ tr.side }}</span>
                </td>
                <td class="num font-mono">{{ tr.quantity?.toLocaleString() }}</td>
                <td class="num font-mono">{{ formatInstrumentPrice(tr.entryPrice, tr.symbol) }}</td>
                <td class="num font-mono">
                  {{ tr.exitPrice ? formatInstrumentPrice(tr.exitPrice, tr.symbol) : '—' }}
                </td>
                <td class="num font-mono text-xs" style="color: var(--text-secondary);">
                  <span v-if="tr.stopLoss && tr.stopLoss > 0" class="text-danger">SL {{ formatInstrumentPrice(tr.stopLoss, tr.symbol) }}</span>
                  <span v-if="tr.stopLoss && tr.stopLoss > 0 && tr.takeProfit && tr.takeProfit > 0"> / </span>
                  <span v-if="tr.takeProfit && tr.takeProfit > 0" class="text-success">TP {{ formatInstrumentPrice(tr.takeProfit, tr.symbol) }}</span>
                  <span v-if="(!tr.stopLoss || tr.stopLoss <= 0) && (!tr.takeProfit || tr.takeProfit <= 0)">—</span>
                </td>
                <td class="num font-mono">
                  <template v-if="(tr.state || '').toUpperCase() === 'OPEN'">
                    <span :class="(tr.unrealizedPnL || 0) > 0 ? 'text-success' : (tr.unrealizedPnL || 0) < 0 ? 'text-danger' : ''">
                      {{ (tr.unrealizedPnL || 0) > 0 ? '+' : '' }}{{ formatNativeCurrency(tr.unrealizedPnL || 0, tr.currency) }}
                    </span>
                  </template>
                  <template v-else>
                    <span :class="(tr.realizedPnL || 0) > 0 ? 'text-success' : (tr.realizedPnL || 0) < 0 ? 'text-danger' : ''">
                      {{ (tr.realizedPnL || 0) > 0 ? '+' : '' }}{{ formatNativeCurrency(tr.realizedPnL || 0, tr.currency) }}
                    </span>
                  </template>
                </td>
                <td class="font-mono text-xs" style="max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="tr.strategyTag || ''">
                  <button
                    v-if="tr.strategyTag"
                    type="button"
                    class="strat-tag-pill"
                    @click="filterTradesByStrategy(tr.strategyTag)"
                  >
                    {{ cleanStrategyTag(tr.strategyTag) }}
                  </button>
                  <span v-else>—</span>
                </td>
                <td class="font-mono text-xs" style="color: var(--text-secondary);">
                  {{ formatTime(tr.closeTime || tr.openTime) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Active alert section if problems exist -->
    <div v-if="stats.staleCount > 0 || stats.breachCount > 0" class="banner warning mb-6">
      <strong>Operational Alerts Active:</strong> 
      <span v-if="stats.staleCount > 0">There are {{ stats.staleCount }} running processes showing stale telemetry heartbeats.</span>
      <span v-if="stats.breachCount > 0"> Drawdown threshold deactivations occurred on {{ stats.breachCount }} runs.</span>
    </div>

    <div v-if="loading && !summary" class="loading-state">
      <div class="spinner"></div>
      <p>Loading real-time operational telemetry…</p>
    </div>

    <div v-else-if="liveRuns.length === 0 && brokerStrategySummaries.length === 0" class="empty-state">
      <div class="empty-icon">📡</div>
      <h3>No Live or Paper Strategies Detected</h3>
      <p>Deploy a strategy to IBKR or OANDA Live/Paper mode from the Strategies catalog or connect your Control Plane Server URL in the bottom-right status bar.</p>
    </div>

    <div v-else-if="liveRuns.length === 0 && brokerStrategySummaries.length > 0" class="venues-section mb-6">
      <div class="venues-section-header">
        <div style="display: flex; align-items: center; gap: 0.5rem;">
          <h4>Strategy Attribution Across Connected Venues</h4>
          <span class="venue-count-pill">{{ brokerStrategySummaries.length }} Active Strategies</span>
        </div>
        <span class="venues-hint">Aggregated performance by strategy tag across IBKR & OANDA executions</span>
      </div>
      <div class="live-grid">
        <div
          v-for="bs in brokerStrategySummaries"
          :key="bs.venueId + '-' + bs.strategyId"
          :class="['strategy-card', { active: selectedTradeStrategyFilter === bs.strategyId }]"
        >
          <div class="card-header">
            <div>
              <h4 class="strat-title">{{ bs.strategyId }}</h4>
              <div style="display: flex; gap: 0.35rem; align-items: center; margin-top: 0.3rem; flex-wrap: wrap;">
                <span class="symbol-badge">{{ bs.symbol }}</span>
                <span :class="['provider-badge', bs.provider.toLowerCase()]">{{ bs.provider }}</span>
                <span class="acct-mini-badge font-mono">{{ bs.maskedAccountId || bs.venueId }}</span>
              </div>
            </div>
            <span class="mode-badge paper">PAPER</span>
          </div>

          <div class="card-body">
            <div class="metric-row">
              <span>Position State</span>
              <span :class="['status-val', bs.openTradesCount > 0 ? 'running' : 'flat']">
                {{ bs.openTradesCount > 0 ? `${bs.openTradesCount} OPEN` : 'FLAT' }}
              </span>
            </div>
            <div class="metric-row">
              <span>Closed Trades</span>
              <span class="metric-val font-mono">
                {{ bs.tradesCount }} ({{ bs.tradesCount > 0 ? Math.round((bs.winsCount / bs.tradesCount) * 100) : 0 }}% Win)
              </span>
            </div>
            <div class="metric-row">
              <span>Realized P&L (Native)</span>
              <span :class="['metric-val font-mono', bs.realizedPnLNative > 0 ? 'text-success' : bs.realizedPnLNative < 0 ? 'text-danger' : '']">
                {{ bs.realizedPnLNative > 0 ? '+' : '' }}{{ formatNativeCurrency(bs.realizedPnLNative, bs.currency) }}
              </span>
            </div>
            <div class="metric-row">
              <span>Realized P&L ({{ selectedCurrency }})</span>
              <span :class="['metric-val font-mono', bs.realizedPnLDisplay > 0 ? 'text-success' : bs.realizedPnLDisplay < 0 ? 'text-danger' : '']">
                {{ bs.realizedPnLDisplay > 0 ? '+' : '' }}{{ formatCurrencyVal(bs.realizedPnLDisplay, selectedCurrency) }}
              </span>
            </div>
            <div class="metric-row">
              <span>Last Execution</span>
              <span class="metric-val font-mono">{{ formatTime(bs.lastTradeAt) }}</span>
            </div>
          </div>

          <div class="card-footer">
            <button class="btn btn-secondary btn-sm flex-grow" @click="filterTradesByStrategy(bs.strategyId)">
              {{ selectedTradeStrategyFilter === bs.strategyId ? 'Clear Trade Filter' : 'Filter Strategy Trades' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="live-grid mb-6">
      <div 
        v-for="run in liveRuns" 
        :key="run.runId" 
        :class="['strategy-card', { active: selectedRunId === run.runId, stale: run.isStale }]"
      >
        <div class="card-header">
          <div>
            <h4 class="strat-title">{{ run.strategyId }}</h4>
            <div style="display: flex; gap: 0.35rem; align-items: center; margin-top: 0.3rem; flex-wrap: wrap;">
              <span class="symbol-badge">{{ run.symbol }}</span>
              <span :class="['provider-badge', resolveRunProvider(run).toLowerCase()]">
                {{ resolveRunProvider(run) }}
              </span>
              <span v-if="resolveRunAccountBadge(run)" class="acct-mini-badge font-mono">
                {{ resolveRunAccountBadge(run) }}
              </span>
            </div>
          </div>
          <span :class="['mode-badge', run.mode.toLowerCase()]">{{ run.mode }}</span>
        </div>

        <div class="card-body">
          <div class="metric-row">
            <span>Status</span>
            <span :class="['status-val', run.status.toLowerCase(), { stale: run.isStale }]">
              {{ run.isStale ? 'STALE' : run.status }}
            </span>
          </div>
          <div v-if="run.status === 'COOLDOWN'" class="metric-row">
            <span>Cooldown Timer</span>
            <span class="status-val cooldown">{{ formatSeconds(run.cooldownSecondsRemaining) }}</span>
          </div>
          <div v-if="run.lastBid || run.lastAsk" class="metric-row">
            <span>Live Quote</span>
            <span class="metric-val font-mono">
              {{ formatInstrumentPrice(run.lastBid, run.symbol) }} / {{ formatInstrumentPrice(run.lastAsk, run.symbol) }}
            </span>
          </div>
          <div class="metric-row">
            <span>Position</span>
            <span :class="['status-val', run.netSide ? run.netSide.toLowerCase() : 'flat']">
              {{ formatPosition(run.netSide, run.netQuantity) }}
            </span>
          </div>
          <div class="metric-row">
            <span>Realized P&L</span>
            <span :class="['metric-val', (run.realizedPnL ?? 0) > 0 ? 'text-success' : (run.realizedPnL ?? 0) < 0 ? 'text-danger' : '']">
              {{ formatCurrencyVal(run.realizedPnL ?? 0, resolveRunCurrency(run)) }}
            </span>
          </div>
          <div class="metric-row">
            <span>Floating P&L</span>
            <span :class="['metric-val', (run.openPnL ?? 0) > 0 ? 'text-success' : (run.openPnL ?? 0) < 0 ? 'text-danger' : '']">
              {{ formatCurrencyVal(run.openPnL ?? 0, resolveRunCurrency(run)) }}
            </span>
          </div>
          <div class="metric-row">
            <span>Total P&L</span>
            <span :class="['metric-val', (run.totalPnL ?? 0) > 0 ? 'text-success' : (run.totalPnL ?? 0) < 0 ? 'text-danger' : '']">
              {{ formatCurrencyVal(run.totalPnL ?? 0, resolveRunCurrency(run)) }}
            </span>
          </div>
        </div>

        <div class="card-footer">
          <button class="btn btn-secondary btn-sm flex-grow" @click="selectRun(run.runId)">
            Inspect
          </button>
          <button class="btn btn-danger btn-sm" @click="triggerKill(run)">
            Kill
          </button>
        </div>
      </div>
    </div>

    <!-- Active inspect drill-down workspace -->
    <div v-if="selectedRun" class="inspect-workspace section">
      <div class="workspace-header">
        <div>
          <div style="display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.25rem;">
            <h3>Monitoring: {{ selectedRun.strategyId }}</h3>
            <span :class="['provider-badge', resolveRunProvider(selectedRun).toLowerCase()]">
              {{ resolveRunProvider(selectedRun) }}
            </span>
            <span v-if="resolveRunAccountBadge(selectedRun)" class="acct-mini-badge font-mono">
              {{ resolveRunAccountBadge(selectedRun) }}
            </span>
          </div>
          <p class="run-meta">
            ID: {{ selectedRun.runId }} | Symbol: {{ selectedRun.symbol }} | Mode: {{ selectedRun.mode }}
            <template v-if="selectedRun.configSnapshot?.strategyTimeframe">
              | Strategy TF: {{ selectedRun.configSnapshot.strategyTimeframe }}
              | Candle TF: {{ selectedRun.configSnapshot.strategyTimeframe }}
              <template v-if="selectedRun.configSnapshot.dataTimeframe && selectedRun.configSnapshot.dataTimeframe !== selectedRun.configSnapshot.strategyTimeframe">
                (from {{ selectedRun.configSnapshot.dataTimeframe }} data)
              </template>
            </template>
          </p>
        </div>
        <button class="btn-close" @click="deselectRun">×</button>
      </div>

      <div class="tab-bar">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">
          Overview
        </button>
        <button :class="['tab', { active: activeTab === 'positions' }]" @click="activeTab = 'positions'">
          Open Positions
          <span class="tab-count">{{ selectedRun.positions ? selectedRun.positions.length : 0 }}</span>
        </button>
        <button :class="['tab', { active: activeTab === 'pending' }]" @click="activeTab = 'pending'">
          Pending Orders
          <span class="tab-count">{{ selectedRun.pendingOrders ? selectedRun.pendingOrders.length : 0 }}</span>
        </button>
        <button :class="['tab', { active: activeTab === 'chart' }]" @click="activeTab = 'chart'">
          Price Chart
        </button>
        <button :class="['tab', { active: activeTab === 'trades' }]" @click="activeTab = 'trades'">
          Trades History
          <span class="tab-count">{{ inspectTrades.length }}</span>
        </button>
        <button :class="['tab', { active: activeTab === 'sentiment' }]" @click="activeTab = 'sentiment'">
          Market Sentiment
        </button>
      </div>

      <div v-if="loadingInspect" class="loading-state py-6">
        <div class="spinner"></div>
        <p>Loading real-time inspection telemetry…</p>
      </div>

      <div v-else-if="inspectError" class="banner error my-4">{{ inspectError }}</div>

      <div v-else class="workspace-content">
        <!-- Overview tab -->
        <div v-if="activeTab === 'overview'" class="tab-panel">
          <div class="kpis-wrapper mb-6">
            <div class="kpi-box">
              <span class="kpi-lbl">Current Daily Drawdown</span>
              <span :class="['kpi-val', selectedRun.dailyDdBreached ? 'text-danger' : '']">
                {{ selectedRun.dailyDrawdownPct ? selectedRun.dailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}
              </span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Max Daily Drawdown</span>
              <span class="kpi-val">{{ selectedRun.maxDailyDrawdownPct ? selectedRun.maxDailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}</span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Allocated Capital</span>
              <span class="kpi-val">{{ formatCurrencyValNoDecimals(selectedRun.configSnapshot?.capital) }}</span>
            </div>
            <div class="kpi-box">
              <span class="kpi-lbl">Event Sequence</span>
              <span class="kpi-val">#{{ selectedRun.eventCount }}</span>
            </div>
          </div>

          <div class="equity-curve-section">
            <h4>Live Performance Curve</h4>
            <EquityChart v-if="inspectEquity.length" :data="inspectEquity" :height="260" :show-time-scale="true" />
            <p v-else class="no-data">Performance data curve not available.</p>
          </div>
        </div>

        <!-- Open Positions tab -->
        <div v-if="activeTab === 'positions'" class="tab-panel">
          <div v-if="!selectedRun.positions || selectedRun.positions.length === 0" class="empty-panel-state">
            <svg xmlns="http://www.w3.org/2000/svg" class="w-12 h-12 text-gray-500 mb-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path><polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline><line x1="12" y1="22.08" x2="12" y2="12"></line></svg>
            <p class="text-lg font-medium text-gray-300">No Active Positions</p>
            <p class="text-sm text-gray-500 mt-2">This strategy currently has no floating positions in the market.</p>
          </div>
          <div v-else class="glass-table-container">
            <table class="live-positions-table">
              <thead>
                <tr>
                  <th class="text-left pl-6">Instrument</th>
                  <th class="text-left">Side</th>
                  <th class="num">Quantity</th>
                  <th class="num">Entry Price</th>
                  <th class="num">Stop Loss</th>
                  <th class="num">Take Profit</th>
                  <th class="text-right pr-6">Duration</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(p, i) in selectedRun.positions" :key="i" class="position-row">
                  <td class="pl-6">
                    <div class="symbol-badge">
                      <span class="font-bold text-gray-100 tracking-wider">{{ p.symbol }}</span>
                    </div>
                  </td>
                  <td>
                    <span :class="['modern-badge', p.side.toLowerCase()]">
                      <span class="dot"></span>
                      {{ p.side }}
                    </span>
                  </td>
                  <td class="num font-mono text-gray-200">{{ p.quantity?.toLocaleString() }}</td>
                  <td class="num font-mono text-yellow-400 font-medium">{{ formatInstrumentPrice(p.entryPrice, p.symbol || selectedRun.symbol) }}</td>
                  <td class="num font-mono text-red-400">{{ formatInstrumentPrice(p.stopLoss, p.symbol || selectedRun.symbol) }}</td>
                  <td class="num font-mono text-green-400">{{ formatInstrumentPrice(p.takeProfit, p.symbol || selectedRun.symbol) }}</td>
                  <td class="text-right pr-6 text-gray-400 font-mono text-sm">{{ formatPositionAge(p.entryTime) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Price Chart tab -->
        <div v-if="activeTab === 'chart'" class="tab-panel">
          <TradeChart
            ref="tradeChartRef"
            :bars="inspectBars"
            :trades="inspectTrades"
            :positions="selectedRun?.positions"
            :pending-orders="selectedRun?.pendingOrders"
            :indicators="selectedRun?.indicators"
            :height="400"
            :bid="currentBid"
            :ask="currentAsk"
            :symbol="selectedRun?.symbol"
            :timeframe="selectedRun?.configSnapshot?.strategyTimeframe"
            @loadMoreBars="handleLoadMoreBars"
          />
        </div>

        <!-- Pending Orders tab -->
        <div v-if="activeTab === 'pending'" class="tab-panel">
          <div v-if="!selectedRun.pendingOrders || selectedRun.pendingOrders.length === 0" class="empty-panel-state">
            <svg xmlns="http://www.w3.org/2000/svg" class="w-12 h-12 text-gray-500 mb-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
            <p class="text-lg font-medium text-gray-300">No Pending Orders</p>
            <p class="text-sm text-gray-500 mt-2">This strategy currently has no pending LIMIT or STOP orders.</p>
          </div>
          <div v-else class="glass-table-container">
            <table class="live-positions-table">
              <thead>
                <tr>
                  <th class="text-left pl-6">ID</th>
                  <th class="text-left">Type</th>
                  <th class="text-left">Side</th>
                  <th class="num">Quantity</th>
                  <th class="num">Target Price</th>
                  <th class="num">Stop Loss</th>
                  <th class="num">Take Profit</th>
                  <th class="text-right pr-6">Submitted At</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(o, i) in selectedRun.pendingOrders" :key="i" class="position-row">
                  <td class="pl-6 font-mono text-xs text-gray-400">{{ o.orderId || o.id }}</td>
                  <td>
                    <span class="modern-badge secondary">
                      {{ o.type || 'LIMIT' }}
                    </span>
                  </td>
                  <td>
                    <span :class="['modern-badge', o.side ? o.side.toLowerCase() : '']">
                      <span class="dot"></span>
                      {{ o.side }}
                    </span>
                  </td>
                  <td class="num font-mono text-gray-200">{{ o.quantity?.toLocaleString() || o.units?.toLocaleString() }}</td>
                  <td class="num font-mono text-yellow-400 font-medium">{{ formatInstrumentPrice(o.price ?? o.limitPrice, selectedRun.symbol) }}</td>
                  <td class="num font-mono text-red-400">{{ formatInstrumentPrice(o.stopLoss, selectedRun.symbol) }}</td>
                  <td class="num font-mono text-green-400">{{ formatInstrumentPrice(o.takeProfit, selectedRun.symbol) }}</td>
                  <td class="text-right pr-6 text-gray-400 font-mono text-sm">{{ formatTime(o.timestamp) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Trades History tab -->
        <div v-if="activeTab === 'trades'" class="tab-panel">
          <TradeTable :trades="inspectTrades" :currency="selectedCurrency" :rates="currencyFactors" />
        </div>

        <!-- Sentiment / Order Book tab -->
        <div v-if="activeTab === 'sentiment'" class="tab-panel">
          <div v-if="resolveRunProvider(selectedRun) === 'IBKR'" class="banner mb-4" style="background: rgba(59, 130, 246, 0.1); border: 1px solid rgba(59, 130, 246, 0.25); color: #93c5fd;">
            <strong>IBKR Venue Note:</strong> Retail positioning order book data is sourced from OANDA FX sentiment when available for <code>{{ selectedRun.symbol }}</code>.
          </div>
          <OrderBook :instrument="selectedRun.symbol" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view-container {
  max-width: 1280px;
  margin: 0 auto;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.title-section h2 {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.subtitle {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.stats-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(185px, 1fr));
  gap: 0.75rem;
}

.stat-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0.85rem 1rem;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-label {
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-secondary);
  text-transform: uppercase;
  margin-bottom: 0.3rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-value {
  font-size: clamp(1.1rem, 1.35vw, 1.4rem);
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.stat-sub {
  margin-top: 0.3rem;
  font-size: 0.7rem;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.strat-tag-pill {
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.3);
  color: #fbbf24;
  border-radius: 4px;
  padding: 0.12rem 0.45rem;
  font-family: monospace;
  font-size: 0.72rem;
  cursor: pointer;
  transition: all 0.15s;
}

.strat-tag-pill:hover {
  background: rgba(245, 158, 11, 0.25);
  border-color: var(--accent);
}

.live-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.25rem;
}

.strategy-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.15rem;
  display: flex;
  flex-direction: column;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.strategy-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.strategy-card.stale {
  border-color: var(--danger);
  opacity: 0.85;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.strat-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
}

.symbol-badge {
  font-size: 0.7rem;
  background: var(--bg-card);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  color: var(--text-secondary);
}

.mode-badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
}

.mode-badge.live {
  background: rgba(34, 197, 94, 0.1);
  color: var(--success);
  border: 1px solid rgba(34, 197, 94, 0.2);
}

.mode-badge.paper {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.2);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  margin-bottom: 1.25rem;
  font-size: 0.75rem;
}

.metric-row {
  display: flex;
  justify-content: space-between;
  color: var(--text-secondary);
}

.metric-val {
  font-weight: 500;
  color: var(--text-primary);
}

.status-val {
  font-weight: 700;
}

.status-val.running { color: var(--success); }
.status-val.paused { color: var(--warning); }
.status-val.stale { color: var(--danger); }
.status-val.suspended_daily { color: #f97316; }
.status-val.suspended_weekly { color: #ef4444; }
.status-val.cooldown { color: #3b82f6; }
.status-val.long { color: var(--success); }
.status-val.short { color: var(--danger); }
.status-val.flat { color: var(--text-secondary); }

.card-footer {
  margin-top: auto;
  display: flex;
  gap: 0.5rem;
}

.btn {
  padding: 0.45rem 0.9rem;
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: background 0.15s;
}

.btn-secondary {
  background: #222;
  color: var(--text-primary);
}

.btn-secondary:hover { background: #333; }

.btn-danger {
  background: var(--danger);
  color: #fff;
}

.btn-danger:hover { background: #dc2626; }

.btn-sm {
  padding: 0.35rem 0.6rem;
  font-size: 0.75rem;
}

.flex-grow {
  flex-grow: 1;
}

.inspect-workspace {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1.5rem;
}

.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.workspace-header h3 {
  font-size: 1.1rem;
  font-weight: 600;
}

.run-meta {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: 1.5rem;
  cursor: pointer;
}

.tab-bar {
  display: flex;
  gap: 0.5rem;
  border-bottom: 1px solid var(--border);
  margin-bottom: 1.5rem;
}

.tab {
  background: none;
  border: none;
  padding: 0.6rem 1rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.tab:hover {
  color: var(--text-primary);
}

.tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.tab-count {
  font-size: 0.7rem;
  background: var(--bg-card);
  padding: 0.05rem 0.3rem;
  border-radius: 4px;
  margin-left: 0.25rem;
  color: var(--text-secondary);
}

.kpis-wrapper {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
}

.kpi-box {
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: 0.85rem;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
}

.kpi-lbl {
  font-size: 0.7rem;
  color: var(--text-secondary);
  margin-bottom: 0.25rem;
}

.kpi-val {
  font-size: 1.25rem;
  font-weight: 700;
}

.live-positions-table {
  width: 100%;
  border-collapse: collapse;
}

.live-positions-table th {
  text-align: left;
  background: var(--bg-card);
  padding: 0.6rem 0.85rem;
  color: var(--text-secondary);
  font-size: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.live-positions-table td {
  padding: 0.75rem 0.85rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.8rem;
}

.live-positions-table th.num {
  text-align: right;
}

.live-positions-table td.num {
  font-family: monospace;
  text-align: right;
}

.side-badge {
  padding: 0.1rem 0.35rem;
  border-radius: 3px;
  font-size: 0.7rem;
  font-weight: 700;
}

.side-badge.buy {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.side-badge.sell {
  background: rgba(239, 68, 68, 0.15);
  color: var(--danger);
}

.empty-panel-state {
  padding: 3rem;
  text-align: center;
  color: var(--text-secondary);
}

.no-data {
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.loading-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
}

.spinner {
  width: 28px;
  height: 28px;
  border: 3px solid rgba(255, 255, 255, 0.1);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 1rem;
}

.empty-icon {
  font-size: 2.5rem;
  margin-bottom: 1rem;
}

.mb-4 { margin-bottom: 1rem; }
.mb-6 { margin-bottom: 1.5rem; }
.py-6 { padding-top: 1.5rem; padding-bottom: 1.5rem; }

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  font-size: 0.8rem;
}

.banner.error {
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.2);
  color: var(--danger);
}

.banner.warning {
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid rgba(245, 158, 11, 0.2);
  color: var(--warning);
}

.accounts-panel {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1.5rem;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.75rem;
}

.panel-header h3 {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.accounts-table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 1rem;
}

.accounts-table th {
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  padding: 0.6rem 0.85rem;
  color: var(--text-secondary);
  font-size: 0.75rem;
  border-bottom: 1px solid var(--border);
  text-transform: uppercase;
}

.accounts-table td {
  padding: 0.75rem 0.85rem;
  border-bottom: 1px solid var(--border);
  font-size: 0.85rem;
}

.provider-badge {
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.03em;
  border: 1px solid rgba(99, 102, 241, 0.25);
}

.provider-badge.ibkr {
  background: rgba(14, 165, 233, 0.15);
  color: #38bdf8;
  border-color: rgba(14, 165, 233, 0.3);
}

.provider-badge.oanda {
  background: rgba(16, 185, 129, 0.14);
  color: #34d399;
  border-color: rgba(16, 185, 129, 0.28);
}

.acct-mini-badge {
  font-size: 0.65rem;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
  padding: 0.12rem 0.4rem;
  border-radius: 4px;
}

.field-hint {
  font-size: 0.72rem;
  color: var(--text-secondary);
  margin-top: 0.35rem;
  line-height: 1.35;
}

.field-hint code {
  background: rgba(255, 255, 255, 0.06);
  padding: 0.1rem 0.3rem;
  border-radius: 3px;
  font-size: 0.68rem;
}

.preset-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.preset-pill {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.25rem 0.55rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.15s;
}

.preset-pill:hover {
  border-color: var(--accent);
  color: var(--text-primary);
}

.preset-pill.active {
  background: rgba(59, 130, 246, 0.18);
  border-color: #3b82f6;
  color: #60a5fa;
}

.btn-link-xs {
  background: none;
  border: none;
  color: #60a5fa;
  font-size: 0.72rem;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.btn-link-xs:hover {
  text-decoration: underline;
}

.test-metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.35rem 0.75rem;
  font-size: 0.75rem;
}

.mt-2 { margin-top: 0.5rem; }

/* Connected Broker Venues Strip */
.venues-section {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem 1.25rem;
}

.venues-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.9rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.venues-section-header h4 {
  font-size: 0.92rem;
  font-weight: 600;
  margin: 0;
  color: var(--text-primary);
}

.venue-count-pill {
  font-size: 0.68rem;
  font-weight: 700;
  background: rgba(16, 185, 129, 0.12);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.25);
  padding: 0.1rem 0.45rem;
  border-radius: 12px;
}

.venues-hint {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.venues-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 1rem;
}

.venue-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 9px;
  padding: 0.95rem 1.1rem;
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.venue-card.ibkr {
  border-left: 3px solid #38bdf8;
}

.venue-card.oanda {
  border-left: 3px solid #34d399;
}

.venue-card.offline {
  border-left-color: var(--danger);
  opacity: 0.92;
}

.venue-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.5rem;
}

.venue-identity {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
}

.venue-id {
  font-size: 0.88rem;
  color: var(--text-primary);
}

.venue-acct-mask {
  font-family: monospace;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.env-pill {
  font-size: 0.62rem;
  font-weight: 700;
  padding: 0.1rem 0.38rem;
  border-radius: 4px;
  text-transform: uppercase;
}

.env-pill.paper {
  background: rgba(59, 130, 246, 0.12);
  color: #60a5fa;
}

.env-pill.live {
  background: rgba(34, 197, 94, 0.12);
  color: #4ade80;
}

.conn-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 0.15rem 0.5rem;
  border-radius: 12px;
}

.conn-pill.online {
  background: rgba(16, 185, 129, 0.12);
  color: #34d399;
}

.conn-pill.offline {
  background: rgba(239, 68, 68, 0.15);
  color: #f87171;
}

.conn-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.venue-endpoint-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.72rem;
  color: var(--text-secondary);
  padding-bottom: 0.45rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.venue-curr-tag {
  font-weight: 700;
  font-size: 0.68rem;
  background: rgba(255, 255, 255, 0.05);
  padding: 0.08rem 0.35rem;
  border-radius: 3px;
}

.venue-metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.65rem 0.75rem;
}

.venue-metric {
  display: flex;
  flex-direction: column;
}

.vm-label {
  font-size: 0.66rem;
  color: var(--text-secondary);
  text-transform: uppercase;
  margin-bottom: 0.15rem;
}

.vm-val {
  font-size: 0.88rem;
  font-weight: 700;
  font-family: monospace;
  color: var(--text-primary);
}

.vm-sub {
  font-size: 0.66rem;
  color: var(--text-secondary);
  font-family: monospace;
}

.vm-sub-inline {
  font-size: 0.7rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.venue-offline-box {
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 6px;
  padding: 0.65rem 0.8rem;
}

.venue-err-msg {
  font-size: 0.75rem;
  color: #fca5a5;
  margin: 0;
}

.venue-positions-drawer {
  border-top: 1px solid var(--border);
  padding-top: 0.6rem;
  margin-top: 0.2rem;
}

.venue-pos-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.75rem;
}

.venue-pos-table th {
  text-align: left;
  color: var(--text-secondary);
  font-size: 0.68rem;
  text-transform: uppercase;
  padding: 0.25rem 0.4rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.venue-pos-table td {
  padding: 0.35rem 0.4rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
}

.venue-pos-table th.num,
.venue-pos-table td.num {
  text-align: right;
}

.status-badge {
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
}

.status-badge.configured {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.status-badge.missing {
  background: rgba(239, 68, 68, 0.15);
  color: var(--danger);
}

/* Editing overlays */
.editing-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  backdrop-filter: blur(5px);
}

.editing-modal-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  width: 90%;
  max-width: 450px;
  padding: 1.5rem;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
}

.field label {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  margin-bottom: 0.35rem;
  display: block;
}

.field input[type="text"],
.field input[type="password"],
.field input[type="number"],
.field select {
  width: 100%;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.55rem 0.75rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  outline: none;
  transition: border-color 0.15s;
}

.field input:focus, .field select:focus {
  border-color: var(--accent);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  border-top: 1px solid var(--border);
  margin-top: 1rem;
}

.spinner-small {
  display: inline-block;
  width: 0.85rem;
  height: 0.85rem;
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

.banner.success {
  background: rgba(34, 197, 94, 0.15);
  border: 1px solid rgba(34, 197, 94, 0.2);
  color: var(--success);
}

.mb-3 { margin-bottom: 0.75rem; }
.pt-4 { padding-top: 1rem; }
.py-4 { padding-top: 1rem; padding-bottom: 1rem; }

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Glass UI Elements */
.glass-table-container {
  background: rgba(17, 19, 23, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  backdrop-filter: blur(10px);
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
}

.position-row {
  transition: background-color 0.2s ease;
}

.position-row:hover {
  background-color: rgba(255, 255, 255, 0.03);
}

.symbol-badge {
  display: inline-flex;
  align-items: center;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15) 0%, rgba(99, 102, 241, 0.15) 100%);
  padding: 0.35rem 0.65rem;
  border-radius: 6px;
  border: 1px solid rgba(59, 130, 246, 0.2);
}

.modern-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.6rem;
  border-radius: 20px;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.modern-badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.modern-badge.buy {
  background: rgba(16, 185, 129, 0.1);
  color: #10B981;
  border: 1px solid rgba(16, 185, 129, 0.2);
}

.modern-badge.buy .dot {
  background-color: #10B981;
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.8);
}

.modern-badge.sell {
  background: rgba(239, 68, 68, 0.1);
  color: #EF4444;
  border: 1px solid rgba(239, 68, 68, 0.2);
}

.modern-badge.sell .dot {
  background-color: #EF4444;
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.8);
}

.live-positions-table th.num, .live-positions-table td.num {
  text-align: right;
}

/* Margin Meter Card */
.margin-meter-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem 1.25rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.meter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.meter-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.meter-title h4 {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.meter-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.meter-dot.healthy { background: var(--success); box-shadow: 0 0 6px var(--success); }
.meter-dot.warning { background: var(--warning); box-shadow: 0 0 6px var(--warning); }
.meter-dot.danger { background: var(--danger); box-shadow: 0 0 6px var(--danger); }

.meter-stats {
  display: flex;
  gap: 1.25rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  flex-wrap: wrap;
}

.meter-stats strong {
  font-family: monospace;
}

.meter-bar-track {
  width: 100%;
  height: 8px;
  background: #111;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.meter-bar-fill {
  height: 100%;
  transition: width 0.3s ease;
}

.meter-bar-fill.healthy { background: linear-gradient(90deg, #10b981, #34d399); }
.meter-bar-fill.warning { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
.meter-bar-fill.danger { background: linear-gradient(90deg, #ef4444, #f87171); }

/* Desk Trades & Executions Panel */
.desk-trades-section {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem 1.25rem;
}

.desk-trades-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 0.85rem;
}

.desk-trades-header h4 {
  font-size: 0.92rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.desk-trades-table-wrap {
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.2);
}

.desk-trades-table th {
  position: sticky;
  top: 0;
  background: #141821;
  z-index: 2;
  padding: 0.55rem 0.7rem;
}

.desk-trades-table td {
  padding: 0.5rem 0.7rem;
}

.empty-trades-note {
  padding: 1.25rem;
  text-align: center;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.trade-state-pill {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.12rem 0.45rem;
  border-radius: 4px;
  text-transform: uppercase;
}

.trade-state-pill.open {
  background: rgba(16, 185, 129, 0.15);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.35);
}

.trade-state-pill.closed {
  background: rgba(148, 163, 184, 0.12);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.25);
}

.trade-state-pill.filled {
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.35);
}
</style>
