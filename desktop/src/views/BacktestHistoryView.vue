<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import EquityChart from '@/components/EquityChart.vue'
import ParameterSensitivityHeatmap from '@/components/ParameterSensitivityHeatmap.vue'
import ParetoFrontierChart from '@/components/ParetoFrontierChart.vue'
import {
  Clock,
  Search,
  Filter,
  ArrowUpDown,
  ChevronRight,
  X,
  ExternalLink,
  RefreshCw,
  Sliders,
  DollarSign,
  Percent,
  Activity,
  GitBranch,
  Trash2
} from '@lucide/vue'

const router = useRouter()
const { listBacktests, getBacktestDetails, getStrategies, deleteBacktest, deleteAllBacktests } = useControlPlane()

// Tab navigation state
const activeTab = ref<'history' | 'heatmap' | 'pareto'>('history')

// Filter State
const strategyId = ref('')
const symbol = ref('')
const minSharpe = ref<number | null>(null)
const minPF = ref<number | null>(null)
const sortBy = ref('created_at')
const sortOrder = ref('DESC')
const limit = ref(15)
const offset = ref(0)

// Data State
const runs = ref<any[]>([])
const totalRuns = ref(0)
const loading = ref(true)
const errorMsg = ref<string | null>(null)
const strategiesList = ref<any[]>([])

// Detail Drawer State
const selectedRunId = ref<string | null>(null)
const selectedRunDetails = ref<any | null>(null)
const loadingDetails = ref(false)
const equityCurveData = ref<number[]>([])

// Fetch Runs from API
async function fetchRuns() {
  loading.value = true
  errorMsg.value = null
  try {
    const response = await listBacktests({
      strategyId: strategyId.value || undefined,
      symbol: symbol.value.trim() || undefined,
      minSharpe: minSharpe.value !== null ? minSharpe.value : undefined,
      minProfitFactor: minPF.value !== null ? minPF.value : undefined,
      sortBy: sortBy.value,
      sortOrder: sortOrder.value,
      limit: limit.value,
      offset: offset.value,
    })
    runs.value = response.items || []
    totalRuns.value = response.total || 0
  } catch (err: any) {
    errorMsg.value = err.message || 'Failed to fetch backtest history.'
  } finally {
    loading.value = false
  }
}

// Fetch Detailed Run for Drawer
async function openDrawer(runId: string) {
  selectedRunId.value = runId
  loadingDetails.value = true
  selectedRunDetails.value = null
  equityCurveData.value = []
  try {
    const details = await getBacktestDetails(runId)
    selectedRunDetails.value = details
    if (details && details.equityCurve) {
      try {
        equityCurveData.value = JSON.parse(details.equityCurve)
      } catch (e) {
        console.error('Failed to parse equity curve JSON:', e)
      }
    }
  } catch (err: any) {
    console.error('Failed to load run details:', err)
  } finally {
    loadingDetails.value = false
  }
}

function closeDrawer() {
  selectedRunId.value = null
  selectedRunDetails.value = null
  equityCurveData.value = []
}

function toggleSort(columnKey: string) {
  if (sortBy.value === columnKey) {
    sortOrder.value = sortOrder.value === 'ASC' ? 'DESC' : 'ASC'
  } else {
    sortBy.value = columnKey
    sortOrder.value = 'DESC'
  }
}

async function handleDeleteBacktest(runId: string) {
  if (!confirm(`Are you sure you want to delete backtest run ${runId.slice(0, 8)}?`)) {
    return
  }
  try {
    await deleteBacktest(runId)
    if (selectedRunId.value === runId) {
      closeDrawer()
    }
    fetchRuns()
  } catch (err: any) {
    alert(`Failed to delete backtest run: ${err.message}`)
  }
}

async function handleDeleteAll() {
  if (!confirm("Are you sure you want to delete ALL backtest runs? This action cannot be undone.")) {
    return
  }
  try {
    await deleteAllBacktests()
    closeDrawer()
    fetchRuns()
  } catch (err: any) {
    alert(`Failed to delete all backtests: ${err.message}`)
  }
}

// Reset Filters
function resetFilters() {
  strategyId.value = ''
  symbol.value = ''
  minSharpe.value = null
  minPF.value = null
  sortBy.value = 'created_at'
  sortOrder.value = 'DESC'
  offset.value = 0
  fetchRuns()
}

// Watchers for Filter trigger
watch([sortBy, sortOrder, limit, strategyId], () => {
  offset.value = 0
  fetchRuns()
})

onMounted(async () => {
  fetchRuns()
  try {
    strategiesList.value = await getStrategies()
  } catch (e) {
    console.error('Failed to load strategies catalog:', e)
  }
})

// Helpers
function formatDate(isoString: string): string {
  if (!isoString) return '—'
  return new Date(isoString).toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function parseParameters(paramsJson: string): Record<string, any> {
  try {
    return JSON.parse(paramsJson)
  } catch (e) {
    return {}
  }
}

function getTradeSize(run: any): string {
  const params = parseParameters(run.parameters)
  if (params.lotSize !== undefined) {
    return `${params.lotSize.toFixed(2)} Lot`
  }
  if (params.quantity !== undefined) {
    return `${(params.quantity / 100000.0).toFixed(2)} Lot`
  }
  return '—'
}

function viewFullReport(runId: string) {
  router.push(`/results/${runId}`)
}

function rerunBacktest(run: any) {
  if (!run) return
  const sym = run.symbol.replace(/_/g, '/')
  router.push(`/dashboard?strategyId=${run.strategyId}&symbol=${sym}`)
}

// Pagination Helpers
const currentPage = ref(1)
watch(offset, () => {
  currentPage.value = Math.floor(offset.value / limit.value) + 1
})

function nextPage() {
  if (offset.value + limit.value < totalRuns.value) {
    offset.value += limit.value
    fetchRuns()
  }
}

function prevPage() {
  if (offset.value - limit.value >= 0) {
    offset.value -= limit.value
    fetchRuns()
  }
}
</script>

<template>
  <div class="view-container">
    <div class="view-header">
      <div>
        <h1>Backtest History</h1>
        <p class="subtitle">Query, analyze, and optimize historical backtest runs</p>
      </div>
      <div class="header-actions">
        <button class="btn btn-secondary" @click="resetFilters">Reset Filters</button>
        <button class="btn btn-danger" @click="handleDeleteAll" :disabled="runs.length === 0">
          <Trash2 class="btn-icon" />
          Delete All
        </button>
        <button class="btn btn-primary" @click="fetchRuns">
          <RefreshCw class="btn-icon" :class="{ 'spin': loading }" />
          Refresh
        </button>
      </div>
    </div>

    <!-- Filters Panel -->
    <div class="filters-panel">
      <div class="filter-group">
        <label>Strategy ID</label>
        <select v-model="strategyId">
          <option value="">All Strategies</option>
          <option v-for="s in strategiesList" :key="s.id" :value="s.id">{{ s.id }}</option>
        </select>
      </div>

      <div class="filter-group">
        <label>Symbol</label>
        <input v-model="symbol" type="text" placeholder="e.g. EUR_USD" @keyup.enter="fetchRuns" />
      </div>

      <div class="filter-group mini" v-if="activeTab === 'history'">
        <label>Min Sharpe</label>
        <input v-model.number="minSharpe" type="number" step="0.1" placeholder="0.0" @keyup.enter="fetchRuns" />
      </div>

      <div class="filter-group mini" v-if="activeTab === 'history'">
        <label>Min PF</label>
        <input v-model.number="minPF" type="number" step="0.1" placeholder="1.0" @keyup.enter="fetchRuns" />
      </div>

      <div class="filter-group" v-if="activeTab === 'history'">
        <label>Sort By</label>
        <select v-model="sortBy">
          <option value="created_at">Date Executed</option>
          <option value="run_id">Run ID</option>
          <option value="strategy_id">Strategy ID</option>
          <option value="symbol">Symbol</option>
          <option value="initial_capital">Starting Capital</option>
          <option value="total_trades">Trades</option>
          <option value="win_rate_pct">Win %</option>
          <option value="sharpe_ratio">Sharpe Ratio</option>
          <option value="profit_factor">Profit Factor</option>
          <option value="total_pnl">Total PnL</option>
          <option value="max_drawdown_pct">Max Drawdown</option>
        </select>
      </div>

      <div class="filter-group mini" v-if="activeTab === 'history'">
        <label>Order</label>
        <select v-model="sortOrder">
          <option value="DESC">DESC</option>
          <option value="ASC">ASC</option>
        </select>
      </div>
    </div>

    <!-- Tab Buttons -->
    <div class="view-tabs mb-4">
      <button :class="['tab-btn', { active: activeTab === 'history' }]" @click="activeTab = 'history'">
        <Clock class="tab-icon" />
        History List
      </button>
      <button :class="['tab-btn', { active: activeTab === 'heatmap' }]" @click="activeTab = 'heatmap'">
        <Sliders class="tab-icon" />
        Parameter Heatmap
      </button>
      <button :class="['tab-btn', { active: activeTab === 'pareto' }]" @click="activeTab = 'pareto'">
        <GitBranch class="tab-icon" />
        Pareto Frontier
      </button>
    </div>

    <!-- Main Content Grid -->
    <div class="content-wrapper">
      <!-- Tab 1: History List Table -->
      <template v-if="activeTab === 'history'">
        <!-- Error banner -->
        <div v-if="errorMsg" class="banner error-banner">
          <span>{{ errorMsg }}</span>
        </div>

        <!-- Loading State -->
        <div v-if="loading && runs.length === 0" class="loading-state">
          <div class="spinner"></div>
          <p>Loading historical runs...</p>
        </div>

        <!-- Empty State -->
        <div v-else-if="runs.length === 0" class="empty-state">
          <p>No historical backtests found matching the current filters.</p>
          <button class="btn btn-secondary mt-4" @click="resetFilters">Clear Filters</button>
        </div>

        <!-- Table View -->
        <div v-else class="table-container">
          <table class="history-table">
            <thead>
              <tr>
                <th class="sortable" @click="toggleSort('run_id')">
                  Run ID
                  <span class="sort-indicator" v-if="sortBy === 'run_id'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="sortable" @click="toggleSort('strategy_id')">
                  Strategy ID
                  <span class="sort-indicator" v-if="sortBy === 'strategy_id'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="sortable" @click="toggleSort('symbol')">
                  Symbol
                  <span class="sort-indicator" v-if="sortBy === 'symbol'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('initial_capital')">
                  Capital
                  <span class="sort-indicator" v-if="sortBy === 'initial_capital'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right">
                  Trade Size
                </th>
                <th class="text-right sortable" @click="toggleSort('total_trades')">
                  Trades
                  <span class="sort-indicator" v-if="sortBy === 'total_trades'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('win_rate_pct')">
                  Win %
                  <span class="sort-indicator" v-if="sortBy === 'win_rate_pct'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('max_drawdown_pct')">
                  Max DD
                  <span class="sort-indicator" v-if="sortBy === 'max_drawdown_pct'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('sharpe_ratio')">
                  Sharpe
                  <span class="sort-indicator" v-if="sortBy === 'sharpe_ratio'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('profit_factor')">
                  Profit Factor
                  <span class="sort-indicator" v-if="sortBy === 'profit_factor'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-right sortable" @click="toggleSort('total_pnl')">
                  PnL
                  <span class="sort-indicator" v-if="sortBy === 'total_pnl'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="sortable" @click="toggleSort('created_at')">
                  Date
                  <span class="sort-indicator" v-if="sortBy === 'created_at'">
                    {{ sortOrder === 'ASC' ? '▲' : '▼' }}
                  </span>
                </th>
                <th class="text-center">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="run in runs"
                :key="run.runId"
                :class="{ 'selected-row': selectedRunId === run.runId }"
                @click="openDrawer(run.runId)"
              >
                <td class="font-mono text-secondary">{{ run.runId.slice(0, 8) }}</td>
                <td class="font-semibold">{{ run.strategyId }}</td>
                <td><span class="symbol-badge">{{ run.symbol }}</span></td>
                <td class="text-right font-mono">${{ run.initialCapital.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</td>
                <td class="text-right font-mono">{{ getTradeSize(run) }}</td>
                <td class="text-right">{{ run.totalTrades }}</td>
                <td class="text-right">{{ run.winRatePct.toFixed(1) }}%</td>
                <td class="text-right loss-text">{{ run.maxDrawdownPct.toFixed(2) }}%</td>
                <td class="text-right font-mono" :class="run.sharpeRatio >= 1 ? 'profit-text' : ''">
                  {{ run.sharpeRatio.toFixed(2) }}
                </td>
                <td class="text-right font-mono">{{ run.profitFactor.toFixed(2) }}</td>
                <td class="text-right font-mono" :class="run.totalPnl >= 0 ? 'profit-text' : 'loss-text'">
                  ${{ run.totalPnl.toFixed(2) }}
                </td>
                <td class="text-secondary text-sm">{{ formatDate(run.createdAt) }}</td>
                <td class="text-center action-col" @click.stop>
                  <button class="icon-btn" @click="viewFullReport(run.runId)" title="Open Full Report">
                    <ExternalLink class="icon" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>

          <!-- Pagination Bar -->
          <div class="pagination-bar">
            <div class="pagination-info">
              Showing {{ offset + 1 }} to {{ Math.min(offset + limit, totalRuns) }} of {{ totalRuns }} runs
            </div>
            <div class="pagination-controls">
              <button class="btn btn-secondary btn-sm" :disabled="offset === 0" @click="prevPage">
                Previous
              </button>
              <span class="page-num">Page {{ currentPage }}</span>
              <button class="btn btn-secondary btn-sm" :disabled="offset + limit >= totalRuns" @click="nextPage">
                Next
              </button>
            </div>
          </div>
        </div>
      </template>

      <!-- Tab 2: Parameter Sensitivity Heatmap -->
      <template v-else-if="activeTab === 'heatmap'">
        <div v-if="!strategyId" class="empty-state text-center p-8 bg-card border rounded">
          <Sliders class="empty-icon" />
          <p class="font-semibold">No Strategy Selected</p>
          <p class="text-secondary text-sm mt-1">Please select a Strategy from the filters panel above to view parameter sensitivity.</p>
        </div>
        <ParameterSensitivityHeatmap
          v-else
          :strategyId="strategyId"
          :symbol="symbol"
          @view-run="openDrawer"
        />
      </template>

      <!-- Tab 3: Pareto Frontier Scatter Plot -->
      <template v-else-if="activeTab === 'pareto'">
        <div v-if="!strategyId" class="empty-state text-center p-8 bg-card border rounded">
          <GitBranch class="empty-icon" />
          <p class="font-semibold">No Strategy Selected</p>
          <p class="text-secondary text-sm mt-1">Please select a Strategy from the filters panel above to view the Pareto optimization frontier.</p>
        </div>
        <ParetoFrontierChart
          v-else
          :strategyId="strategyId"
          :symbol="symbol"
          @view-run="openDrawer"
        />
      </template>
    </div>

    <!-- Slide-over Drawer Panel -->
    <div :class="['drawer-overlay', { 'drawer-open': selectedRunId }]" @click="closeDrawer">
      <div class="drawer-panel" @click.stop>
        <div class="drawer-header">
          <div>
            <h3>Backtest Details</h3>
            <span class="font-mono text-secondary text-xs">{{ selectedRunId }}</span>
          </div>
          <button class="close-btn" @click="closeDrawer">
            <X class="icon" />
          </button>
        </div>

        <div v-if="loadingDetails" class="drawer-loading">
          <div class="spinner"></div>
          <p>Loading run details...</p>
        </div>

        <div v-else-if="selectedRunDetails" class="drawer-content">
          <!-- KPI Cards Grid -->
          <div class="drawer-kpi-grid">
            <div class="kpi-card">
              <span class="label">Total Return</span>
              <span class="value" :class="selectedRunDetails.totalReturnPct >= 0 ? 'profit-text' : 'loss-text'">
                {{ selectedRunDetails.totalReturnPct.toFixed(2) }}%
              </span>
            </div>
            <div class="kpi-card">
              <span class="label">Sharpe Ratio</span>
              <span class="value">{{ selectedRunDetails.sharpeRatio.toFixed(2) }}</span>
            </div>
            <div class="kpi-card">
              <span class="label">Profit Factor</span>
              <span class="value">{{ selectedRunDetails.profitFactor.toFixed(2) }}</span>
            </div>
            <div class="kpi-card">
              <span class="label">Max Drawdown</span>
              <span class="value loss-text">{{ selectedRunDetails.maxDrawdownPct.toFixed(2) }}%</span>
            </div>
          </div>

          <!-- Secondary Metrics -->
          <div class="metrics-list mt-4">
            <div class="metric-row">
              <span class="m-label">Strategy ID</span>
              <span class="m-val font-semibold">{{ selectedRunDetails.strategyId }}</span>
            </div>
            <div class="metric-row">
              <span class="m-label">Symbol</span>
              <span class="m-val"><span class="symbol-badge">{{ selectedRunDetails.symbol }}</span></span>
            </div>
            <div class="metric-row">
              <span class="m-label">Backtest Period</span>
              <span class="m-val text-sm">
                {{ selectedRunDetails.periodStart.slice(0, 10) }} to {{ selectedRunDetails.periodEnd.slice(0, 10) }}
              </span>
            </div>
            <div class="metric-row">
              <span class="m-label">Total Trades</span>
              <span class="m-val">{{ selectedRunDetails.totalTrades }} (Win: {{ selectedRunDetails.winningTrades }} / Loss: {{ selectedRunDetails.losingTrades }})</span>
            </div>
            <div class="metric-row">
              <span class="m-label">Win Rate</span>
              <span class="m-val">{{ selectedRunDetails.winRatePct.toFixed(1) }}%</span>
            </div>
            <div class="metric-row">
              <span class="m-label">Avg Trade P&L</span>
              <span class="m-val font-mono">${{ selectedRunDetails.avgTradePnl.toFixed(2) }}</span>
            </div>
          </div>

          <!-- Equity Chart -->
          <div class="drawer-chart-container mt-6">
            <h4>Equity & Drawdown Curve</h4>
            <div class="chart-wrapper mt-2">
              <EquityChart
                v-if="equityCurveData.length > 0"
                :data="equityCurveData"
                :period-start="selectedRunDetails.periodStart"
                :period-end="selectedRunDetails.periodEnd"
                :height="220"
                :show-time-scale="true"
              />
              <p v-else class="text-secondary text-center py-4">No equity curve coordinates found.</p>
            </div>
          </div>

          <!-- Parameters Section -->
          <div class="drawer-params mt-6">
            <h4>Strategy Parameters</h4>
            <div class="params-grid mt-2">
              <div
                v-for="(val, name) in parseParameters(selectedRunDetails.parameters)"
                :key="name"
                class="param-tag"
              >
                <span class="p-name">{{ name }}:</span>
                <span class="p-val">{{ val }}</span>
              </div>
              <div v-if="Object.keys(parseParameters(selectedRunDetails.parameters)).length === 0" class="text-secondary text-sm">
                No configurable parameters.
              </div>
            </div>
          </div>

          <!-- Drawer Actions -->
          <div class="drawer-actions mt-8">
            <button class="btn btn-secondary w-full" @click="rerunBacktest(selectedRunDetails)">
              <RefreshCw class="btn-icon" />
              Re-run Backtest Setup
            </button>
            <button class="btn btn-primary w-full mt-2" @click="viewFullReport(selectedRunDetails.runId)">
              <ExternalLink class="btn-icon" />
              Open Detailed Analytics
            </button>
            <button class="btn btn-danger w-full mt-2" @click="handleDeleteBacktest(selectedRunDetails.runId)">
              <Trash2 class="btn-icon" />
              Delete Backtest
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view-container {
  max-width: 1200px;
  position: relative;
}

h1 {
  font-size: 1.5rem;
  margin-bottom: 0.25rem;
}

h4 {
  font-size: 0.85rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-secondary);
  letter-spacing: 0.05em;
}

.subtitle {
  color: var(--text-secondary);
  font-size: 0.875rem;
  margin-bottom: 1rem;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.5rem;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.5rem 1rem;
  font-size: 0.85rem;
  font-weight: 500;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}

.btn-primary {
  background: var(--accent);
  color: #000;
}

.btn-primary:hover {
  background: var(--accent-hover);
}

.btn-secondary {
  background: transparent;
  border-color: var(--border);
  color: var(--text-primary);
}

.btn-secondary:hover {
  border-color: #444;
  background: var(--bg-card);
}

.btn-secondary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-sm {
  padding: 0.35rem 0.75rem;
  font-size: 0.75rem;
}

.btn-icon {
  width: 0.95rem;
  height: 0.95rem;
}

.icon {
  width: 1rem;
  height: 1rem;
}

.w-full {
  width: 100%;
}

.mt-2 { margin-top: 0.5rem; }
.mb-4 { margin-bottom: 1rem; }
.mt-4 { margin-top: 1rem; }
.mt-6 { margin-top: 1.5rem; }
.mt-8 { margin-top: 2rem; }
.py-4 { padding-top: 1rem; padding-bottom: 1rem; }

.font-mono {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.font-semibold {
  font-weight: 600;
}

.text-sm {
  font-size: 0.775rem;
}

.text-xs {
  font-size: 0.675rem;
}

.text-right {
  text-align: right;
}

.text-center {
  text-align: center;
}

.text-secondary {
  color: var(--text-secondary);
}

.profit-text {
  color: var(--success);
}

.loss-text {
  color: var(--danger);
}

/* Spin animation */
.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Filters Panel */
.filters-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  margin-bottom: 1.5rem;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 150px;
  flex-grow: 1;
}

.filter-group.mini {
  min-width: 80px;
  flex-grow: 0.2;
}

.filter-group label {
  font-size: 0.65rem;
  text-transform: uppercase;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
}

.filter-group input,
.filter-group select {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.45rem 0.6rem;
  color: var(--text-primary);
  font-size: 0.8rem;
  outline: none;
  transition: border-color 0.15s;
}

.filter-group input:focus,
.filter-group select:focus {
  border-color: var(--accent);
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon {
  position: absolute;
  left: 0.5rem;
  width: 0.85rem;
  height: 0.85rem;
  color: var(--text-secondary);
}

.input-wrapper input {
  padding-left: 1.75rem;
  width: 100%;
}

/* View Sub-Tabs button styling */
.view-tabs {
  display: flex;
  gap: 0.5rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.75rem;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 600;
  padding: 0.5rem 1rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.tab-btn:hover {
  color: var(--text-primary);
  background: var(--bg-card);
}

.tab-btn.active {
  color: var(--accent);
  border-color: var(--border);
  background: var(--bg-secondary);
}

.tab-icon {
  width: 0.95rem;
  height: 0.95rem;
}

/* Main Content Area */
.content-wrapper {
  min-height: 300px;
}

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.error-banner {
  background: #2d1212;
  color: #fca5a5;
  border: 1px solid #7f1d1d;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 4rem 0;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.spinner {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text-secondary);
}

.empty-icon {
  width: 2rem;
  height: 2rem;
  margin-bottom: 0.75rem;
  stroke-width: 1.5;
  color: var(--text-secondary);
  display: inline-block;
}

/* Table styling */
.table-container {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}

.history-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8rem;
  text-align: left;
}

.history-table th {
  background: rgba(255, 255, 255, 0.02);
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  font-size: 0.65rem;
  letter-spacing: 0.05em;
}

.history-table td {
  padding: 0.85rem 1rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
  cursor: pointer;
  transition: background-color 0.15s;
}

.history-table tr:hover td {
  background: rgba(255, 255, 255, 0.015);
}

.history-table tr.selected-row td {
  background: rgba(217, 119, 6, 0.04);
}

.symbol-badge {
  background: rgba(217, 119, 6, 0.1);
  color: var(--accent);
  border: 1px solid rgba(217, 119, 6, 0.2);
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
}

.action-col {
  width: 60px;
}

.icon-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.icon-btn:hover {
  color: var(--accent);
  background: rgba(255, 255, 255, 0.05);
}

/* Pagination */
.pagination-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  background: rgba(255, 255, 255, 0.01);
  border-top: 1px solid var(--border);
}

.pagination-info {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.pagination-controls {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.page-num {
  font-size: 0.75rem;
  font-weight: 500;
}

/* Drawer slides in from the right */
.drawer-overlay {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.25s ease;
}

.drawer-overlay.drawer-open {
  opacity: 1;
  pointer-events: auto;
}

.drawer-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 500px;
  max-width: 90vw;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border);
  box-shadow: -10px 0 30px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.drawer-overlay.drawer-open .drawer-panel {
  transform: translateX(0);
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border);
}

.drawer-header h3 {
  font-size: 1.1rem;
  margin-bottom: 0.15rem;
}

.close-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.15s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-primary);
}

.drawer-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 5rem 0;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.drawer-content {
  flex-grow: 1;
  overflow-y: auto;
  padding: 1.25rem;
}

/* Drawer KPIs */
.drawer-kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.75rem;
}

.kpi-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.kpi-card .label {
  font-size: 0.6rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
}

.kpi-card .value {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-primary);
}

/* Metrics List */
.metrics-list {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
}

.metric-row {
  display: flex;
  justify-content: space-between;
  padding: 0.45rem 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.02);
}

.metric-row:last-child {
  border-bottom: none;
}

.m-label {
  font-size: 0.725rem;
  color: var(--text-secondary);
}

.m-val {
  font-size: 0.75rem;
  color: var(--text-primary);
}

/* Chart Container */
.chart-wrapper {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem;
  min-height: 230px;
}

/* Parameters Section */
.params-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.param-tag {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.25rem 0.5rem;
  font-size: 0.7rem;
  display: inline-flex;
  gap: 0.25rem;
}

.p-name {
  color: var(--text-secondary);
}

.p-val {
  color: var(--text-primary);
  font-weight: 500;
}

/* Drawer Actions */
.drawer-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.btn-danger {
  background: var(--danger);
  color: #fff;
  border-color: var(--danger);
}

.btn-danger:hover {
  background: #dc2626;
  border-color: #dc2626;
}

.btn-danger:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.sortable {
  cursor: pointer;
  user-select: none;
}

.sortable:hover {
  color: var(--text-primary) !important;
  background: rgba(255, 255, 255, 0.04) !important;
}

.sort-indicator {
  display: inline-block;
  margin-left: 0.25rem;
  font-size: 0.7rem;
  color: var(--accent);
}

.action-btn-group {
  display: flex;
  justify-content: center;
  gap: 0.25rem;
}

.delete-btn {
  color: var(--text-secondary);
}

.delete-btn:hover {
  color: var(--danger) !important;
}
</style>
