<script setup lang="ts">
import { ref, computed, onUnmounted, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useControlPlane } from '@/composables/useControlPlane'
import { useStatusBar } from '@/composables/useStatusBar'

const route = useRoute()
const { getHistoricalDataStatus, downloadHistoricalData, deleteHistoricalData } = useControlPlane()
const { setStatus } = useStatusBar()

const dataTimeframe = ref<'h1' | 'm1'>('h1')
const dataStatus = ref<any[]>([])
const activeDownloads = ref<string[]>([])
const activeTasks = ref<any[]>([])
const loadError = ref<string | null>(null)
const infoMessage = ref<string | null>(null)
const actionLoading = ref(false)

const selectedPair = ref('eurusd')
const selectedYear = ref(new Date().getFullYear())
const selectedTf = ref<'h1' | 'm1'>('h1')
const downloadMode = ref<'single' | 'range' | 'all'>('single')
const selectedStartYear = ref(2006)
const selectedEndYear = ref(new Date().getFullYear())

const pairsList = ['eurusd', 'gbpusd', 'gbpjpy', 'usdcad', 'usdjpy', 'audusd', 'nzdusd', 'usdchf']
const yearsList = computed(() => {
  const current = new Date().getFullYear()
  const years = []
  for (let y = current; y >= 2006; y--) {
    years.push(y)
  }
  return years
})

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

const groupedStatus = computed(() => {
  const map: Record<string, Record<number, any>> = {}
  pairsList.forEach(p => {
    map[p] = {}
  })
  dataStatus.value.forEach(item => {
    const p = item.pair.toLowerCase()
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
    : `Starting ingestion for ${selectedPair.value.toUpperCase()} (${selectedTf.value.toUpperCase()})...`
  setStatus(infoMsg, 'info')
  try {
    dataTimeframe.value = selectedTf.value
    let params: any
    if (sync) {
      params = { syncMode: true, tf: selectedTf.value }
    } else {
      if (downloadMode.value === 'all') {
        params = {
          pair: selectedPair.value,
          startYear: 2006,
          endYear: new Date().getFullYear(),
          tf: selectedTf.value
        }
      } else if (downloadMode.value === 'range') {
        params = {
          pair: selectedPair.value,
          startYear: selectedStartYear.value,
          endYear: selectedEndYear.value,
          tf: selectedTf.value
        }
      } else {
        params = {
          pair: selectedPair.value,
          year: selectedYear.value,
          tf: selectedTf.value
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

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

let statusPollTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  refreshDataStatus()
  statusPollTimer = setInterval(refreshDataStatus, 5000)

  // Check for auto-play download redirect
  if (route.query.autoplay === 'true' && route.query.pair && route.query.year) {
    const pair = String(route.query.pair).toLowerCase()
    const yearSpec = String(route.query.year)
    const tf = String(route.query.tf || 'h1').toLowerCase()

    infoMessage.value = `ℹ️ No historical data was found for ${pair.toUpperCase().replace('_', '/')} (${yearSpec.toUpperCase()}). We have automatically started the ingestion process for you.`
    
    // Set form fields to match the requested download
    selectedPair.value = pair
    selectedTf.value = tf as any
    if (yearSpec === 'all') {
      downloadMode.value = 'all'
    } else if (yearSpec.includes('-')) {
      downloadMode.value = 'range'
      const parts = yearSpec.split('-')
      selectedStartYear.value = parseInt(parts[0]) || 2020
      selectedEndYear.value = parseInt(parts[1]) || 2025
    } else {
      downloadMode.value = 'single'
      selectedYear.value = parseInt(yearSpec) || new Date().getFullYear()
    }

    // Trigger the download automatically!
    try {
      actionLoading.value = true
      let params: any
      if (downloadMode.value === 'all') {
        params = {
          pair: selectedPair.value,
          startYear: 2006,
          endYear: new Date().getFullYear(),
          tf: selectedTf.value
        }
      } else if (downloadMode.value === 'range') {
        params = {
          pair: selectedPair.value,
          startYear: selectedStartYear.value,
          endYear: selectedEndYear.value,
          tf: selectedTf.value
        }
      } else {
        params = {
          pair: selectedPair.value,
          year: selectedYear.value,
          tf: selectedTf.value
        }
      }
      await downloadHistoricalData(params)
      refreshDataStatus()
    } catch (err: any) {
      loadError.value = err.message
    } finally {
      actionLoading.value = false
    }
  }
})

onUnmounted(() => {
  if (statusPollTimer) clearInterval(statusPollTimer)
})
</script>

<template>
  <div class="view">
    <div class="page-header">
      <div>
        <h1>Data Management</h1>
        <p class="subtitle">Ingest, sync, and inspect historical bars for backtesting</p>
      </div>
      <div class="page-actions">
        <button class="btn secondary" @click="refreshDataStatus">🔄 Refresh Status</button>
        <button
          class="btn primary"
          :disabled="actionLoading"
          @click="triggerDownload(true)"
        >
          ⚡ Sync Current Year
        </button>
      </div>
    </div>

    <div v-if="loadError" class="banner error">{{ loadError }}</div>
    <div v-if="infoMessage" class="banner info animate-fade-in">{{ infoMessage }}</div>

    <!-- Active Ingestion Processes -->
    <div v-if="activeTasks.length > 0" class="active-downloads-section">
      <h3>Active Ingestion Processes</h3>
      <div class="active-tasks-list">
        <div v-for="task in activeTasks" :key="task.key" class="task-progress-card">
          <div class="task-info">
            <span class="task-name">{{ task.key.toUpperCase() }}</span>
            <span class="task-action">{{ task.currentAction }}</span>
            <span class="task-pct">{{ task.progress }}%</span>
          </div>
          <div class="progress-bar-bg">
            <div class="progress-bar-fill" :style="{ width: task.progress + '%' }"></div>
          </div>
        </div>
      </div>
    </div>

    <div class="data-grid-layout">
      <!-- Matrix Status View -->
      <div class="matrix-card">
        <div class="matrix-card-header">
          <h3>Historical Data Coverage</h3>
          <div class="tf-selector">
            <button
              :class="['tf-btn', { active: dataTimeframe === 'h1' }]"
              @click="dataTimeframe = 'h1'; refreshDataStatus()"
            >
              H1 Data
            </button>
            <button
              :class="['tf-btn', { active: dataTimeframe === 'm1' }]"
              @click="dataTimeframe = 'm1'; refreshDataStatus()"
            >
              M1 Data
            </button>
          </div>
        </div>

        <div class="matrix-container">
          <table class="matrix-table">
            <thead>
              <tr>
                <th>Pair</th>
                <th v-for="year in yearsList" :key="year" class="year-header">
                  {{ year.toString().slice(2) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pair in pairsList" :key="pair">
                <td class="pair-name">{{ pair.toUpperCase().replace('_', '') }}</td>
                <td
                  v-for="year in yearsList"
                  :key="year"
                  class="matrix-cell"
                >
                  <div
                    v-if="groupedStatus[pair] && groupedStatus[pair][year]"
                    :class="[
                      'status-indicator',
                      {
                        complete: groupedStatus[pair][year].csvExists && groupedStatus[pair][year].barsExists,
                        partial: groupedStatus[pair][year].csvExists !== groupedStatus[pair][year].barsExists,
                        missing: !groupedStatus[pair][year].csvExists && !groupedStatus[pair][year].barsExists,
                        syncing: activeDownloads.includes(pair + '-' + year + '-' + dataTimeframe)
                      }
                    ]"
                    :title="`${pair.toUpperCase()} ${year} (${dataTimeframe.toUpperCase()})\nCSV: ${groupedStatus[pair][year].csvExists ? formatBytes(groupedStatus[pair][year].csvSize) : 'None'}\nBARS: ${groupedStatus[pair][year].barsExists ? formatBytes(groupedStatus[pair][year].barsSize) : 'None'}\nClick to Delete`"
                    @click="groupedStatus[pair][year].csvExists || groupedStatus[pair][year].barsExists ? triggerDelete(pair, year) : null"
                  ></div>
                  <div v-else class="status-indicator missing"></div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="matrix-legend">
          <div class="legend-item"><span class="dot complete"></span> Available</div>
          <div class="legend-item"><span class="dot partial"></span> Partial (CSV or Bars only)</div>
          <div class="legend-item"><span class="dot missing"></span> Missing</div>
          <div class="legend-item"><span class="dot syncing"></span> Syncing</div>
          <span style="flex-grow: 1;"></span>
          <span class="legend-note">* Hover cells for size detail, click to delete a dataset.</span>
        </div>
      </div>

      <!-- Download Specific Dataset Form -->
      <div class="download-form-section">
        <h3>Ingest Custom Dataset</h3>
        <p class="section-subtitle">Download historical prices directly from the broker API</p>
        
        <div class="download-fields">
          <div class="field">
            <label>Currency Pair</label>
            <select v-model="selectedPair">
              <option v-for="p in pairsList" :key="p" :value="p">
                {{ p.toUpperCase() }}
              </option>
            </select>
          </div>
          
          <div class="field">
            <label>Granularity</label>
            <select v-model="selectedTf">
              <option value="h1">H1</option>
              <option value="m1">M1</option>
            </select>
          </div>

          <div class="field">
            <label>Mode</label>
            <select v-model="downloadMode">
              <option value="single">Single Year</option>
              <option value="range">Year Range</option>
              <option value="all">All History</option>
            </select>
          </div>

          <div class="field" v-if="downloadMode === 'single'">
            <label>Year</label>
            <select v-model="selectedYear">
              <option v-for="y in yearsList" :key="y" :value="y">
                {{ y }}
              </option>
            </select>
          </div>

          <div class="field" v-if="downloadMode === 'range'">
            <label>Start Year</label>
            <select v-model="selectedStartYear">
              <option v-for="y in yearsList" :key="y" :value="y">
                {{ y }}
              </option>
            </select>
          </div>

          <div class="field" v-if="downloadMode === 'range'">
            <label>End Year</label>
            <select v-model="selectedEndYear">
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
            📥 Start Ingestion
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

h1 {
  font-size: 1.5rem;
  margin-bottom: 0.25rem;
}

.subtitle {
  color: var(--text-secondary);
  font-size: 0.875rem;
  margin-bottom: 0;
}

.page-actions {
  display: flex;
  gap: 0.75rem;
}

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1.5rem;
  font-size: 0.85rem;
}

.banner.error {
  background: #2d1212;
  color: #fca5a5;
  border: 1px solid #7f1d1d;
}

.btn {
  padding: 0.5rem 1rem;
  font-size: 0.8rem;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn.primary {
  background: var(--accent);
  border: 1px solid var(--accent);
  color: #000;
}

.btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.btn.secondary {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-primary);
}

.btn.secondary:hover {
  background: var(--bg-secondary);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Active Ingestion Processes */
.active-downloads-section {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
}

.active-downloads-section h3 {
  font-size: 0.95rem;
  margin-bottom: 1rem;
  color: var(--text-primary);
}

.active-tasks-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.task-progress-card {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.task-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.8rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.task-name {
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  color: var(--accent);
}

.task-action {
  color: var(--text-secondary);
}

.task-pct {
  font-weight: 600;
  font-family: 'JetBrains Mono', monospace;
}

.progress-bar-bg {
  width: 100%;
  height: 6px;
  background: #262626;
  border-radius: 3px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.data-grid-layout {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

/* Matrix Card */
.matrix-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.25rem;
}

.matrix-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.matrix-card-header h3 {
  font-size: 0.95rem;
  color: var(--text-primary);
  margin: 0;
}

.tf-selector {
  display: flex;
  background: var(--bg-primary);
  padding: 0.25rem;
  border-radius: 6px;
  border: 1px solid var(--border);
}

.tf-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  padding: 0.4rem 1rem;
  border-radius: 4px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.tf-btn.active {
  background: var(--bg-card);
  color: var(--text-primary);
}

/* Matrix Table */
.matrix-container {
  overflow-x: auto;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 8px;
  margin-bottom: 1rem;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8rem;
  text-align: center;
}

.matrix-table th,
.matrix-table td {
  padding: 0.6rem 0.4rem;
  border: 1px solid var(--border);
}

.matrix-table th {
  background: var(--bg-card);
  color: var(--text-secondary);
  font-weight: 600;
}

.year-header {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.75rem;
}

.pair-name {
  font-weight: 700;
  color: var(--text-primary);
  text-align: left;
  padding-left: 0.75rem;
  width: 90px;
}

.matrix-cell {
  width: 38px;
  vertical-align: middle;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin: 0 auto;
  transition: all 0.15s;
}

.status-indicator.complete {
  background: var(--success);
  box-shadow: 0 0 4px var(--success);
  cursor: pointer;
}

.status-indicator.complete:hover {
  transform: scale(1.3);
  background: var(--danger);
  box-shadow: 0 0 6px var(--danger);
}

.status-indicator.partial {
  background: var(--warning);
  box-shadow: 0 0 4px var(--warning);
  cursor: pointer;
}

.status-indicator.partial:hover {
  transform: scale(1.3);
  background: var(--danger);
  box-shadow: 0 0 6px var(--danger);
}

.status-indicator.missing {
  background: #262626;
}

.status-indicator.syncing {
  background: #3b82f6;
  animation: pulse 1s infinite;
  box-shadow: 0 0 6px #3b82f6;
}

.matrix-legend {
  display: flex;
  gap: 1.25rem;
  align-items: center;
  flex-wrap: wrap;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.legend-item .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.legend-item .dot.complete { background: var(--success); }
.legend-item .dot.partial { background: var(--warning); }
.legend-item .dot.missing { background: #262626; }
.legend-item .dot.syncing { background: #3b82f6; }

.legend-note {
  font-style: italic;
}

/* Download Form Section */
.download-form-section {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.25rem;
}

.download-form-section h3 {
  font-size: 0.95rem;
  margin-bottom: 0.25rem;
  color: var(--text-primary);
}

.section-subtitle {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin-bottom: 1.25rem;
}

.download-fields {
  display: flex;
  gap: 1.25rem;
  align-items: flex-end;
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.field label {
  font-size: 0.7rem;
  color: var(--text-secondary);
  text-transform: uppercase;
  font-weight: 600;
}

.field select {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 6px;
  padding: 0.45rem 1rem;
  font-size: 0.8rem;
  outline: none;
  min-width: 140px;
}

.download-btn {
  margin-left: auto;
  padding: 0.5rem 1.5rem;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
.banner.info {
  background: #0f1d2d;
  color: #93c5fd;
  border: 1px solid #1e3a5f;
}

.animate-fade-in {
  animation: fadeIn 0.3s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-5px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
