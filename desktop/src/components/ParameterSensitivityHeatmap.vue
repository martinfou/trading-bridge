<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import { Sliders, RefreshCw } from '@lucide/vue'

const props = defineProps<{
  strategyId: string
  symbol?: string
}>()

const emit = defineEmits<{
  (e: 'view-run', runId: string): void
}>()

const { getHeatmap, listBacktests } = useControlPlane()

// State
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const heatmapData = ref<any[]>([])
const xParam = ref('')
const yParam = ref('')
const metric = ref('sharpeRatio')

// Available params scanned from runs
const availableParams = ref<string[]>([])

// Fetch runs to inspect available parameter keys
async function loadAvailableParams() {
  if (!props.strategyId) return
  loading.value = true
  try {
    const res = await listBacktests({
      strategyId: props.strategyId,
      symbol: props.symbol || undefined,
      limit: 10
    })
    if (res.items && res.items.length > 0) {
      const firstRun = res.items[0]
      try {
        const params = JSON.parse(firstRun.parameters)
        availableParams.value = Object.keys(params).sort()
        
        // Auto-select first two parameters if available
        if (availableParams.value.length >= 2) {
          xParam.value = availableParams.value[0]
          yParam.value = availableParams.value[1]
        } else if (availableParams.value.length === 1) {
          xParam.value = availableParams.value[0]
          yParam.value = availableParams.value[0]
        }
      } catch (e) {
        console.error('Failed to parse parameters JSON:', e)
      }
    }
  } catch (err: any) {
    console.error('Failed to inspect strategy params:', err)
  } finally {
    loading.value = false
  }
}

// Fetch Heatmap Grid
async function fetchHeatmapData() {
  if (!props.strategyId || !xParam.value || !yParam.value) {
    heatmapData.value = []
    return
  }
  loading.value = true
  errorMsg.value = null
  try {
    const res = await getHeatmap({
      strategyId: props.strategyId,
      symbol: props.symbol || undefined,
      xParam: xParam.value,
      yParam: yParam.value,
      metric: metric.value
    })
    heatmapData.value = res.data || []
  } catch (err: any) {
    errorMsg.value = err.message || 'Failed to compute sensitivity heatmap.'
  } finally {
    loading.value = false
  }
}

// Watchers
watch(() => props.strategyId, async () => {
  xParam.value = ''
  yParam.value = ''
  availableParams.value = []
  await loadAvailableParams()
  fetchHeatmapData()
})

watch(() => props.symbol, fetchHeatmapData)
watch([xParam, yParam, metric], fetchHeatmapData)

onMounted(async () => {
  await loadAvailableParams()
  fetchHeatmapData()
})

// Process grid layout
const gridLayout = computed(() => {
  if (heatmapData.value.length === 0) return null

  // Extract unique sorted X and Y coordinates
  const uniqueX = Array.from(new Set(heatmapData.value.map(d => d.x))).sort((a, b) => Number(a) - Number(b))
  const uniqueY = Array.from(new Set(heatmapData.value.map(d => d.y))).sort((a, b) => Number(b) - Number(a)) // vertical axis: high to low

  // Map coordinate to cell
  const cellsMap: Record<string, any> = {}
  let minVal = Infinity
  let maxVal = -Infinity

  for (const d of heatmapData.value) {
    const key = `${d.x}_${d.y}`
    cellsMap[key] = d
    if (d.value < minVal) minVal = d.value
    if (d.value > maxVal) maxVal = d.value
  }

  return {
    columns: uniqueX,
    rows: uniqueY,
    cells: cellsMap,
    min: minVal === Infinity ? 0 : minVal,
    max: maxVal === -Infinity ? 0 : maxVal
  }
})

// Color helper
function getCellStyle(val: number, min: number, max: number) {
  if (max === min) {
    return {
      backgroundColor: 'rgba(217, 119, 6, 0.4)',
      color: '#fff'
    }
  }
  const ratio = (val - min) / (max - min)
  
  // Custom lightness and opacity based on performance ratio
  const opacity = 0.15 + ratio * 0.85
  const color = ratio > 0.6 ? '#000' : '#fff'
  return {
    backgroundColor: `rgba(217, 119, 6, ${opacity})`,
    color: color
  }
}
</script>

<template>
  <div class="heatmap-container">
    <div class="heatmap-controls">
      <div class="control-group">
        <label>Variable X (Cols)</label>
        <select v-model="xParam" :disabled="availableParams.length === 0">
          <option v-for="p in availableParams" :key="p" :value="p">{{ p }}</option>
        </select>
      </div>

      <div class="control-group">
        <label>Variable Y (Rows)</label>
        <select v-model="yParam" :disabled="availableParams.length === 0">
          <option v-for="p in availableParams" :key="p" :value="p">{{ p }}</option>
        </select>
      </div>

      <div class="control-group">
        <label>Performance Metric</label>
        <select v-model="metric">
          <option value="sharpeRatio">Sharpe Ratio</option>
          <option value="profitFactor">Profit Factor</option>
          <option value="totalReturnPct">Total Return %</option>
          <option value="maxDrawdownPct">Max Drawdown %</option>
          <option value="winRatePct">Win Rate %</option>
        </select>
      </div>
    </div>

    <!-- Error message -->
    <div v-if="errorMsg" class="banner error-banner mt-4">
      <span>{{ errorMsg }}</span>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Computing sensitivity grid...</p>
    </div>

    <!-- Heatmap Grid -->
    <div v-else-if="gridLayout" class="heatmap-grid-wrapper mt-4">
      <div class="grid-table-container">
        <table class="grid-table">
          <thead>
            <tr>
              <!-- Y variable label cell -->
              <th class="axis-label corner-cell">
                <span class="y-lbl">{{ yParam }}</span>
                <span class="divider">/</span>
                <span class="x-lbl">{{ xParam }}</span>
              </th>
              <th v-for="x in gridLayout.columns" :key="x" class="axis-header text-center">
                {{ x }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="y in gridLayout.rows" :key="y">
              <td class="axis-header font-semibold">{{ y }}</td>
              <td
                v-for="x in gridLayout.columns"
                :key="x"
                class="heatmap-cell"
                :style="gridLayout.cells[`${x}_${y}`] ? getCellStyle(gridLayout.cells[`${x}_${y}`].value, gridLayout.min, gridLayout.max) : {}"
                @click="gridLayout.cells[`${x}_${y}`] && emit('view-run', gridLayout.cells[`${x}_${y}`].runId)"
              >
                <div v-if="gridLayout.cells[`${x}_${y}`]" class="cell-content">
                  <span class="cell-value">{{ gridLayout.cells[`${x}_${y}`].value.toFixed(2) }}</span>
                  <!-- Hover tooltip -->
                  <div class="cell-tooltip">
                    <div class="tooltip-title">{{ strategyId }}</div>
                    <div class="tooltip-row">
                      <span>{{ xParam }}:</span> <strong>{{ x }}</strong>
                    </div>
                    <div class="tooltip-row">
                      <span>{{ yParam }}:</span> <strong>{{ y }}</strong>
                    </div>
                    <div class="tooltip-row highlight">
                      <span>{{ metric }}:</span> <strong>{{ gridLayout.cells[`${x}_${y}`].value.toFixed(4) }}</strong>
                    </div>
                    <div class="tooltip-footer">Click to view details</div>
                  </div>
                </div>
                <div v-else class="empty-cell">—</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <div class="legend-bar mt-4">
        <span>Min: {{ gridLayout.min.toFixed(2) }}</span>
        <div class="legend-gradient"></div>
        <span>Max: {{ gridLayout.max.toFixed(2) }}</span>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else class="empty-state mt-4">
      <Sliders class="empty-icon" />
      <p>Select a strategy and run some backtests with different parameter variables to generate a heatmap.</p>
    </div>
  </div>
</template>

<style scoped>
.heatmap-container {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
}

.heatmap-controls {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.control-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 150px;
  flex-grow: 1;
}

.control-group label {
  font-size: 0.65rem;
  text-transform: uppercase;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
}

.control-group select {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.45rem 0.6rem;
  color: var(--text-primary);
  font-size: 0.8rem;
  outline: none;
}

.control-group select:focus {
  border-color: var(--accent);
}

.mt-4 { margin-top: 1rem; }

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
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
  padding: 3rem 0;
  color: var(--text-secondary);
}

.spinner {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-state {
  text-align: center;
  padding: 3rem 2rem;
  color: var(--text-secondary);
}

.empty-icon {
  width: 2rem;
  height: 2rem;
  margin-bottom: 0.75rem;
  stroke-width: 1.5;
}

/* Heatmap Grid Table */
.heatmap-grid-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.grid-table-container {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.grid-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.775rem;
}

.grid-table th, 
.grid-table td {
  padding: 0.75rem;
  border: 1px solid var(--border);
}

.axis-header {
  background: rgba(255, 255, 255, 0.01);
  color: var(--text-secondary);
  font-size: 0.75rem;
}

.corner-cell {
  position: relative;
  background: rgba(255, 255, 255, 0.02);
  min-width: 120px;
}

.corner-cell .y-lbl {
  position: absolute;
  top: 0.25rem;
  left: 0.5rem;
  color: var(--text-secondary);
}

.corner-cell .x-lbl {
  position: absolute;
  bottom: 0.25rem;
  right: 0.5rem;
  color: var(--text-secondary);
}

.corner-cell .divider {
  color: var(--border);
}

.text-center {
  text-align: center;
}

.font-semibold {
  font-weight: 600;
}

/* Cells */
.heatmap-cell {
  cursor: pointer;
  position: relative;
  text-align: center;
  transition: transform 0.15s;
}

.heatmap-cell:hover {
  transform: scale(1.05);
  z-index: 10;
  box-shadow: 0 0 10px rgba(0, 0, 0, 0.5);
}

.cell-content {
  position: relative;
  width: 100%;
  height: 100%;
}

.cell-value {
  font-weight: 600;
  font-family: 'JetBrains Mono', monospace;
}

.empty-cell {
  color: rgba(255, 255, 255, 0.15);
}

/* Tooltip on cell hover */
.cell-tooltip {
  visibility: hidden;
  position: absolute;
  bottom: 125%;
  left: 50%;
  transform: translateX(-50%);
  background: #141414;
  border: 1px solid var(--border);
  box-shadow: 0 5px 15px rgba(0,0,0,0.6);
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  width: 160px;
  z-index: 20;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  text-align: left;
}

.heatmap-cell:hover .cell-tooltip {
  visibility: visible;
  opacity: 1;
}

.tooltip-title {
  font-weight: 600;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.25rem;
  margin-bottom: 0.25rem;
  font-size: 0.7rem;
  color: var(--accent);
}

.tooltip-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.675rem;
  margin-bottom: 0.15rem;
}

.tooltip-row.highlight {
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  padding-top: 0.25rem;
  margin-top: 0.25rem;
}

.tooltip-row.highlight strong {
  color: var(--accent);
}

.tooltip-footer {
  font-size: 0.6rem;
  color: var(--text-secondary);
  text-align: center;
  margin-top: 0.4rem;
}

/* Legend Bar */
.legend-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  width: 100%;
  max-width: 400px;
}

.legend-gradient {
  flex-grow: 1;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(to right, rgba(217, 119, 6, 0.15), rgba(217, 119, 6, 1));
  border: 1px solid var(--border);
}
</style>
