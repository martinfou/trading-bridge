<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useControlPlane } from '../composables/useControlPlane'
import TradeChart from '../components/TradeChart.vue'
import TradeTable from '../components/TradeTable.vue'
import KpiStrip from '../components/KpiStrip.vue'
import EquityChart from '../components/EquityChart.vue'
import type { Bar, Trade } from '@/types/control-plane'

const { getControlSummary, killStrategy, getBars, getTrades, getEquityCurve, getBrokerAccounts, saveBrokerAccounts, testBrokerAccount } = useControlPlane()
const route = useRoute()

const summary = ref<any>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const selectedRunId = ref<string | null>(null)
const activeTab = ref<'overview' | 'chart' | 'trades' | 'positions'>('overview')

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

// Connection testing state
const testingConnection = ref(false)
const testResult = ref<{ success: boolean; balance?: number; currency?: string; message?: string } | null>(null)

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
      token: editingAccount.value.token.trim() || null,
      accountId: editingAccount.value.accountId.trim() || null,
      host: editingAccount.value.host,
      port: editingAccount.value.port,
      defaultRestUrl: editingAccount.value.provider === 'OANDA' ? 'https://api-fxpractice.oanda.com' : null
    }
    const res = await testBrokerAccount(payload)
    if (res && res.success) {
      testResult.value = {
        success: true,
        balance: res.balance,
        currency: res.currency
      }
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

async function fetchSummary() {
  try {
    const data = await getControlSummary()
    summary.value = data
    error.value = null
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
  if (acc) {
    editingAccount.value = {
      id: acc.id,
      provider: acc.provider,
      token: acc.configured ? '********' : '',
      accountId: acc.maskedAccountId || '',
      host: acc.host || '127.0.0.1',
      port: acc.port || 7497,
      isNew: false
    }
  } else {
    editingAccount.value = {
      id: '',
      provider: 'OANDA',
      token: '',
      accountId: '',
      host: '127.0.0.1',
      port: 7497,
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
          token: editingAccount.value.token.trim() || null,
          accountId: editingAccount.value.accountId.trim() || null,
          host: editingAccount.value.host,
          port: editingAccount.value.port
        })
      } else {
        accountsPayload.push({
          id: acc.id,
          provider: acc.provider,
          token: null,
          accountId: null,
          host: null,
          port: null
        })
      }
    })

    if (!exists) {
      accountsPayload.push({
        id: editingAccount.value.id,
        provider: editingAccount.value.provider,
        token: editingAccount.value.token.trim() || null,
        accountId: editingAccount.value.accountId.trim() || null,
        host: editingAccount.value.host,
        port: editingAccount.value.port
      })
    }

    await saveBrokerAccounts(accountsPayload)
    accountsSuccess.value = 'Broker configuration updated successfully!'
    editingAccount.value = null
    await fetchAccounts()
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
  return summary.value.runs.filter((r: any) => r.mode === 'LIVE' || r.mode === 'PAPER')
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
const inspectError = ref<string | null>(null)

async function inspectRun(runId: string) {
  selectedRunId.value = runId
  loadingInspect.value = true
  inspectError.value = null
  activeTab.value = 'overview'
  
  try {
    const [barsData, tradesData, equityData] = await Promise.all([
      getBars(runId).catch(() => []),
      getTrades(runId).catch(() => []),
      getEquityCurve(runId).catch(() => [])
    ])
    inspectBars.value = barsData
    inspectTrades.value = tradesData
    inspectEquity.value = equityData
  } catch (e: any) {
    inspectError.value = 'Failed to load strategy telemetry details: ' + e.message
  } finally {
    loadingInspect.value = false
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

onMounted(async () => {
  await fetchSummary()
  const runId = route.query.runId
  if (typeof runId === 'string') {
    inspectRun(runId)
  }
  pollTimer = setInterval(fetchSummary, 10000)
})

watch(() => route.query.runId, (newRunId) => {
  if (typeof newRunId === 'string') {
    inspectRun(newRunId)
  }
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="view-container">
    <div class="header">
      <div class="title-section">
        <h2>Live Room 📡</h2>
        <p class="subtitle">Prop-shop real-time operations board</p>
      </div>
      <div style="display: flex; gap: 0.5rem;">
        <button class="btn btn-secondary" @click="toggleAccountsConfig">
          🔑 {{ showAccountsConfig ? 'Close Keys Panel' : 'Broker Accounts' }}
        </button>
        <button class="btn btn-secondary" @click="fetchSummary" :disabled="loading">
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
              <th>Account Details</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="acc in accounts" :key="acc.id">
              <td><strong>{{ acc.id }}</strong></td>
              <td><span class="provider-badge">{{ acc.provider }}</span></td>
              <td>{{ acc.maskedAccountId }}</td>
              <td>
                <span :class="['status-badge', acc.configured ? 'configured' : 'missing']">
                  {{ acc.configured ? 'Active Credentials' : 'Credentials Missing' }}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 0.5rem;">
                  <button class="btn btn-secondary btn-sm" @click="startEditAccount(acc)">Edit</button>
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
              <label>Account name/ID</label>
              <input v-model="editingAccount.id" type="text" :disabled="!editingAccount.isNew" placeholder="e.g. oanda_practice" />
            </div>
            <div class="field mb-3">
              <label>Provider</label>
              <select v-model="editingAccount.provider" :disabled="!editingAccount.isNew">
                <option value="OANDA">OANDA</option>
                <option value="IBKR">IBKR</option>
              </select>
            </div>
            
            <!-- OANDA specific -->
            <div v-if="editingAccount.provider === 'OANDA'">
              <div class="field mb-3">
                <label>API Key / Token</label>
                <input v-model="editingAccount.token" type="password" placeholder="•••••••••••••••• (Leave blank to keep existing)" />
              </div>
              <div class="field mb-3">
                <label>OANDA Account ID</label>
                <input v-model="editingAccount.accountId" type="text" placeholder="e.g. 101-002-1234567-001" />
              </div>
            </div>

            <!-- IBKR specific -->
            <div v-if="editingAccount.provider === 'IBKR'">
              <div class="field mb-3">
                <label>TWS / Gateway Host</label>
                <input v-model="editingAccount.host" type="text" placeholder="127.0.0.1" />
              </div>
              <div class="field mb-3">
                <label>TWS / Gateway Port</label>
                <input v-model.number="editingAccount.port" type="number" placeholder="7497" />
              </div>
            </div>

            <!-- Connection test feedback card -->
            <div v-if="testResult" :class="['banner my-3', testResult.success ? 'success' : 'error']">
              <div v-if="testResult.success">
                <strong>Connection Successful!</strong><br />
                Account balance: {{ testResult.balance }} {{ testResult.currency }}
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

    <!-- Active operational summary statistics -->
    <div class="stats-strip mb-6">
      <div class="stat-card">
        <span class="stat-label">Live Running</span>
        <span class="stat-value text-success">{{ stats.activeLive }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Paper Running</span>
        <span class="stat-value text-info">{{ stats.activePaper }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Total Allocation</span>
        <span class="stat-value">${{ stats.totalAllocated.toLocaleString() }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Stale Heartbeats</span>
        <span :class="['stat-value', stats.staleCount > 0 ? 'text-danger' : '']">{{ stats.staleCount }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Drawdown Paused</span>
        <span :class="['stat-value', stats.breachCount > 0 ? 'text-danger' : '']">{{ stats.breachCount }}</span>
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

    <div v-else-if="liveRuns.length === 0" class="empty-state">
      <div class="empty-icon">📡</div>
      <h3>No Live or Paper Strategies Running</h3>
      <p>Deploy a strategy to Live/Paper mode from the Strategies catalog or control plane to begin real-time operations monitoring.</p>
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
            <span class="symbol-badge">{{ run.symbol }}</span>
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
          <div class="metric-row">
            <span>Daily Drawdown</span>
            <span :class="['metric-val', run.dailyDdBreached ? 'text-danger' : '']">
              {{ run.dailyDrawdownPct ? run.dailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}
            </span>
          </div>
          <div class="metric-row">
            <span>Max Daily Drawdown</span>
            <span>{{ run.maxDailyDrawdownPct ? run.maxDailyDrawdownPct.toFixed(2) + '%' : '0.00%' }}</span>
          </div>
          <div class="metric-row">
            <span>Event Count</span>
            <span>{{ run.eventCount }}</span>
          </div>
        </div>

        <div class="card-footer">
          <button class="btn btn-secondary btn-sm flex-grow" @click="inspectRun(run.runId)">
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
          <h3>Monitoring: {{ selectedRun.strategyId }}</h3>
          <p class="run-meta">ID: {{ selectedRun.runId }} | Symbol: {{ selectedRun.symbol }} | Mode: {{ selectedRun.mode }}</p>
        </div>
        <button class="btn-close" @click="selectedRunId = null">×</button>
      </div>

      <div class="tab-bar">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">
          Overview
        </button>
        <button :class="['tab', { active: activeTab === 'positions' }]" @click="activeTab = 'positions'">
          Open Positions
          <span class="tab-count">{{ selectedRun.positions ? selectedRun.positions.length : 0 }}</span>
        </button>
        <button :class="['tab', { active: activeTab === 'chart' }]" @click="activeTab = 'chart'">
          Price Chart
        </button>
        <button :class="['tab', { active: activeTab === 'trades' }]" @click="activeTab = 'trades'">
          Trades History
          <span class="tab-count">{{ inspectTrades.length }}</span>
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
              <span class="kpi-val">${{ selectedRun.configSnapshot?.capital?.toLocaleString() ?? '—' }}</span>
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
            <p>No active floating positions currently held by this strategy.</p>
          </div>
          <table v-else class="live-positions-table">
            <thead>
              <tr>
                <th>Symbol</th>
                <th>Side</th>
                <th>Quantity</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(p, i) in selectedRun.positions" :key="i">
                <td>{{ p.symbol }}</td>
                <td>
                  <span :class="['side-badge', p.side.toLowerCase()]">{{ p.side }}</span>
                </td>
                <td class="num">{{ p.quantity }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Price Chart tab -->
        <div v-if="activeTab === 'chart'" class="tab-panel">
          <TradeChart :bars="inspectBars" :trades="inspectTrades" :height="400" />
        </div>

        <!-- Trades History tab -->
        <div v-if="activeTab === 'trades'" class="tab-panel">
          <TradeTable :trades="inspectTrades" />
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
  grid-template-columns: repeat(5, 1fr);
  gap: 1rem;
}

.stat-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
  text-transform: uppercase;
  margin-bottom: 0.35rem;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
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

.live-positions-table td.num {
  font-family: monospace;
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
  font-size: 0.7rem;
  font-weight: 700;
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
</style>
