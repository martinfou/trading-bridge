<script setup lang="ts">
import { ref, computed, onUnmounted, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { useStatusBar } from '@/composables/useStatusBar'
import UniverseManagerDrawer from '@/components/UniverseManagerDrawer.vue'
import type { InstrumentDefinition } from '@/types/control-plane'
import {
  Database,
  RefreshCw,
  Zap,
  DownloadCloud,
  CheckCircle2,
  AlertCircle,
  Clock,
  Layers,
  Server,
  Trash2,
  Sliders
} from '@lucide/vue'

const route = useRoute()
const { getHistoricalDataStatus, downloadHistoricalData, deleteHistoricalData, getInstruments } = useControlPlane()
const { setStatus } = useStatusBar()

const dataTimeframe = ref<'h1' | 'm1'>('h1')
const selectedAssetCategory = ref<'forex' | 'futures' | 'equities'>('forex')
const selectedProvider = ref<'yahoo' | 'dukascopy' | 'oanda' | 'ibkr'>('yahoo')

const dataStatus = ref<any[]>([])
const activeDownloads = ref<string[]>([])
const activeTasks = ref<any[]>([])
const loadError = ref<string | null>(null)
const infoMessage = ref<string | null>(null)
const actionLoading = ref(false)
const isUniverseDrawerOpen = ref(false)

const selectedPair = ref('eurusd')
const selectedYear = ref(new Date().getFullYear())
const selectedTf = ref<'h1' | 'm1'>('h1')
const downloadMode = ref<'single' | 'range' | 'all'>('single')
const selectedStartYear = ref(2006)
const selectedEndYear = ref(new Date().getFullYear())

const rawInstruments = ref<InstrumentDefinition[]>([])

const instrumentsByCategory = computed(() => {
  const forex = rawInstruments.value
    .filter(i => i.assetClass.toUpperCase() === 'FOREX')
    .map(i => ({ symbol: i.symbol.toLowerCase().replace('_', ''), rawSymbol: i.symbol, label: i.symbol.replace('_', '/'), desc: i.name, type: 'Forex' }))
  
  const futures = rawInstruments.value
    .filter(i => i.assetClass.toUpperCase() === 'FUTURES')
    .map(i => ({ symbol: i.symbol.toLowerCase(), rawSymbol: i.symbol, label: i.symbol, desc: i.name, type: 'CME Futures' }))

  const equities = rawInstruments.value
    .filter(i => i.assetClass.toUpperCase() === 'EQUITIES')
    .map(i => ({ symbol: i.symbol.toLowerCase(), rawSymbol: i.symbol, label: i.symbol, desc: i.name, type: 'US Equities' }))

  return { forex, futures, equities }
})

const currentInstruments = computed(() => instrumentsByCategory.value[selectedAssetCategory.value] || [])
const currentSymbolList = computed(() => currentInstruments.value.map(i => i.symbol))

const yearsList = computed(() => {
  const current = new Date().getFullYear()
  const years = []
  for (let y = current; y >= 2006; y--) {
    years.push(y)
  }
  return years
})

function onCategoryChange(cat: 'forex' | 'futures' | 'equities') {
  selectedAssetCategory.value = cat
  const insts = instrumentsByCategory.value[cat]
  if (insts && insts.length > 0) {
    selectedPair.value = insts[0].symbol
  }
}

async function loadInstrumentsUniverse() {
  try {
    const list = await getInstruments()
    rawInstruments.value = list
    const currentList = instrumentsByCategory.value[selectedAssetCategory.value]
    if (currentList && currentList.length > 0 && !currentList.some(i => i.symbol === selectedPair.value)) {
      selectedPair.value = currentList[0].symbol
    }
  } catch (err: any) {
    console.error('Failed to load instruments universe:', err)
  }
}

async function refreshDataStatus() {
  try {
    loadError.value = null
    const res = await getHistoricalDataStatus(dataTimeframe.value)
    dataStatus.value = res.status || []
    activeDownloads.value = res.activeDownloads || []
    activeTasks.value = res.activeTasks || []
  } catch (err: any) {
    loadError.value = err.message
  }
}

async function onUniverseUpdated() {
  await loadInstrumentsUniverse()
  await refreshDataStatus()
}

const groupedStatus = computed(() => {
  const map: Record<string, Record<number, any>> = {}
  Object.values(instrumentsByCategory.value).flat().forEach(inst => {
    map[inst.symbol.toLowerCase()] = {}
  })
  dataStatus.value.forEach(item => {
    const p = (item.pair || item.symbol || '').toLowerCase()
    if (map[p]) {
      map[p][item.year] = item
    }
  })
  return map
})

async function triggerDownload(sync = false) {
  if (actionLoading.value) return
  actionLoading.value = true
  const infoMsg = sync 
    ? 'Syncing current year historical data...' 
    : `Starting ingestion for ${selectedPair.value.toUpperCase()} (${selectedTf.value.toUpperCase()}) via ${selectedProvider.value.toUpperCase()}...`
  setStatus(infoMsg, 'info')
  try {
    dataTimeframe.value = selectedTf.value
    let params: any
    if (sync) {
      params = { syncMode: true, tf: selectedTf.value, provider: selectedProvider.value }
    } else {
      if (downloadMode.value === 'all') {
        params = {
          pair: selectedPair.value,
          startYear: 2006,
          endYear: new Date().getFullYear(),
          tf: selectedTf.value,
          provider: selectedProvider.value
        }
      } else if (downloadMode.value === 'range') {
        params = {
          pair: selectedPair.value,
          startYear: selectedStartYear.value,
          endYear: selectedEndYear.value,
          tf: selectedTf.value,
          provider: selectedProvider.value
        }
      } else {
        params = {
          pair: selectedPair.value,
          year: selectedYear.value,
          tf: selectedTf.value,
          provider: selectedProvider.value
        }
      }
    }
    await downloadHistoricalData(params)
    setStatus(`Successfully submitted ingestion task for ${selectedPair.value.toUpperCase()} ${selectedTf.value.toUpperCase()}`, 'success')
    refreshDataStatus()
  } catch (err: any) {
    loadError.value = err.message
    setStatus(`Ingestion failed: ${err.message}`, 'error')
  } finally {
    actionLoading.value = false
  }
}

async function triggerDelete(pair: string, year: number) {
  if (!confirm(`Are you sure you want to delete historical data for ${pair.toUpperCase()} ${year} (${dataTimeframe.value.toUpperCase()})?`)) {
    return
  }
  actionLoading.value = true
  setStatus(`Deleting ${pair.toUpperCase()} ${year} dataset...`, 'info')
  try {
    await deleteHistoricalData({ pair, year, tf: dataTimeframe.value })
    setStatus(`Successfully deleted historical data for ${pair.toUpperCase()} ${year}.`, 'success')
    refreshDataStatus()
  } catch (err: any) {
    loadError.value = err.message
    setStatus(`Deletion failed: ${err.message}`, 'error')
  } finally {
    actionLoading.value = false
  }
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

let pollTimer: any = null

onMounted(async () => {
  await loadInstrumentsUniverse()
  await refreshDataStatus()
  pollTimer = setInterval(refreshDataStatus, 5000)
  if (route.query.pair) {
    selectedPair.value = String(route.query.pair).toLowerCase()
  }
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="data-manager-container">
    <!-- Header -->
    <header class="page-header">
      <div class="header-left">
        <div class="header-badge">
          <Database class="icon-sm text-accent" />
          <span>Historical Repository</span>
        </div>
        <h1 class="header-title">Data Management Hub</h1>
        <p class="header-subtitle">
          Ingest, sync, and inspect historical bars across CME Futures, US Equities (Small/Mid Cap), and Forex.
        </p>
      </div>

      <div class="header-actions">
        <button class="btn secondary manage-btn" @click="isUniverseDrawerOpen = true">
          <Sliders class="icon-xs" />
          ⚡ Manage Universe
        </button>
        <button class="btn secondary" @click="refreshDataStatus">
          <RefreshCw class="icon-xs" />
          Refresh
        </button>
        <button
          class="btn primary"
          :disabled="actionLoading"
          @click="triggerDownload(true)"
        >
          <Zap class="icon-xs" />
          Sync Current Year
        </button>
      </div>
    </header>

    <!-- Error Banner -->
    <div v-if="loadError" class="banner error">
      <AlertCircle class="icon-sm" />
      <span>{{ loadError }}</span>
    </div>

    <!-- Active Tasks Progress Card -->
    <div v-if="activeDownloads.length > 0 || activeTasks.length > 0" class="active-tasks-card mb-4">
      <div class="tasks-header">
        <div class="flex items-center gap-2">
          <div class="spinner-sm"></div>
          <h4>Active Background Ingestions ({{ activeDownloads.length + activeTasks.length }})</h4>
        </div>
        <span class="text-xs text-muted">Downloading via broker / proxy API...</span>
      </div>
      <div class="tasks-list">
        <div v-for="task in activeDownloads" :key="task" class="task-row">
          <DownloadCloud class="icon-sm text-accent" />
          <span class="task-name">{{ task.toUpperCase() }}</span>
          <span class="task-status">Downloading & Parsing Parquet/CSV...</span>
        </div>
      </div>
    </div>

    <!-- Main Coverage Section -->
    <div class="card coverage-card">
      <div class="coverage-toolbar">
        <!-- Asset Class Tabs -->
        <div class="asset-category-tabs">
          <button
            class="category-tab forex"
            :class="{ active: selectedAssetCategory === 'forex' }"
            @click="onCategoryChange('forex')"
          >
            Forex Majors ({{ instrumentsByCategory.forex.length }})
          </button>
          <button
            class="category-tab futures"
            :class="{ active: selectedAssetCategory === 'futures' }"
            @click="onCategoryChange('futures')"
          >
            CME Micro/Mini Futures ({{ instrumentsByCategory.futures.length }})
          </button>
          <button
            class="category-tab equities"
            :class="{ active: selectedAssetCategory === 'equities' }"
            @click="onCategoryChange('equities')"
          >
            US Small/Mid Cap Equities ({{ instrumentsByCategory.equities.length }})
          </button>
        </div>

        <!-- Timeframe Toggle -->
        <div class="tf-selector">
          <button
            :class="['tf-btn', { active: dataTimeframe === 'h1' }]"
            @click="dataTimeframe = 'h1'; refreshDataStatus()"
          >
            H1 Bars
          </button>
          <button
            :class="['tf-btn', { active: dataTimeframe === 'm1' }]"
            @click="dataTimeframe = 'm1'; refreshDataStatus()"
          >
            M1 Bars
          </button>
        </div>
      </div>

      <!-- Provider Selector Bar -->
      <div class="provider-bar">
        <span class="provider-label">Data Ingestion Source:</span>
        <div class="provider-pills">
          <button
            class="provider-pill"
            :class="{ active: selectedProvider === 'yahoo' }"
            @click="selectedProvider = 'yahoo'"
          >
            Yahoo Finance / Stooq (Continuous Futures & Stocks)
          </button>
          <button
            class="provider-pill"
            :class="{ active: selectedProvider === 'dukascopy' }"
            @click="selectedProvider = 'dukascopy'"
          >
            Dukascopy (Forex & Metals)
          </button>
          <button
            class="provider-pill"
            :class="{ active: selectedProvider === 'oanda' }"
            @click="selectedProvider = 'oanda'"
          >
            OANDA API (Forex Live)
          </button>
          <button
            class="provider-pill"
            :class="{ active: selectedProvider === 'ibkr' }"
            @click="selectedProvider = 'ibkr'"
          >
            IBKR TWS API (Direct Futures & Stocks)
          </button>
        </div>
      </div>

      <!-- Coverage Heatmap Matrix Table -->
      <div class="matrix-container">
        <table class="matrix-table">
          <thead>
            <tr>
              <th class="inst-col">Instrument</th>
              <th class="spec-col">Specs</th>
              <th v-for="year in yearsList" :key="year" class="year-header">
                {{ year.toString().slice(2) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="inst in currentInstruments" :key="inst.symbol">
              <td class="inst-cell">
                <span class="symbol-tag" :class="selectedAssetCategory">{{ inst.label }}</span>
              </td>
              <td class="spec-cell text-muted">
                {{ inst.desc }}
              </td>
              <td
                v-for="year in yearsList"
                :key="year"
                class="matrix-cell"
              >
                <div
                  v-if="groupedStatus[inst.symbol.toLowerCase()] && groupedStatus[inst.symbol.toLowerCase()][year]"
                  :class="[
                    'status-indicator',
                    {
                      complete: groupedStatus[inst.symbol.toLowerCase()][year].barsExists,
                      partial: groupedStatus[inst.symbol.toLowerCase()][year].csvExists && !groupedStatus[inst.symbol.toLowerCase()][year].barsExists,
                      missing: !groupedStatus[inst.symbol.toLowerCase()][year].csvExists && !groupedStatus[inst.symbol.toLowerCase()][year].barsExists,
                      syncing: activeDownloads.includes(inst.symbol + '-' + year + '-' + dataTimeframe)
                    }
                  ]"
                  :title="`${inst.label} ${year} (${dataTimeframe.toUpperCase()})\nBARS: ${groupedStatus[inst.symbol.toLowerCase()][year].barsExists ? formatBytes(groupedStatus[inst.symbol.toLowerCase()][year].barsSize) : 'None'}\nCSV: ${groupedStatus[inst.symbol.toLowerCase()][year].csvExists ? formatBytes(groupedStatus[inst.symbol.toLowerCase()][year].csvSize) : 'None'}\nClick to Delete`"
                  @click="groupedStatus[inst.symbol.toLowerCase()][year].barsExists || groupedStatus[inst.symbol.toLowerCase()][year].csvExists ? triggerDelete(inst.symbol, year) : null"
                ></div>
                <div v-else class="status-indicator missing"></div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Matrix Legend -->
      <div class="matrix-legend">
        <div class="legend-item"><span class="dot complete"></span> Available in Local Store</div>
        <div class="legend-item"><span class="dot partial"></span> Partial (CSV or Binary Bars)</div>
        <div class="legend-item"><span class="dot missing"></span> Missing / Unsynced</div>
        <div class="legend-item"><span class="dot syncing"></span> Ingestion in Progress</div>
        <span class="spacer"></span>
        <span class="legend-note">* Click on any active cell to purge dataset.</span>
      </div>
    </div>

    <!-- Ingestion Command Card -->
    <div class="card download-form-section mt-4">
      <div class="form-header">
        <DownloadCloud class="icon-sm text-accent" />
        <div>
          <h3>Ingest Historical Dataset</h3>
          <p class="section-subtitle">Fetch candles directly from selected source and build Parquet / CSV bar cache</p>
        </div>
      </div>
      
      <div class="download-fields">
        <div class="field">
          <label>Target Instrument</label>
          <select v-model="selectedPair" class="form-select">
            <optgroup label="Forex Majors">
              <option v-for="p in instrumentsByCategory.forex" :key="p.symbol" :value="p.symbol">
                {{ p.label }} — {{ p.desc }}
              </option>
            </optgroup>
            <optgroup label="CME Futures">
              <option v-for="p in instrumentsByCategory.futures" :key="p.symbol" :value="p.symbol">
                {{ p.label }} — {{ p.desc }}
              </option>
            </optgroup>
            <optgroup label="US Equities Small/Mid Cap">
              <option v-for="p in instrumentsByCategory.equities" :key="p.symbol" :value="p.symbol">
                {{ p.label }} — {{ p.desc }}
              </option>
            </optgroup>
          </select>
        </div>
        
        <div class="field">
          <label>Granularity</label>
          <select v-model="selectedTf" class="form-select">
            <option value="h1">H1 (1-Hour)</option>
            <option value="m1">M1 (1-Minute)</option>
          </select>
        </div>

        <div class="field">
          <label>Mode</label>
          <select v-model="downloadMode" class="form-select">
            <option value="single">Single Year</option>
            <option value="range">Year Range</option>
            <option value="all">All History (2006-2026)</option>
          </select>
        </div>

        <div class="field" v-if="downloadMode === 'single'">
          <label>Year</label>
          <select v-model="selectedYear" class="form-select">
            <option v-for="y in yearsList" :key="y" :value="y">
              {{ y }}
            </option>
          </select>
        </div>

        <div class="field" v-if="downloadMode === 'range'">
          <label>Start Year</label>
          <select v-model="selectedStartYear" class="form-select">
            <option v-for="y in yearsList" :key="y" :value="y">
              {{ y }}
            </option>
          </select>
        </div>

        <div class="field" v-if="downloadMode === 'range'">
          <label>End Year</label>
          <select v-model="selectedEndYear" class="form-select">
            <option v-for="y in yearsList" :key="y" :value="y">
              {{ y }}
            </option>
          </select>
        </div>

        <button
          class="btn primary download-btn"
          :disabled="actionLoading"
          @click="triggerDownload(false)"
        >
          <DownloadCloud class="icon-xs" />
          <span>{{ actionLoading ? 'Ingesting...' : 'Start Ingestion' }}</span>
        </button>
      </div>
    </div>

    <!-- Universe Manager Drawer -->
    <UniverseManagerDrawer
      :is-open="isUniverseDrawerOpen"
      @close="isUniverseDrawerOpen = false"
      @updated="onUniverseUpdated"
    />
  </div>
</template>

<style scoped>
.data-manager-container {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.header-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: 0.25rem;
}

.header-title {
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.header-subtitle {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin-top: 0.2rem;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
}

.manage-btn {
  border-color: rgba(245, 158, 11, 0.4);
  color: #f59e0b;
  font-weight: 600;
}

.manage-btn:hover {
  background: rgba(245, 158, 11, 0.15);
  border-color: #f59e0b;
  color: #fbbf24;
}

/* Card */
.card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
}

/* Toolbar */
.coverage-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.asset-category-tabs {
  display: flex;
  gap: 0.4rem;
}

.category-tab {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  padding: 0.45rem 0.8rem;
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.category-tab.forex.active {
  background: var(--asset-forex-bg);
  border-color: var(--asset-forex);
  color: #fde047;
}

.category-tab.futures.active {
  background: var(--asset-futures-bg);
  border-color: var(--asset-futures);
  color: #d8b4fe;
}

.category-tab.equities.active {
  background: var(--asset-equity-bg);
  border-color: var(--asset-equity);
  color: #67e8f9;
}

.tf-selector {
  display: flex;
  background: #0f0f0f;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.2rem;
}

.tf-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  padding: 0.35rem 0.75rem;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 600;
  cursor: pointer;
}

.tf-btn.active {
  background: var(--accent);
  color: #fff;
}

/* Provider Bar */
.provider-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: #0d0d0d;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
  margin-bottom: 1rem;
  flex-wrap: wrap;
}

.provider-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
}

.provider-pills {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
}

.provider-pill {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  padding: 0.3rem 0.6rem;
  border-radius: 4px;
  font-size: 0.72rem;
  cursor: pointer;
  transition: all 0.15s;
}

.provider-pill.active {
  background: rgba(217, 119, 6, 0.15);
  border-color: var(--accent);
  color: var(--text-primary);
  font-weight: 600;
}

/* Matrix Table */
.matrix-container {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.75rem;
}

.matrix-table th {
  padding: 0.6rem 0.5rem;
  background: #111;
  color: var(--text-secondary);
  font-weight: 600;
  border-bottom: 1px solid var(--border);
  text-align: center;
}

.matrix-table th.inst-col {
  text-align: left;
  padding-left: 0.75rem;
  width: 140px;
}

.matrix-table th.spec-col {
  text-align: left;
  width: 240px;
}

.matrix-table td {
  padding: 0.5rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
}

.inst-cell {
  padding-left: 0.75rem !important;
}

.symbol-tag {
  display: inline-block;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  font-weight: 700;
  font-family: monospace;
}

.symbol-tag.forex {
  background: var(--asset-forex-bg);
  color: #fde047;
  border: 1px solid var(--asset-forex-border);
}

.symbol-tag.futures {
  background: var(--asset-futures-bg);
  color: #d8b4fe;
  border: 1px solid var(--asset-futures-border);
}

.symbol-tag.equities {
  background: var(--asset-equity-bg);
  color: #67e8f9;
  border: 1px solid var(--asset-equity-border);
}

.spec-cell {
  font-size: 0.7rem;
}

.matrix-cell {
  text-align: center;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin: 0 auto;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.status-indicator:hover {
  transform: scale(1.3);
}

.status-indicator.complete {
  background: #10b981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.6);
}

.status-indicator.partial {
  background: #f59e0b;
  box-shadow: 0 0 6px rgba(245, 158, 11, 0.6);
}

.status-indicator.missing {
  background: #262626;
}

.status-indicator.syncing {
  background: #38bdf8;
  animation: pulse 1s infinite;
}

/* Legend */
.matrix-legend {
  display: flex;
  align-items: center;
  gap: 1.25rem;
  margin-top: 1rem;
  font-size: 0.72rem;
  color: var(--text-secondary);
  flex-wrap: wrap;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.complete { background: #10b981; }
.dot.partial { background: #f59e0b; }
.dot.missing { background: #262626; }
.dot.syncing { background: #38bdf8; }

.spacer { flex-grow: 1; }
.legend-note { color: var(--text-muted); }

/* Download Form */
.form-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.form-header h3 {
  font-size: 1rem;
  font-weight: 600;
  margin: 0;
}

.section-subtitle {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.15rem;
}

.download-fields {
  display: flex;
  gap: 0.75rem;
  align-items: flex-end;
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.field label {
  font-size: 0.72rem;
  color: var(--text-secondary);
}

.form-select {
  background: #0f0f0f;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.45rem 0.65rem;
  font-size: 0.8rem;
  color: var(--text-primary);
  outline: none;
}

.form-select:focus {
  border-color: var(--accent);
}

.download-btn {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 1rem;
  font-size: 0.8rem;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
}

/* Active tasks */
.active-tasks-card {
  background: rgba(217, 119, 6, 0.08);
  border: 1px solid rgba(217, 119, 6, 0.3);
  border-radius: 8px;
  padding: 1rem;
}

.tasks-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.tasks-header h4 {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--accent);
  margin: 0;
}

.tasks-list {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  font-size: 0.75rem;
}

.task-name {
  font-weight: 700;
  font-family: monospace;
}

.task-status {
  color: var(--text-secondary);
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.8rem;
  font-size: 0.78rem;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn.primary {
  background: var(--accent);
  border: 1px solid var(--accent);
  color: #fff;
}

.btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
}

.btn.secondary {
  background: var(--bg-card);
  border: 1px solid var(--border);
  color: var(--text-primary);
}

.btn.secondary:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Icons */
.icon-sm { width: 16px; height: 16px; flex-shrink: 0; }
.icon-xs { width: 14px; height: 14px; flex-shrink: 0; }
.text-accent { color: var(--accent); }
.text-muted { color: var(--text-muted); }
.mt-4 { margin-top: 1rem; }
.mb-4 { margin-bottom: 1rem; }

.spinner-sm {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--accent);
  border-top-color: transparent;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
