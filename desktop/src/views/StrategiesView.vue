<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStrategyCatalog, type CatalogSortField } from '@/composables/useStrategyCatalog'
import type { Strategy } from '@/types/control-plane'
import StrategyCard from '@/components/StrategyCard.vue'
import PromoteModal from '@/components/PromoteModal.vue'

const router = useRouter()

const {
  strategies,
  visibleBaseStrategies,
  loading,
  error: viewError,
  selectedAssetClass,
  selectedFamily,
  selectedStyle,
  selectedSymbol,
  selectedTimeframe,
  selectedTier,
  showHarness,
  searchQuery,
  sortBy,
  sortDir,
  viewMode,
  filteredStrategies,
  groupedStrategies,
  fetchCatalog,
  removeStrategy,
  resetFilters
} = useStrategyCatalog()

const inspectedStrategyId = ref<string | null>(null)
const promoteTargetId = ref<string | null>(null)
const showPromoteModal = ref(false)
const confirmDeleteId = ref<string | null>(null)
const showSourceCode = ref(false)
const copiedSource = ref(false)
const groupTableByFamily = ref(false)

const inspectedStrategy = computed<Strategy | null>(() => {
  if (!inspectedStrategyId.value) return null
  return strategies.value.find((s) => s.id === inspectedStrategyId.value) || null
})

const harnessCount = computed(() => strategies.value.filter((s) => s.family === 'HARNESS').length)

const families = computed(() => {
  const counts = new Map<string, number>()
  for (const s of visibleBaseStrategies.value) {
    counts.set(s.family, (counts.get(s.family) || 0) + 1)
  }
  const labels: Record<string, string> = {
    GENERATED: '🧬 Generated',
    PROP: '🏛️ Prop FX',
    LONG_TERM: '📈 Long-Term',
    CREATIVE: '🗓️ Seasonal',
    HARNESS: '🧪 Harness'
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([id, count]) => ({
      id,
      label: labels[id] || id,
      count
    }))
})

const symbolOptions = computed(() => {
  const counts = new Map<string, number>()
  for (const s of visibleBaseStrategies.value) {
    const sym = (s.defaultSymbol || 'EUR_USD').toUpperCase()
    counts.set(sym, (counts.get(sym) || 0) + 1)
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([sym, count]) => ({
      id: sym,
      label: sym.replace('_', '/'),
      count
    }))
})

const timeframeOptions = computed(() => {
  const counts = new Map<string, number>()
  for (const s of visibleBaseStrategies.value) {
    const tf = (s.primaryTimeframe || s.timeframe || 'H1').toUpperCase()
    counts.set(tf, (counts.get(tf) || 0) + 1)
  }
  const order = ['M5', 'M15', 'M30', 'H1', 'H4', 'D1']
  return Array.from(counts.entries())
    .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
    .map(([tf, count]) => ({ id: tf, count }))
})

const assetClassOptions = [
  { id: 'ALL', label: 'All Assets' },
  { id: 'FUTURES', label: '⚡ CME Futures' },
  { id: 'FOREX', label: '💱 Forex Majors' },
  { id: 'EQUITY', label: '📈 US Equities' }
]

const tierOptions = [
  { id: 'ALL', label: 'All Tiers' },
  { id: 'PROMOTED', label: '🚀 Promoted / Live' },
  { id: 'ELITE', label: '🏆 ELITE' },
  { id: 'INSTITUTIONAL', label: '🏛️ INSTITUTIONAL' },
  { id: 'CORE', label: '🎯 CORE' },
  { id: 'QUALIFIED', label: '✅ QUALIFIED' },
  { id: 'OVERLAY', label: '🗓️ OVERLAY' }
]

const sortOptions: { id: CatalogSortField; label: string }[] = [
  { id: 'score', label: 'Composite Score' },
  { id: 'pf', label: 'Profit Factor (Full)' },
  { id: 'oosPf', label: 'OOS Profit Factor' },
  { id: 'sharpe', label: 'Sharpe Ratio' },
  { id: 'dd', label: 'Lowest Max DD' },
  { id: 'winRate', label: 'Win Rate %' },
  { id: 'trades', label: 'Total Trades' },
  { id: 'netProfit', label: 'Net Profit ($)' },
  { id: 'name', label: 'Strategy Name' }
]

// Portfolio Summary KPIs
const portfolioKpis = computed(() => {
  const list = filteredStrategies.value
  const promotedCount = list.filter((s) => s.promoted || s.deployedMode).length
  const eliteCount = list.filter((s) => s.tier === 'ELITE' || s.tier === 'CORE').length
  const withPf = list.filter((s) => (s.profitFactor ?? 0) > 0)
  const avgPf =
    withPf.length > 0
      ? withPf.reduce((acc, s) => acc + (s.profitFactor || 0), 0) / withPf.length
      : 0
  const withOos = list.filter((s) => (s.oosProfitFactor ?? 0) > 0)
  const avgOosPf =
    withOos.length > 0
      ? withOos.reduce((acc, s) => acc + (s.oosProfitFactor || 0), 0) / withOos.length
      : 0
  const bestStrat =
    [...list].sort((a, b) => (b.compositeScore || 0) - (a.compositeScore || 0))[0] || null
  const uniqueSymbols = new Set(list.map((s) => (s.defaultSymbol || '').replace('_', '/')))
  return {
    total: list.length,
    promotedCount,
    eliteCount,
    avgPf,
    avgOosPf,
    bestStrat,
    symbolsCount: uniqueSymbols.size
  }
})

const hasActiveFilters = computed(() => {
  return (
    selectedAssetClass.value !== 'ALL' ||
    selectedFamily.value !== 'ALL' ||
    selectedStyle.value !== 'ALL' ||
    selectedSymbol.value !== 'ALL' ||
    selectedTimeframe.value !== 'ALL' ||
    selectedTier.value !== 'ALL' ||
    searchQuery.value.trim() !== ''
  )
})

function toggleSortColumn(field: CatalogSortField) {
  if (sortBy.value === field) {
    sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc'
  } else {
    sortBy.value = field
    sortDir.value = 'desc'
  }
}

function openInspector(strategy: Strategy) {
  inspectedStrategyId.value = strategy.id
  showSourceCode.value = false
  confirmDeleteId.value = null
}

function closeInspector() {
  inspectedStrategyId.value = null
  confirmDeleteId.value = null
}

function stepInspector(delta: number) {
  const list = filteredStrategies.value
  if (!list.length || !inspectedStrategyId.value) return
  const idx = list.findIndex((s) => s.id === inspectedStrategyId.value)
  if (idx === -1) return
  const nextIdx = (idx + delta + list.length) % list.length
  inspectedStrategyId.value = list[nextIdx].id
  confirmDeleteId.value = null
}

function handleBacktest(strategy: Strategy) {
  const symbol = strategy.defaultSymbol?.replace(/_/g, '/') || 'EUR/USD'
  router.push(`/dashboard?strategyId=${encodeURIComponent(strategy.id)}&symbol=${encodeURIComponent(symbol)}`)
}

function handleWfa(strategy: Strategy) {
  const symbol = strategy.defaultSymbol || 'MES'
  router.push(`/wfa?strategyId=${encodeURIComponent(strategy.id)}&symbol=${encodeURIComponent(symbol)}`)
}

function handlePromote(strategy: Strategy) {
  promoteTargetId.value = strategy.id
  showPromoteModal.value = true
}

function requestDelete(strategy: Strategy) {
  if (confirmDeleteId.value === strategy.id) {
    executeDelete(strategy.id)
  } else {
    confirmDeleteId.value = strategy.id
  }
}

async function executeDelete(id: string) {
  confirmDeleteId.value = null
  if (inspectedStrategyId.value === id) {
    stepInspector(1)
    if (inspectedStrategyId.value === id) {
      inspectedStrategyId.value = null
    }
  }
  await removeStrategy(id)
}

async function copyJavaSource() {
  if (!inspectedStrategy.value?.javaSourceCode) return
  try {
    await navigator.clipboard.writeText(inspectedStrategy.value.javaSourceCode)
    copiedSource.value = true
    setTimeout(() => {
      copiedSource.value = false
    }, 1800)
  } catch {
    // Ignore clipboard error
  }
}

function symbolBadgeClass(sym?: string): string {
  const s = (sym || '').toUpperCase()
  if (s.startsWith('MGC') || s.includes('XAU')) return 'sym-gold'
  if (s.startsWith('MNQ') || s.startsWith('NQ')) return 'sym-cyan'
  if (s.startsWith('MES') || s.startsWith('ES')) return 'sym-purple'
  if (s.startsWith('EMD') || s.startsWith('M2K')) return 'sym-emerald'
  return 'sym-fx'
}

function tierBadgeClass(tier?: string): string {
  const t = (tier || '').toUpperCase()
  if (t === 'ELITE') return 'tier-elite'
  if (t === 'INSTITUTIONAL') return 'tier-inst'
  if (t === 'CORE') return 'tier-core'
  if (t === 'OVERLAY') return 'tier-overlay'
  return 'tier-qualified'
}

function pfColorClass(val?: number): string {
  if (val == null) return ''
  if (val >= 2.0) return 'num-elite'
  if (val >= 1.4) return 'num-good'
  if (val >= 1.1) return 'num-ok'
  return 'num-warn'
}

function ddColorClass(val?: number): string {
  if (val == null) return ''
  if (val <= 10.0) return 'num-elite'
  if (val <= 15.0) return 'num-good'
  if (val <= 25.0) return 'num-ok'
  return 'num-warn'
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

function buildSparkline(rawCurve?: number[], oosIdx?: number, w = 96, h = 26) {
  if (!rawCurve || rawCurve.length < 2) return null
  const curve = rawCurve.filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (curve.length < 2) return null
  const pad = 2
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
  const area = `M ${pad},${h} L ${pts.join(' L ')} L ${w - pad},${h} Z`
  const oosX =
    oosIdx != null && oosIdx > 0 && oosIdx < curve.length
      ? pad + (oosIdx / (curve.length - 1)) * (w - pad * 2)
      : null
  const isPositive = curve[curve.length - 1] >= curve[0]
  return { points: pts.join(' '), area, oosX, isPositive }
}

const sparklineMap = computed(() => {
  const map: Record<string, ReturnType<typeof buildSparkline>> = {}
  for (const s of filteredStrategies.value) {
    map[s.id] = buildSparkline(s.equityCurve, s.oosStartIndex)
  }
  return map
})

const drawerChart = computed(() => {
  const s = inspectedStrategy.value
  if (!s || !s.equityCurve || s.equityCurve.length < 2) return null
  return buildSparkline(s.equityCurve, s.oosStartIndex, 460, 130)
})

function onKeyDown(e: KeyboardEvent) {
  if (!inspectedStrategyId.value || showPromoteModal.value) return
  const target = e.target as HTMLElement | null
  if (
    target &&
    (target.tagName === 'INPUT' ||
      target.tagName === 'SELECT' ||
      target.tagName === 'TEXTAREA' ||
      target.isContentEditable)
  ) {
    return
  }
  if (e.key === 'Escape') {
    closeInspector()
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    stepInspector(1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    stepInspector(-1)
  }
}

onMounted(() => {
  fetchCatalog()
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <div class="catalog-page">
    <!-- Top Header & View Switcher -->
    <div class="catalog-header">
      <div class="header-left">
        <div class="title-cluster">
          <h1>Strategy Catalog</h1>
          <span class="model-count-badge">{{ portfolioKpis.total }} Models</span>
        </div>
        <p class="subtitle">
          Institutional quantitative model registry — In-Sample (2010–2021) &amp; Out-of-Sample (2022–2026) verified under realistic spread, swap &amp; IBKR fees
        </p>
      </div>

      <div class="header-controls">
        <div class="search-box">
          <span class="search-icon">🔍</span>
          <input
            v-model="searchQuery"
            type="text"
            class="search-input"
            placeholder="Search symbol (MGC, MNQ, GBP/USD), indicator, rule, or ID..."
          />
          <button v-if="searchQuery" class="clear-btn" @click="searchQuery = ''">✕</button>
        </div>

        <div class="view-switcher" role="group" aria-label="View Mode">
          <button
            :class="['view-btn', { active: viewMode === 'table' }]"
            title="High-Density Quant Table View"
            @click="viewMode = 'table'"
          >
            ☰ Quant Table
          </button>
          <button
            :class="['view-btn', { active: viewMode === 'cards' }]"
            title="Rich Bento Card Grid"
            @click="viewMode = 'cards'"
          >
            ⊞ Bento Cards
          </button>
        </div>
      </div>
    </div>

    <!-- Error Banner -->
    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Portfolio KPI Telemetry Strip -->
    <div class="kpi-strip">
      <div class="kpi-card">
        <span class="kpi-label">ACTIVE STRATEGIES</span>
        <div class="kpi-main">
          <span class="kpi-value">{{ portfolioKpis.total }}</span>
          <span class="kpi-sub">{{ portfolioKpis.symbolsCount }} instruments</span>
        </div>
      </div>

      <div class="kpi-card">
        <span class="kpi-label">DESK READY / PROMOTED</span>
        <div class="kpi-main">
          <span class="kpi-value val-emerald">{{ portfolioKpis.promotedCount }}</span>
          <span class="kpi-sub">{{ portfolioKpis.eliteCount }} Elite / Core</span>
        </div>
      </div>

      <div class="kpi-card">
        <span class="kpi-label">MEAN PROFIT FACTOR</span>
        <div class="kpi-main">
          <span class="kpi-value val-cyan">{{ portfolioKpis.avgPf ? portfolioKpis.avgPf.toFixed(2) : '—' }}</span>
          <span class="kpi-sub">OOS Avg {{ portfolioKpis.avgOosPf ? portfolioKpis.avgOosPf.toFixed(2) : '—' }}</span>
        </div>
      </div>

      <div
        v-if="portfolioKpis.bestStrat"
        class="kpi-card kpi-clickable"
        @click="openInspector(portfolioKpis.bestStrat)"
      >
        <span class="kpi-label">TOP RANKED ALPHA</span>
        <div class="kpi-main">
          <span class="kpi-value val-gold">{{ portfolioKpis.bestStrat.displayName || portfolioKpis.bestStrat.id }}</span>
          <span class="kpi-sub">
            Score {{ portfolioKpis.bestStrat.compositeScore != null ? portfolioKpis.bestStrat.compositeScore.toFixed(1) : '—' }} · PF {{ portfolioKpis.bestStrat.profitFactor != null ? portfolioKpis.bestStrat.profitFactor.toFixed(2) : '—' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Multi-Dimensional Command & Filter Bar -->
    <div class="command-bar">
      <!-- Row 1: Asset Class + Family + Tier + System Toggle -->
      <div class="filter-row">
        <div class="pill-group">
          <button
            v-for="opt in assetClassOptions"
            :key="opt.id"
            :class="['filter-pill', { active: selectedAssetClass === opt.id }]"
            @click="selectedAssetClass = opt.id as any"
          >
            {{ opt.label }}
          </button>
        </div>

        <div class="divider-v"></div>

        <div class="pill-group">
          <button
            :class="['tab-pill', { active: selectedFamily === 'ALL' }]"
            @click="selectedFamily = 'ALL'"
          >
            All Families
            <span class="pill-count">{{ visibleBaseStrategies.length }}</span>
          </button>
          <button
            v-for="f in families"
            :key="f.id"
            :class="['tab-pill', { active: selectedFamily === f.id }]"
            @click="selectedFamily = f.id"
          >
            {{ f.label }}
            <span class="pill-count">{{ f.count }}</span>
          </button>
        </div>

        <div class="filter-row-right">
          <button
            v-if="harnessCount > 0"
            :class="['harness-toggle', { active: showHarness }]"
            title="Show/Hide internal plumbing validation harness strategies"
            @click="showHarness = !showHarness"
          >
            🧪 System Harness ({{ harnessCount }})
          </button>
          <button v-if="hasActiveFilters" class="reset-link-btn" @click="resetFilters">
            ↺ Reset Filters
          </button>
        </div>
      </div>

      <!-- Row 2: Instrument Pills + Timeframe Pills + Tier + Sort Controls -->
      <div class="filter-row secondary-row">
        <!-- Instrument Quick-Pills -->
        <div class="pill-group symbol-filter-group">
          <span class="row-micro-label">SYMBOL</span>
          <button
            :class="['micro-pill', { active: selectedSymbol === 'ALL' }]"
            @click="selectedSymbol = 'ALL'"
          >
            All
          </button>
          <button
            v-for="sym in symbolOptions"
            :key="sym.id"
            :class="['micro-pill', { active: selectedSymbol === sym.id }]"
            @click="selectedSymbol = selectedSymbol === sym.id ? 'ALL' : sym.id"
          >
            {{ sym.label }}
            <span class="micro-count">{{ sym.count }}</span>
          </button>
        </div>

        <div class="divider-v"></div>

        <!-- Timeframe Quick-Pills -->
        <div class="pill-group">
          <span class="row-micro-label">TF</span>
          <button
            :class="['micro-pill', { active: selectedTimeframe === 'ALL' }]"
            @click="selectedTimeframe = 'ALL'"
          >
            All
          </button>
          <button
            v-for="tf in timeframeOptions"
            :key="tf.id"
            :class="['micro-pill', { active: selectedTimeframe === tf.id }]"
            @click="selectedTimeframe = selectedTimeframe === tf.id ? 'ALL' : tf.id"
          >
            {{ tf.id }}
          </button>
        </div>

        <div class="divider-v"></div>

        <!-- Tier Filter Select -->
        <div class="pill-group">
          <span class="row-micro-label">TIER</span>
          <select v-model="selectedTier" class="compact-select">
            <option v-for="t in tierOptions" :key="t.id" :value="t.id">{{ t.label }}</option>
          </select>
        </div>

        <!-- Sort Controls -->
        <div class="sort-cluster">
          <label v-if="viewMode === 'table'" class="group-check">
            <input v-model="groupTableByFamily" type="checkbox" />
            <span>Group by Family</span>
          </label>
          <span class="row-micro-label">SORT BY</span>
          <select v-model="sortBy" class="compact-select">
            <option v-for="opt in sortOptions" :key="opt.id" :value="opt.id">
              {{ opt.label }}
            </option>
          </select>
          <button
            class="sort-dir-btn"
            :title="sortDir === 'desc' ? 'Descending (Best first)' : 'Ascending'"
            @click="sortDir = sortDir === 'desc' ? 'asc' : 'desc'"
          >
            {{ sortDir === 'desc' ? '↓ DESC' : '↑ ASC' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Loading quantitative strategy catalog…</p>
    </div>

    <!-- Empty State -->
    <div v-else-if="filteredStrategies.length === 0" class="empty-state">
      <p v-if="searchQuery">No strategies match "{{ searchQuery }}".</p>
      <p v-else>No strategies match the current filter combination.</p>
      <button class="reset-btn" @click="resetFilters">Reset All Filters</button>
    </div>

    <!-- MODE 1: HIGH-DENSITY QUANT TABLE VIEW -->
    <template v-else-if="viewMode === 'table'">
      <div
        v-for="(stratList, groupName) in (groupTableByFamily ? groupedStrategies : { 'All Matching Strategies': filteredStrategies })"
        :key="groupName"
        class="table-section"
      >
        <h2 v-if="groupTableByFamily" class="family-heading">
          {{ groupName }}
          <span class="family-count">{{ stratList.length }}</span>
        </h2>

        <div class="quant-table-wrap">
          <table class="quant-table">
            <thead>
              <tr>
                <th class="col-rank" @click="toggleSortColumn('score')">
                  TIER / SCORE
                  <span v-if="sortBy === 'score'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-name" @click="toggleSortColumn('name')">
                  STRATEGY
                  <span v-if="sortBy === 'name'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-inst">INST · TF</th>
                <th class="col-style">STYLE &amp; INDICATORS</th>
                <th class="col-curve">EQUITY (IS | OOS)</th>
                <th class="col-num" @click="toggleSortColumn('pf')">
                  FULL PF
                  <span v-if="sortBy === 'pf'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('oosPf')">
                  OOS PF
                  <span v-if="sortBy === 'oosPf'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('sharpe')">
                  SHARPE
                  <span v-if="sortBy === 'sharpe'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('dd')">
                  MAX DD
                  <span v-if="sortBy === 'dd'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('winRate')">
                  WIN %
                  <span v-if="sortBy === 'winRate'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('trades')">
                  TRADES
                  <span v-if="sortBy === 'trades'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-num" @click="toggleSortColumn('netProfit')">
                  NET PNL
                  <span v-if="sortBy === 'netProfit'" class="sort-caret">{{ sortDir === 'desc' ? '▾' : '▴' }}</span>
                </th>
                <th class="col-actions">ACTIONS</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="s in stratList"
                :key="s.id"
                :class="['quant-row', { 'row-selected': inspectedStrategyId === s.id }]"
                @click="openInspector(s)"
              >
                <!-- Tier & Score -->
                <td class="col-rank">
                  <div class="tier-cell">
                    <span :class="['tier-badge', tierBadgeClass(s.tier)]">
                      {{ s.tier || 'QUALIFIED' }}
                    </span>
                    <span v-if="s.compositeScore" class="score-num">
                      {{ s.compositeScore.toFixed(1) }}
                    </span>
                  </div>
                </td>

                <!-- Strategy Name & ID -->
                <td class="col-name">
                  <div class="name-cell">
                    <div class="name-top">
                      <span class="row-title">{{ s.displayName || s.id }}</span>
                      <span v-if="s.deployedMode" class="mini-status live">
                        ● {{ s.deployedMode }}
                      </span>
                      <span v-else-if="s.promoted" class="mini-status promoted">
                        PROMOTED
                      </span>
                    </div>
                    <code class="row-id">{{ s.id }}</code>
                  </div>
                </td>

                <!-- Instrument & Timeframe -->
                <td class="col-inst">
                  <div class="inst-cell">
                    <span :class="['inst-badge', symbolBadgeClass(s.defaultSymbol)]">
                      {{ (s.defaultSymbol || 'EUR_USD').replace('_', '/') }}
                    </span>
                    <span class="tf-badge">{{ s.primaryTimeframe || s.timeframe || 'H1' }}</span>
                  </div>
                </td>

                <!-- Style & Indicators -->
                <td class="col-style">
                  <div class="style-cell">
                    <span class="style-tag">{{ (s.tradingStyle || s.type || 'TREND').replace(/_/g, ' ') }}</span>
                    <div class="mini-ind-list">
                      <span
                        v-for="ind in (s.indicators || []).slice(0, 3)"
                        :key="ind"
                        class="mini-ind"
                      >
                        {{ ind }}
                      </span>
                    </div>
                  </div>
                </td>

                <!-- Inline Equity Sparkline -->
                <td class="col-curve">
                  <div v-if="sparklineMap[s.id]" class="table-spark">
                    <svg viewBox="0 0 96 26" class="table-spark-svg" preserveAspectRatio="none">
                      <path
                        :d="sparklineMap[s.id]!.area"
                        :fill="sparklineMap[s.id]!.isPositive ? 'rgba(34, 197, 94, 0.14)' : 'rgba(239, 68, 68, 0.14)'"
                      />
                      <line
                        v-if="sparklineMap[s.id]!.oosX"
                        :x1="sparklineMap[s.id]!.oosX!"
                        y1="1"
                        :x2="sparklineMap[s.id]!.oosX!"
                        y2="25"
                        stroke="rgba(251, 191, 36, 0.45)"
                        stroke-width="1"
                        stroke-dasharray="2,2"
                      />
                      <polyline
                        fill="none"
                        :stroke="sparklineMap[s.id]!.isPositive ? '#22c55e' : '#ef4444'"
                        stroke-width="1.5"
                        :points="sparklineMap[s.id]!.points"
                      />
                    </svg>
                  </div>
                  <span v-else class="muted-dash">—</span>
                </td>

                <!-- Full PF -->
                <td :class="['col-num', 'mono', pfColorClass(s.profitFactor)]">
                  {{ s.profitFactor != null ? s.profitFactor.toFixed(2) : '—' }}
                </td>

                <!-- OOS PF -->
                <td :class="['col-num', 'mono', pfColorClass(s.oosProfitFactor)]">
                  {{ s.oosProfitFactor != null ? s.oosProfitFactor.toFixed(2) : '—' }}
                </td>

                <!-- Sharpe -->
                <td :class="['col-num', 'mono', (s.sharpeRatio ?? 0) >= 1.2 ? 'num-elite' : '']">
                  {{ s.sharpeRatio != null ? s.sharpeRatio.toFixed(2) : '—' }}
                </td>

                <!-- Max DD -->
                <td :class="['col-num', 'mono', ddColorClass(s.maxDrawdownPct)]">
                  {{ s.maxDrawdownPct != null ? `${s.maxDrawdownPct.toFixed(1)}%` : '—' }}
                </td>

                <!-- Win Rate -->
                <td class="col-num mono">
                  {{ s.winRatePct != null ? `${s.winRatePct.toFixed(1)}%` : '—' }}
                </td>

                <!-- Trades -->
                <td class="col-num mono">
                  <div class="trades-cell">
                    <span>{{ s.totalTrades ?? '—' }}</span>
                    <span v-if="s.isTrades != null && s.oosTrades != null" class="trades-sub">
                      {{ s.isTrades }}/{{ s.oosTrades }}
                    </span>
                  </div>
                </td>

                <!-- Net PnL -->
                <td :class="['col-num', 'mono', (s.netProfit ?? 0) >= 0 ? 'num-elite' : 'num-warn']">
                  {{ formatMoney(s.netProfit) }}
                </td>

                <!-- Row Quick Actions -->
                <td class="col-actions" @click.stop>
                  <div class="row-actions">
                    <button class="icon-btn run" title="Run Backtest" @click="handleBacktest(s)">
                      ▶
                    </button>
                    <button class="icon-btn wfa" title="Run Walk-Forward Analysis" @click="handleWfa(s)">
                      📈
                    </button>
                    <button class="icon-btn promote" title="Promote Strategy" @click="handlePromote(s)">
                      🚀
                    </button>
                    <button
                      :class="['icon-btn', 'delete', { confirming: confirmDeleteId === s.id }]"
                      :title="confirmDeleteId === s.id ? 'Click again to confirm delete' : 'Delete / Prune Strategy'"
                      @click="requestDelete(s)"
                    >
                      {{ confirmDeleteId === s.id ? '✓?' : '🗑️' }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- MODE 2: WORLD-CLASS BENTO CARD GRID -->
    <template v-else>
      <div v-for="(stratList, groupName) in groupedStrategies" :key="groupName" class="family-section">
        <h2 class="family-heading">
          {{ groupName }}
          <span class="family-count">{{ stratList.length }}</span>
        </h2>
        <div class="card-grid">
          <StrategyCard
            v-for="s in stratList"
            :key="s.id"
            :strategy="s"
            :selected="inspectedStrategyId === s.id"
            :confirming-delete="confirmDeleteId === s.id"
            @inspect="openInspector"
            @backtest="handleBacktest"
            @wfa="handleWfa"
            @promote="handlePromote"
            @delete="requestDelete"
          />
        </div>
      </div>
    </template>

    <!-- RIGHT-HAND SLIDE-OVER STRATEGY INSPECTOR DRAWER -->
    <Teleport to="body">
      <div v-if="inspectedStrategy" class="drawer-backdrop" @click.self="closeInspector">
        <aside class="inspector-drawer" aria-label="Strategy Inspector">
          <!-- Drawer Top Bar -->
          <div class="drawer-topbar">
            <div class="drawer-nav-btns">
              <button class="nav-step-btn" title="Previous Strategy (↑)" @click="stepInspector(-1)">
                ↑ Prev
              </button>
              <button class="nav-step-btn" title="Next Strategy (↓)" @click="stepInspector(1)">
                ↓ Next
              </button>
            </div>
            <button class="drawer-close-btn" title="Close (Esc)" @click="closeInspector">✕</button>
          </div>

          <!-- Drawer Identity Header -->
          <div class="drawer-header">
            <div class="drawer-badges">
              <span :class="['inst-badge', symbolBadgeClass(inspectedStrategy.defaultSymbol)]">
                {{ (inspectedStrategy.defaultSymbol || 'EUR_USD').replace('_', '/') }}
              </span>
              <span class="tf-badge">
                {{ inspectedStrategy.primaryTimeframe || inspectedStrategy.timeframe || 'H1' }}
              </span>
              <span :class="['tier-badge', tierBadgeClass(inspectedStrategy.tier)]">
                {{ inspectedStrategy.tier || 'QUALIFIED' }}
                <strong v-if="inspectedStrategy.compositeScore">
                  · {{ inspectedStrategy.compositeScore.toFixed(1) }}
                </strong>
              </span>
              <span v-if="inspectedStrategy.deployedMode" class="mini-status live">
                ● {{ inspectedStrategy.deployedMode }} ({{ inspectedStrategy.executionLabel || 'DESK' }})
              </span>
              <span v-else-if="inspectedStrategy.promoted" class="mini-status promoted">
                🚀 PROMOTED
              </span>
            </div>

            <h2 class="drawer-title">{{ inspectedStrategy.displayName || inspectedStrategy.id }}</h2>
            <div class="drawer-id-line">
              <code>{{ inspectedStrategy.id }}</code>
              <span class="family-tag">{{ inspectedStrategy.family }}</span>
              <span class="style-tag">{{ (inspectedStrategy.tradingStyle || '').replace(/_/g, ' ') }}</span>
            </div>
          </div>

          <!-- Scrollable Drawer Body -->
          <div class="drawer-body">
            <!-- Equity Curve Panel -->
            <div v-if="drawerChart" class="drawer-section">
              <div class="section-header-row">
                <span class="section-title">EQUITY CURVE (IN-SAMPLE vs OUT-OF-SAMPLE)</span>
                <span class="oos-legend">
                  <span class="oos-dash"></span> Yellow line = 70/30 OOS Split ({{ inspectedStrategy.yearSpec || '2010–2026' }})
                </span>
              </div>
              <div class="drawer-chart-box">
                <svg viewBox="0 0 460 130" class="drawer-chart-svg" preserveAspectRatio="none">
                  <path
                    :d="drawerChart.area"
                    :fill="drawerChart.isPositive ? 'rgba(34, 197, 94, 0.14)' : 'rgba(239, 68, 68, 0.14)'"
                  />
                  <line
                    v-if="drawerChart.oosX"
                    :x1="drawerChart.oosX"
                    y1="4"
                    :x2="drawerChart.oosX"
                    y2="126"
                    stroke="#fbbf24"
                    stroke-width="1.5"
                    stroke-dasharray="4,4"
                  />
                  <polyline
                    fill="none"
                    :stroke="drawerChart.isPositive ? '#22c55e' : '#ef4444'"
                    stroke-width="2.2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :points="drawerChart.points"
                  />
                </svg>
              </div>
            </div>

            <!-- IS vs OOS Comparison Matrix -->
            <div class="drawer-section">
              <span class="section-title">QUANTITATIVE VALIDATION SCORECARD</span>
              <div class="matrix-table-wrap">
                <table class="matrix-table">
                  <thead>
                    <tr>
                      <th>METRIC</th>
                      <th>FULL PERIOD</th>
                      <th>IN-SAMPLE (70%)</th>
                      <th>OUT-OF-SAMPLE (30%)</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td>Profit Factor</td>
                      <td :class="['mono', pfColorClass(inspectedStrategy.profitFactor)]">
                        {{ inspectedStrategy.profitFactor != null ? inspectedStrategy.profitFactor.toFixed(2) : '—' }}
                      </td>
                      <td class="mono">
                        {{ inspectedStrategy.isProfitFactor != null ? inspectedStrategy.isProfitFactor.toFixed(2) : '—' }}
                      </td>
                      <td :class="['mono', pfColorClass(inspectedStrategy.oosProfitFactor)]">
                        {{ inspectedStrategy.oosProfitFactor != null ? inspectedStrategy.oosProfitFactor.toFixed(2) : '—' }}
                      </td>
                    </tr>
                    <tr>
                      <td>Sharpe Ratio</td>
                      <td class="mono">{{ inspectedStrategy.sharpeRatio != null ? inspectedStrategy.sharpeRatio.toFixed(2) : '—' }}</td>
                      <td class="mono">{{ inspectedStrategy.isSharpeRatio != null ? inspectedStrategy.isSharpeRatio.toFixed(2) : '—' }}</td>
                      <td class="mono">{{ inspectedStrategy.oosSharpeRatio != null ? inspectedStrategy.oosSharpeRatio.toFixed(2) : '—' }}</td>
                    </tr>
                    <tr>
                      <td>Trade Count</td>
                      <td class="mono">{{ inspectedStrategy.totalTrades ?? '—' }}</td>
                      <td class="mono">{{ inspectedStrategy.isTrades ?? '—' }}</td>
                      <td class="mono">{{ inspectedStrategy.oosTrades ?? '—' }}</td>
                    </tr>
                    <tr>
                      <td>Net Profit</td>
                      <td :class="['mono', (inspectedStrategy.netProfit ?? 0) >= 0 ? 'num-elite' : 'num-warn']">
                        {{ formatMoney(inspectedStrategy.netProfit) }}
                      </td>
                      <td class="mono">{{ formatMoney(inspectedStrategy.isNetProfit) }}</td>
                      <td :class="['mono', (inspectedStrategy.oosNetProfit ?? 0) >= 0 ? 'num-elite' : 'num-warn']">
                        {{ formatMoney(inspectedStrategy.oosNetProfit) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <!-- Secondary Risk Metrics Row -->
              <div class="risk-pills-grid">
                <div class="risk-box">
                  <span class="r-label">MAX DRAWDOWN</span>
                  <span :class="['r-val', ddColorClass(inspectedStrategy.maxDrawdownPct)]">
                    {{ inspectedStrategy.maxDrawdownPct != null ? `${inspectedStrategy.maxDrawdownPct.toFixed(2)}%` : '—' }}
                  </span>
                </div>
                <div class="risk-box">
                  <span class="r-label">WIN RATE</span>
                  <span class="r-val">
                    {{ inspectedStrategy.winRatePct != null ? `${inspectedStrategy.winRatePct.toFixed(1)}%` : '—' }}
                  </span>
                </div>
                <div class="risk-box">
                  <span class="r-label">STABILITY (R²)</span>
                  <span class="r-val">
                    {{ inspectedStrategy.rSquared != null ? inspectedStrategy.rSquared.toFixed(2) : '—' }}
                  </span>
                </div>
                <div class="risk-box">
                  <span class="r-label">RET / DD RATIO</span>
                  <span class="r-val">
                    {{ inspectedStrategy.retDdRatio != null ? inspectedStrategy.retDdRatio.toFixed(2) : '—' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Strategy Signal & Rule Blueprint -->
            <div class="drawer-section">
              <span class="section-title">EXECUTION &amp; SIGNAL BLUEPRINT</span>
              <p v-if="inspectedStrategy.description" class="drawer-desc">
                {{ inspectedStrategy.description }}
              </p>

              <div class="blueprint-cards">
                <div v-if="inspectedStrategy.longSignalSummary" class="bp-item bp-long">
                  <span class="bp-tag">LONG ENTRY</span>
                  <code>{{ inspectedStrategy.longSignalSummary }}</code>
                </div>
                <div v-if="inspectedStrategy.shortSignalSummary" class="bp-item bp-short">
                  <span class="bp-tag">SHORT ENTRY</span>
                  <code>{{ inspectedStrategy.shortSignalSummary }}</code>
                </div>
                <div v-if="inspectedStrategy.exitSummary" class="bp-item bp-exit">
                  <span class="bp-tag">EXIT &amp; STOPS</span>
                  <code>{{ inspectedStrategy.exitSummary }}</code>
                </div>
                <div v-if="inspectedStrategy.sessionSummary" class="bp-item bp-session">
                  <span class="bp-tag">SESSION GATE</span>
                  <code>{{ inspectedStrategy.sessionSummary }}</code>
                </div>
              </div>
            </div>

            <!-- Indicators & Recommended Symbols -->
            <div class="drawer-section two-col-meta">
              <div>
                <span class="section-title">INDICATORS</span>
                <div class="chip-wrap">
                  <span
                    v-for="ind in inspectedStrategy.indicators || []"
                    :key="ind"
                    class="ind-chip"
                  >
                    {{ ind }}
                  </span>
                </div>
              </div>
              <div>
                <span class="section-title">RECOMMENDED SYMBOLS</span>
                <div class="chip-wrap">
                  <span
                    v-for="sym in (inspectedStrategy.recommendedSymbols?.length ? inspectedStrategy.recommendedSymbols : [inspectedStrategy.defaultSymbol || 'EUR_USD'])"
                    :key="sym"
                    :class="['inst-badge', symbolBadgeClass(sym)]"
                  >
                    {{ sym.replace('_', '/') }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Collapsible Java Source Code Inspector -->
            <div v-if="inspectedStrategy.javaSourceCode" class="drawer-section">
              <div class="source-header">
                <button class="source-toggle-btn" @click="showSourceCode = !showSourceCode">
                  {{ showSourceCode ? '▾ Hide Synthesized Java Source' : '▸ View Synthesized Java Source Code' }}
                </button>
                <button v-if="showSourceCode" class="copy-code-btn" @click="copyJavaSource">
                  {{ copiedSource ? '✓ Copied' : '📋 Copy Java' }}
                </button>
              </div>
              <pre v-if="showSourceCode" class="java-source-pre"><code>{{ inspectedStrategy.javaSourceCode }}</code></pre>
            </div>
          </div>

          <!-- Sticky Drawer Action Footer -->
          <div class="drawer-footer">
            <button class="drawer-btn primary-run" @click="handleBacktest(inspectedStrategy)">
              ▶ Run Backtest
            </button>
            <button class="drawer-btn wfa-run" @click="handleWfa(inspectedStrategy)">
              📈 Run WFA
            </button>
            <button class="drawer-btn promote-run" @click="handlePromote(inspectedStrategy)">
              🚀 Promote
            </button>
            <button
              :class="['drawer-btn', 'delete-run', { confirming: confirmDeleteId === inspectedStrategy.id }]"
              @click="requestDelete(inspectedStrategy)"
            >
              {{ confirmDeleteId === inspectedStrategy.id ? '✓ Confirm Delete' : '🗑️ Prune' }}
            </button>
          </div>
        </aside>
      </div>
    </Teleport>

    <!-- Promote Modal -->
    <PromoteModal
      v-if="promoteTargetId"
      :strategyId="promoteTargetId"
      :show="showPromoteModal"
      @close="showPromoteModal = false"
      @promoted="fetchCatalog(true)"
    />
  </div>
</template>

<style scoped>
.catalog-page {
  max-width: 1560px;
  padding-bottom: 3rem;
}

.catalog-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1.25rem;
  margin-bottom: 1rem;
  flex-wrap: wrap;
}

.title-cluster {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}

h1 {
  font-size: 1.55rem;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 0;
}

.model-count-badge {
  background: rgba(245, 158, 11, 0.16);
  border: 1px solid rgba(245, 158, 11, 0.38);
  color: #fbbf24;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.75rem;
  font-weight: 700;
  padding: 0.18rem 0.55rem;
  border-radius: 999px;
}

.subtitle {
  color: var(--text-secondary, #94a3b8);
  font-size: 0.82rem;
  margin: 0.25rem 0 0;
}

.header-controls {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  flex-wrap: wrap;
}

.search-box {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 340px;
}

.search-icon {
  position: absolute;
  left: 0.75rem;
  font-size: 0.82rem;
  pointer-events: none;
}

.search-input {
  width: 100%;
  background: rgba(20, 22, 28, 0.95);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  padding: 0.52rem 2rem 0.52rem 2.2rem;
  color: var(--text-primary, #f8fafc);
  font-size: 0.82rem;
  outline: none;
  transition: border-color 0.15s;
}

.search-input:focus {
  border-color: #f59e0b;
}

.clear-btn {
  position: absolute;
  right: 0.55rem;
  background: transparent;
  border: none;
  color: #94a3b8;
  cursor: pointer;
  font-size: 0.8rem;
}

.view-switcher {
  display: inline-flex;
  background: rgba(20, 22, 28, 0.95);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  padding: 0.18rem;
  gap: 0.18rem;
}

.view-btn {
  background: transparent;
  border: none;
  color: #94a3b8;
  font-size: 0.76rem;
  font-weight: 600;
  padding: 0.38rem 0.72rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.14s;
}

.view-btn.active {
  background: #f59e0b;
  color: #000;
  font-weight: 700;
}

/* KPI Strip */
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 0.65rem;
  margin-bottom: 0.9rem;
}

.kpi-card {
  background: linear-gradient(180deg, rgba(24, 26, 32, 0.9) 0%, rgba(16, 18, 23, 0.95) 100%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 9px;
  padding: 0.65rem 0.9rem;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.kpi-clickable {
  cursor: pointer;
  transition: border-color 0.14s;
}

.kpi-clickable:hover {
  border-color: rgba(245, 158, 11, 0.5);
}

.kpi-label {
  font-size: 0.62rem;
  font-weight: 700;
  color: #64748b;
  letter-spacing: 0.06em;
}

.kpi-main {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}

.kpi-value {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 1.12rem;
  font-weight: 800;
  color: #f8fafc;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kpi-sub {
  font-size: 0.72rem;
  color: #94a3b8;
  white-space: nowrap;
}

.val-emerald {
  color: #4ade80;
}
.val-cyan {
  color: #38bdf8;
}
.val-gold {
  color: #fbbf24;
  font-family: inherit;
  font-size: 0.95rem;
}

/* Command Bar */
.command-bar {
  background: rgba(18, 20, 26, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 0.65rem 0.85rem;
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
  margin-bottom: 1.15rem;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.secondary-row {
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.pill-group {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  flex-wrap: wrap;
}

.divider-v {
  width: 1px;
  height: 20px;
  background: rgba(255, 255, 255, 0.09);
  margin: 0 0.15rem;
}

.filter-pill {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  padding: 0.32rem 0.7rem;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.14s;
}

.filter-pill.active {
  background: #f59e0b;
  border-color: #f59e0b;
  color: #000;
}

.tab-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.09);
  color: #94a3b8;
  padding: 0.3rem 0.62rem;
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.14s;
}

.tab-pill.active {
  border-color: #f59e0b;
  color: #fbbf24;
  background: rgba(245, 158, 11, 0.1);
}

.pill-count {
  background: rgba(0, 0, 0, 0.35);
  padding: 0.04rem 0.38rem;
  border-radius: 8px;
  font-size: 0.65rem;
}

.filter-row-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.45rem;
}

.harness-toggle {
  background: rgba(255, 255, 255, 0.03);
  border: 1px dashed rgba(255, 255, 255, 0.15);
  color: #64748b;
  font-size: 0.72rem;
  padding: 0.28rem 0.58rem;
  border-radius: 6px;
  cursor: pointer;
}

.harness-toggle.active {
  border-style: solid;
  border-color: #a855f7;
  color: #c084fc;
  background: rgba(168, 85, 247, 0.12);
}

.reset-link-btn {
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #fca5a5;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0.28rem 0.58rem;
  border-radius: 6px;
  cursor: pointer;
}

.row-micro-label {
  font-size: 0.62rem;
  font-weight: 700;
  color: #64748b;
  letter-spacing: 0.05em;
  margin-right: 0.15rem;
}

.micro-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #94a3b8;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.2rem 0.48rem;
  border-radius: 5px;
  cursor: pointer;
}

.micro-pill.active {
  background: rgba(56, 189, 248, 0.16);
  border-color: rgba(56, 189, 248, 0.45);
  color: #38bdf8;
}

.micro-count {
  font-size: 0.6rem;
  opacity: 0.7;
}

.compact-select {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #e2e8f0;
  font-size: 0.74rem;
  font-weight: 600;
  padding: 0.24rem 0.5rem;
  border-radius: 5px;
  outline: none;
}

.sort-cluster {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.group-check {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.72rem;
  color: #94a3b8;
  margin-right: 0.4rem;
  cursor: pointer;
}

.sort-dir-btn {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #cbd5e1;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 0.24rem 0.5rem;
  border-radius: 5px;
  cursor: pointer;
}

/* QUANT TABLE VIEW */
.table-section {
  margin-bottom: 1.4rem;
}

.quant-table-wrap {
  background: rgba(17, 19, 24, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  overflow-x: auto;
}

.quant-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.78rem;
}

.quant-table thead th {
  background: rgba(24, 27, 34, 0.98);
  color: #64748b;
  font-size: 0.6rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-align: left;
  padding: 0.52rem 0.36rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  white-space: nowrap;
  user-select: none;
  cursor: pointer;
}

.quant-table thead th.col-num {
  text-align: right;
}

.quant-table thead th.col-actions {
  text-align: right;
  cursor: default;
  padding-right: 0.55rem;
}

.sort-caret {
  color: #f59e0b;
  margin-left: 0.12rem;
}

.quant-row {
  border-bottom: 1px solid rgba(255, 255, 255, 0.045);
  cursor: pointer;
  transition: background 0.12s;
}

.quant-row:hover {
  background: rgba(245, 158, 11, 0.05);
}

.quant-row.row-selected {
  background: rgba(245, 158, 11, 0.11);
}

.quant-row td {
  padding: 0.44rem 0.36rem;
  vertical-align: middle;
}

.quant-row td.col-name {
  max-width: 210px;
  overflow: hidden;
}

.quant-row td.col-style {
  max-width: 150px;
  overflow: hidden;
}

.quant-row td.col-actions {
  padding-right: 0.55rem;
  white-space: nowrap;
}

.col-num {
  text-align: right;
  white-space: nowrap;
}

.mono {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-weight: 600;
}

.tier-cell {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  white-space: nowrap;
}

.tier-badge {
  font-size: 0.62rem;
  font-weight: 700;
  padding: 0.12rem 0.42rem;
  border-radius: 4px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.tier-elite {
  background: rgba(245, 158, 11, 0.16);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.35);
}

.tier-inst {
  background: rgba(56, 189, 248, 0.14);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.32);
}

.tier-core {
  background: rgba(34, 197, 94, 0.14);
  color: #4ade80;
  border: 1px solid rgba(34, 197, 94, 0.32);
}

.tier-overlay {
  background: rgba(168, 85, 247, 0.14);
  color: #c084fc;
  border: 1px solid rgba(168, 85, 247, 0.32);
}

.tier-qualified {
  background: rgba(148, 163, 184, 0.12);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.25);
}

.score-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.75rem;
  font-weight: 700;
  color: #e2e8f0;
}

.name-cell {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}

.name-top {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  white-space: nowrap;
}

.row-title {
  font-weight: 700;
  color: #f8fafc;
  font-size: 0.81rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-id {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.66rem;
  color: #64748b;
}

.mini-status {
  font-size: 0.58rem;
  font-weight: 700;
  padding: 0.08rem 0.35rem;
  border-radius: 3px;
  letter-spacing: 0.04em;
}

.mini-status.live {
  background: rgba(34, 197, 94, 0.16);
  color: #4ade80;
}

.mini-status.promoted {
  background: rgba(56, 189, 248, 0.14);
  color: #38bdf8;
}

.inst-cell {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  white-space: nowrap;
}

.inst-badge {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.7rem;
  font-weight: 700;
  padding: 0.12rem 0.42rem;
  border-radius: 4px;
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

.tf-badge {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.66rem;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  padding: 0.1rem 0.36rem;
  border-radius: 4px;
}

.style-cell {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.style-tag {
  font-size: 0.64rem;
  font-weight: 700;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.mini-ind-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.22rem;
}

.mini-ind {
  font-size: 0.6rem;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  padding: 0.04rem 0.3rem;
  border-radius: 3px;
}

.table-spark {
  width: 76px;
  height: 24px;
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 4px;
  overflow: hidden;
}

.table-spark-svg {
  width: 100%;
  height: 100%;
  display: block;
}

.num-elite {
  color: #4ade80;
}
.num-good {
  color: #38bdf8;
}
.num-ok {
  color: #e2e8f0;
}
.num-warn {
  color: #fbbf24;
}

.trades-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.trades-sub {
  font-size: 0.58rem;
  color: #64748b;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.28rem;
}

.icon-btn {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  font-size: 0.72rem;
  padding: 0.24rem 0.44rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.12s;
}

.icon-btn.run:hover {
  background: #f59e0b;
  border-color: #f59e0b;
  color: #000;
}

.icon-btn.wfa:hover {
  background: rgba(168, 85, 247, 0.3);
  border-color: #a855f7;
}

.icon-btn.promote:hover {
  background: rgba(34, 197, 94, 0.3);
  border-color: #22c55e;
}

.icon-btn.delete:hover,
.icon-btn.delete.confirming {
  background: rgba(239, 68, 68, 0.25);
  border-color: #ef4444;
  color: #fca5a5;
}

/* BENTO GRID */
.family-section {
  margin-bottom: 1.65rem;
}

.family-heading {
  font-size: 0.95rem;
  font-weight: 700;
  margin-bottom: 0.65rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.family-count {
  font-size: 0.74rem;
  font-weight: 600;
  color: #94a3b8;
  background: rgba(255, 255, 255, 0.06);
  padding: 0.08rem 0.45rem;
  border-radius: 999px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 0.75rem;
}

/* SLIDE-OVER INSPECTOR DRAWER */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(2px);
  z-index: 950;
  display: flex;
  justify-content: flex-end;
}

.inspector-drawer {
  width: min(560px, 94vw);
  height: 100vh;
  background: #12141a;
  border-left: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: -16px 0 40px rgba(0, 0, 0, 0.65);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.drawer-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1.15rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  background: rgba(255, 255, 255, 0.02);
}

.drawer-nav-btns {
  display: flex;
  gap: 0.4rem;
}

.nav-step-btn {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0.25rem 0.6rem;
  border-radius: 5px;
  cursor: pointer;
}

.nav-step-btn:hover {
  background: rgba(255, 255, 255, 0.12);
}

.drawer-close-btn {
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #94a3b8;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
}

.drawer-header {
  padding: 1rem 1.25rem 0.9rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.drawer-badges {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
  margin-bottom: 0.5rem;
}

.drawer-title {
  font-size: 1.28rem;
  font-weight: 800;
  color: #f8fafc;
  margin: 0 0 0.25rem;
}

.drawer-id-line {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  font-size: 0.75rem;
  color: #64748b;
}

.drawer-id-line code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  color: #94a3b8;
}

.family-tag {
  font-size: 0.65rem;
  font-weight: 700;
  color: #cbd5e1;
  background: rgba(255, 255, 255, 0.06);
  padding: 0.08rem 0.38rem;
  border-radius: 4px;
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 1rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1.15rem;
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.section-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-title {
  font-size: 0.66rem;
  font-weight: 800;
  color: #64748b;
  letter-spacing: 0.06em;
}

.oos-legend {
  font-size: 0.66rem;
  color: #fbbf24;
}

.drawer-chart-box {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 0.4rem;
  height: 138px;
}

.drawer-chart-svg {
  width: 100%;
  height: 100%;
  display: block;
}

.matrix-table-wrap {
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  overflow: hidden;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.78rem;
}

.matrix-table th {
  background: rgba(255, 255, 255, 0.04);
  color: #64748b;
  font-size: 0.62rem;
  font-weight: 700;
  text-align: left;
  padding: 0.48rem 0.65rem;
}

.matrix-table td {
  padding: 0.48rem 0.65rem;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.risk-pills-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.45rem;
  margin-top: 0.25rem;
}

.risk-box {
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  padding: 0.45rem 0.55rem;
  display: flex;
  flex-direction: column;
  gap: 0.12rem;
}

.r-label {
  font-size: 0.58rem;
  font-weight: 700;
  color: #64748b;
}

.r-val {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.85rem;
  font-weight: 700;
  color: #f8fafc;
}

.drawer-desc {
  font-size: 0.8rem;
  line-height: 1.45;
  color: #cbd5e1;
  margin: 0;
}

.blueprint-cards {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.bp-item {
  background: rgba(0, 0, 0, 0.32);
  border-left: 3px solid #64748b;
  border-radius: 0 6px 6px 0;
  padding: 0.48rem 0.65rem;
  display: flex;
  flex-direction: column;
  gap: 0.18rem;
}

.bp-long {
  border-left-color: #22c55e;
}
.bp-short {
  border-left-color: #ef4444;
}
.bp-exit {
  border-left-color: #f59e0b;
}
.bp-session {
  border-left-color: #38bdf8;
}

.bp-tag {
  font-size: 0.6rem;
  font-weight: 800;
  color: #94a3b8;
  letter-spacing: 0.05em;
}

.bp-item code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.74rem;
  color: #e2e8f0;
  word-break: break-word;
}

.two-col-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.chip-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 0.32rem;
  margin-top: 0.35rem;
}

.ind-chip {
  font-size: 0.68rem;
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 0.14rem 0.45rem;
  border-radius: 4px;
}

.source-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.source-toggle-btn,
.copy-code-btn {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #cbd5e1;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0.32rem 0.65rem;
  border-radius: 5px;
  cursor: pointer;
}

.java-source-pre {
  background: #0b0d12;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 0.65rem;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.68rem;
  color: #cbd5e1;
  max-height: 240px;
  overflow: auto;
  margin: 0;
}

.drawer-footer {
  padding: 0.85rem 1.25rem;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(15, 17, 22, 0.98);
  display: flex;
  gap: 0.5rem;
}

.drawer-btn {
  border-radius: 6px;
  font-size: 0.78rem;
  font-weight: 700;
  padding: 0.5rem 0.9rem;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.14s;
}

.primary-run {
  background: #f59e0b;
  color: #000;
}

.wfa-run {
  background: rgba(168, 85, 247, 0.16);
  border-color: rgba(168, 85, 247, 0.4);
  color: #d8b4fe;
}

.promote-run {
  background: rgba(34, 197, 94, 0.16);
  border-color: rgba(34, 197, 94, 0.4);
  color: #4ade80;
}

.delete-run {
  margin-left: auto;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.35);
  color: #fca5a5;
}

.delete-run.confirming {
  background: #ef4444;
  color: #fff;
}

.loading-state,
.empty-state {
  padding: 3rem;
  text-align: center;
  color: #94a3b8;
}

.spinner {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid rgba(255, 255, 255, 0.15);
  border-top-color: #f59e0b;
  border-radius: 50%;
  margin: 0 auto 0.75rem;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.reset-btn {
  margin-top: 0.75rem;
  background: #f59e0b;
  color: #000;
  border: none;
  padding: 0.48rem 1rem;
  border-radius: 6px;
  font-weight: 700;
  cursor: pointer;
}
</style>
