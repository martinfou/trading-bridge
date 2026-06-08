<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import { GitBranch, RefreshCw, Info } from '@lucide/vue'

const props = defineProps<{
  strategyId: string
  symbol?: string
}>()

const emit = defineEmits<{
  (e: 'view-run', runId: string): void
}>()

const { getPareto } = useControlPlane()

// State
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const rawData = ref<any | null>(null)
const metricX = ref('maxDrawdownPct')
const metricY = ref('totalReturnPct')

// Fetch Pareto Data
async function fetchParetoData() {
  if (!props.strategyId) return
  loading.value = true
  errorMsg.value = null
  try {
    rawData.value = await getPareto({
      strategyId: props.strategyId,
      symbol: props.symbol || undefined,
      metricX: metricX.value,
      metricY: metricY.value
    })
  } catch (err: any) {
    errorMsg.value = err.message || 'Failed to compute Pareto frontier.'
  } finally {
    loading.value = false
  }
}

// Watchers
watch(() => props.strategyId, fetchParetoData)
watch(() => props.symbol, fetchParetoData)
watch([metricX, metricY], fetchParetoData)

onMounted(fetchParetoData)

// Metric extractor helpers
function getMetricValue(run: any, metric: string): number {
  if (!run) return 0
  switch (metric.toLowerCase()) {
    case 'maxdrawdownpct':
    case 'drawdown':
      return run.maxDrawdownPct || 0
    case 'totalreturnpct':
    case 'return':
      return run.totalReturnPct || 0
    case 'sharperatio':
    case 'sharpe':
      return run.sharpeRatio || 0
    case 'profitfactor':
    case 'pf':
      return run.profitFactor || 0
    case 'totalpnl':
    case 'pnl':
      return run.totalPnl || 0
    case 'totaltrades':
    case 'trades':
      return run.totalTrades || 0
    case 'winratepct':
    case 'winrate':
      return run.winRatePct || 0
    default:
      return 0
  }
}

// Metric Labels
const metricLabels: Record<string, string> = {
  maxDrawdownPct: 'Max Drawdown %',
  totalReturnPct: 'Total Return %',
  sharpeRatio: 'Sharpe Ratio',
  profitFactor: 'Profit Factor',
  totalPnl: 'Total PnL ($)',
  totalTrades: 'Total Trades',
  winRatePct: 'Win Rate %'
}

// Hover State for tooltip
const hoveredPoint = ref<any | null>(null)
const tooltipX = ref(0)
const tooltipY = ref(0)

// SVG Plot Configurations
const svgWidth = ref(560)
const svgHeight = ref(380)
const margins = { top: 25, right: 30, bottom: 45, left: 55 }

const plotWidth = computed(() => svgWidth.value - margins.left - margins.right)
const plotHeight = computed(() => svgHeight.value - margins.top - margins.bottom)

// Process Scales and Coordinates
const chartData = computed(() => {
  if (!rawData.value || !rawData.value.runs || rawData.value.runs.length === 0) return null

  const allRuns = rawData.value.runs
  const frontier = rawData.value.frontier || []

  // Compute Bounds
  let minX = Infinity, maxX = -Infinity
  let minY = Infinity, maxY = -Infinity

  for (const r of allRuns) {
    const vx = getMetricValue(r, metricX.value)
    const vy = getMetricValue(r, metricY.value)
    if (vx < minX) minX = vx
    if (vx > maxX) maxX = vx
    if (vy < minY) minY = vy
    if (vy > maxY) maxY = vy
  }

  // Handle singular scale limits
  if (minX === maxX) { minX -= 1; maxX += 1 }
  if (minY === maxY) { minY -= 1; maxY += 1 }

  // Add 10% padding to bounds for better aesthetics
  const padX = (maxX - minX) * 0.05
  const padY = (maxY - minY) * 0.05
  minX -= padX
  maxX += padX
  minY -= padY
  maxY += padY

  // Scale functions
  const scaleX = (val: number) => {
    return margins.left + ((val - minX) / (maxX - minX)) * plotWidth.value
  }
  const scaleY = (val: number) => {
    // Invert Y-axis for SVG space
    return margins.top + (1 - (val - minY) / (maxY - minY)) * plotHeight.value
  }

  // Map runs
  const points = allRuns.map((r: any) => {
    const vx = getMetricValue(r, metricX.value)
    const vy = getMetricValue(r, metricY.value)
    const isFrontier = frontier.some((f: any) => f.runId === r.runId)
    return {
      runId: r.runId,
      strategyId: r.strategyId,
      symbol: r.symbol,
      vx,
      vy,
      cx: scaleX(vx),
      cy: scaleY(vy),
      isFrontier,
      raw: r
    }
  })

  // Sort frontier to draw line cleanly
  const frontierPoints = points
    .filter((p: any) => p.isFrontier)
    // Draw from smallest X to largest X.
    // If X is drawdown (smaller is better) and Y is return (larger is better),
    // sorting by X alphabetically builds a continuous frontier envelope curve.
    .sort((a: any, b: any) => a.vx - b.vx)

  // Generate SVG path for frontier line
  let frontierLinePath = ''
  if (frontierPoints.length >= 2) {
    frontierLinePath = frontierPoints.map((p: any, i: number) => `${i === 0 ? 'M' : 'L'} ${p.cx} ${p.cy}`).join(' ')
  }

  // Generate ticks for Y axis
  const yTicksCount = 5
  const yTicks = Array.from({ length: yTicksCount }).map((_, i) => {
    const val = minY + (i * (maxY - minY)) / (yTicksCount - 1)
    return { val, cy: scaleY(val) }
  })

  // Generate ticks for X axis
  const xTicksCount = 5
  const xTicks = Array.from({ length: xTicksCount }).map((_, i) => {
    const val = minX + (i * (maxX - minX)) / (xTicksCount - 1)
    return { val, cx: scaleX(val) }
  })

  return {
    points,
    frontierPoints,
    frontierLinePath,
    xTicks,
    yTicks,
    minX, maxX, minY, maxY
  }
})

// Tooltip triggers
function showTooltip(event: MouseEvent, pt: any) {
  hoveredPoint.value = pt
  // Position tooltip relative to container
  const rect = (event.currentTarget as SVGElement).getBoundingClientRect()
  const parentRect = (event.currentTarget as SVGElement).parentElement?.getBoundingClientRect()
  if (parentRect) {
    tooltipX.value = rect.left - parentRect.left + rect.width / 2
    tooltipY.value = rect.top - parentRect.top - 10
  }
}

function hideTooltip() {
  hoveredPoint.value = null
}

function parseParameters(paramsJson: string): Record<string, any> {
  try {
    return JSON.parse(paramsJson)
  } catch (e) {
    return {}
  }
}
</script>

<template>
  <div class="pareto-container">
    <div class="pareto-controls">
      <div class="control-group">
        <label>Risk Metric (X-Axis)</label>
        <select v-model="metricX">
          <option value="maxDrawdownPct">Max Drawdown %</option>
          <option value="totalTrades">Total Trades</option>
        </select>
      </div>

      <div class="control-group">
        <label>Reward Metric (Y-Axis)</label>
        <select v-model="metricY">
          <option value="totalReturnPct">Total Return %</option>
          <option value="sharpeRatio">Sharpe Ratio</option>
          <option value="profitFactor">Profit Factor</option>
          <option value="totalPnl">Total PnL ($)</option>
        </select>
      </div>
    </div>

    <!-- Info message -->
    <div class="info-note mt-2">
      <Info class="info-icon" />
      <span>
        The Pareto frontier connects the optimal risk/reward runs. No other backtests are strictly better in both axes.
      </span>
    </div>

    <!-- Error message -->
    <div v-if="errorMsg" class="banner error-banner mt-4">
      <span>{{ errorMsg }}</span>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Computing Pareto frontier...</p>
    </div>

    <!-- Pareto SVG Chart -->
    <div v-else-if="chartData" class="chart-wrapper mt-4">
      <svg
        :width="svgWidth"
        :height="svgHeight"
        class="pareto-svg"
      >
        <!-- Gridlines -->
        <g class="grid-lines">
          <!-- Y-axis horizontal grids -->
          <line
            v-for="tick in chartData.yTicks"
            :key="'grid-y-' + tick.val"
            :x1="margins.left"
            :y1="tick.cy"
            :x2="svgWidth - margins.right"
            :y2="tick.cy"
            stroke="#1c1c1c"
            stroke-dasharray="2 2"
          />
          <!-- X-axis vertical grids -->
          <line
            v-for="tick in chartData.xTicks"
            :key="'grid-x-' + tick.val"
            :x1="tick.cx"
            :y1="margins.top"
            :x2="tick.cx"
            :y2="svgHeight - margins.bottom"
            stroke="#1c1c1c"
            stroke-dasharray="2 2"
          />
        </g>

        <!-- Axes -->
        <g class="axes">
          <!-- X Axis Line -->
          <line
            :x1="margins.left"
            :y1="svgHeight - margins.bottom"
            :x2="svgWidth - margins.right"
            :y2="svgHeight - margins.bottom"
            stroke="#333"
            stroke-width="1"
          />
          <!-- Y Axis Line -->
          <line
            :x1="margins.left"
            :y1="margins.top"
            :x2="margins.left"
            :y2="svgHeight - margins.bottom"
            stroke="#333"
            stroke-width="1"
          />
        </g>

        <!-- Axis Labels -->
        <g class="labels">
          <!-- X-axis Ticks -->
          <text
            v-for="tick in chartData.xTicks"
            :key="'lbl-x-' + tick.val"
            :x="tick.cx"
            :y="svgHeight - margins.bottom + 18"
            text-anchor="middle"
            fill="#888"
            font-size="9"
            font-family="monospace"
          >
            {{ tick.val.toFixed(1) }}
          </text>
          <!-- Y-axis Ticks -->
          <text
            v-for="tick in chartData.yTicks"
            :key="'lbl-y-' + tick.val"
            :x="margins.left - 8"
            :y="tick.cy + 3"
            text-anchor="end"
            fill="#888"
            font-size="9"
            font-family="monospace"
          >
            {{ tick.val.toFixed(1) }}
          </text>

          <!-- Axis Titles -->
          <text
            :x="margins.left + plotWidth / 2"
            :y="svgHeight - 10"
            text-anchor="middle"
            fill="#aaa"
            font-size="11"
            font-weight="600"
          >
            {{ metricLabels[metricX] }}
          </text>
          <text
            :x="-margins.top - plotHeight / 2"
            :y="15"
            text-anchor="middle"
            fill="#aaa"
            font-size="11"
            font-weight="600"
            transform="rotate(-90)"
          >
            {{ metricLabels[metricY] }}
          </text>
        </g>

        <!-- Frontier Connecting Line -->
        <path
          v-if="chartData.frontierLinePath"
          :d="chartData.frontierLinePath"
          fill="none"
          stroke="rgba(217, 119, 6, 0.4)"
          stroke-width="2"
          stroke-dasharray="3 3"
          class="frontier-path"
        />

        <!-- Scatter Points -->
        <g class="points">
          <circle
            v-for="pt in chartData.points"
            :key="pt.runId"
            :cx="pt.cx"
            :cy="pt.cy"
            :r="pt.isFrontier ? 6 : 4"
            :class="['scatter-point', pt.isFrontier ? 'frontier-point' : 'dominated-point']"
            @mouseenter="showTooltip($event, pt)"
            @mouseleave="hideTooltip"
            @click="emit('view-run', pt.runId)"
          />
        </g>
      </svg>

      <!-- SVG Tooltip -->
      <div
        v-if="hoveredPoint"
        class="chart-tooltip"
        :style="{ left: tooltipX + 'px', top: tooltipY + 'px' }"
      >
        <div class="tt-title">Run Details ({{ hoveredPoint.runId.slice(0, 8) }})</div>
        <div class="tt-body">
          <div class="tt-row">
            <span>Symbol:</span> <strong>{{ hoveredPoint.symbol }}</strong>
          </div>
          <div class="tt-row highlight">
            <span>{{ metricLabels[metricX] }}:</span>
            <strong>{{ hoveredPoint.vx.toFixed(4) }}</strong>
          </div>
          <div class="tt-row highlight">
            <span>{{ metricLabels[metricY] }}:</span>
            <strong>{{ hoveredPoint.vy.toFixed(4) }}</strong>
          </div>
          
          <div class="tt-params mt-1">
            <div class="tt-params-title">Parameters:</div>
            <div class="tt-params-grid">
              <span
                v-for="(val, name) in parseParameters(hoveredPoint.raw.parameters)"
                :key="name"
                class="tt-param-tag"
              >
                {{ name }}: {{ val }}
              </span>
            </div>
          </div>
        </div>
        <div class="tt-footer">Click to view full analytics</div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else class="empty-state mt-4">
      <GitBranch class="empty-icon" />
      <p>Select a strategy and run some backtests to compute and plot the Pareto frontier.</p>
    </div>
  </div>
</template>

<style scoped>
.pareto-container {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 1.25rem;
  position: relative;
}

.pareto-controls {
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

.info-note {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.725rem;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.02);
  padding: 0.4rem 0.6rem;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.04);
}

.info-icon {
  width: 0.9rem;
  height: 0.9rem;
  color: var(--accent);
  flex-shrink: 0;
}

.mt-2 { margin-top: 0.5rem; }
.mt-4 { margin-top: 1rem; }
.mt-6 { margin-top: 1.5rem; }

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
  padding: 4rem 0;
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

/* SVG Chart wrapper */
.chart-wrapper {
  position: relative;
  display: flex;
  justify-content: center;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem;
}

.pareto-svg {
  background: transparent;
  user-select: none;
}

/* Scatter points styling */
.scatter-point {
  cursor: pointer;
  transition: all 0.15s ease-in-out;
}

.dominated-point {
  fill: rgba(255, 255, 255, 0.18);
  stroke: rgba(255, 255, 255, 0.05);
  stroke-width: 1;
}

.dominated-point:hover {
  fill: #fff;
  r: 6px;
}

.frontier-point {
  fill: var(--accent);
  stroke: #fff;
  stroke-width: 1.5;
  filter: drop-shadow(0 0 4px var(--accent));
}

.frontier-point:hover {
  fill: var(--accent-hover);
  r: 8px;
}

/* Tooltip inside SVG container */
.chart-tooltip {
  position: absolute;
  transform: translate(-50%, -100%);
  background: #141414;
  border: 1px solid var(--border);
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.75);
  border-radius: 6px;
  padding: 0.6rem 0.85rem;
  width: 220px;
  z-index: 50;
  pointer-events: none;
  text-align: left;
}

.tt-title {
  font-size: 0.725rem;
  font-weight: 600;
  color: var(--accent);
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.25rem;
  margin-bottom: 0.35rem;
}

.tt-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.675rem;
  margin-bottom: 0.15rem;
  color: var(--text-primary);
}

.tt-row.highlight {
  border-top: 1px solid rgba(255, 255, 255, 0.03);
  padding-top: 0.2rem;
  margin-top: 0.2rem;
}

.tt-row.highlight strong {
  color: var(--accent-hover);
}

.tt-params-title {
  font-size: 0.6rem;
  color: var(--text-secondary);
  font-weight: 600;
  margin-bottom: 0.15rem;
  text-transform: uppercase;
}

.tt-params-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.tt-param-tag {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  font-size: 0.6rem;
  padding: 0.05rem 0.25rem;
  border-radius: 3px;
  color: #ccc;
}

.tt-footer {
  font-size: 0.6rem;
  color: var(--text-secondary);
  text-align: center;
  margin-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  padding-top: 0.25rem;
}
</style>
