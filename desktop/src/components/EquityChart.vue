<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  ColorType,
  LineSeries,
  AreaSeries,
  HistogramSeries,
} from 'lightweight-charts'

const props = defineProps<{
  data: number[]
  periodStart?: string
  periodEnd?: string
  height?: number
  showTimeScale?: boolean
  oosStartIndex?: number
  oosSplitPct?: number
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let lineSeries: ISeriesApi<'Line'> | null = null
let isAreaSeries: ISeriesApi<'Area'> | null = null
let oosAreaSeries: ISeriesApi<'Area'> | null = null
let drawdownSeries: ISeriesApi<'Histogram'> | null = null
let resizeObserver: ResizeObserver | null = null

const splitX = ref<number | null>(null)
const plotWidth = ref<number>(0)

const hasOosSplit = computed(() => {
  return (
    typeof props.oosStartIndex === 'number' &&
    props.oosStartIndex > 0 &&
    props.oosStartIndex < props.data.length - 1
  )
})

const inSamplePctLabel = computed(() => {
  if (typeof props.oosSplitPct === 'number' && props.oosSplitPct > 0) {
    return `${100 - props.oosSplitPct}%`
  }
  if (hasOosSplit.value && props.data.length > 1) {
    return `${Math.round(((props.oosStartIndex ?? 0) / (props.data.length - 1)) * 100)}%`
  }
  return '70%'
})

const outOfSamplePctLabel = computed(() => {
  if (typeof props.oosSplitPct === 'number' && props.oosSplitPct > 0) {
    return `${props.oosSplitPct}%`
  }
  if (hasOosSplit.value && props.data.length > 1) {
    return `${100 - Math.round(((props.oosStartIndex ?? 0) / (props.data.length - 1)) * 100)}%`
  }
  return '30%'
})

const oosZoneWidth = computed(() => {
  if (splitX.value === null || plotWidth.value <= 0) return 0
  return Math.max(0, plotWidth.value - splitX.value)
})

const oosHeaderText = computed(() => {
  if (oosZoneWidth.value >= 215) {
    return `OUT-OF-SAMPLE (BLIND · ${outOfSamplePctLabel.value})`
  }
  if (oosZoneWidth.value >= 115) {
    return `OOS · ${outOfSamplePctLabel.value} BLIND`
  }
  return `OOS ${outOfSamplePctLabel.value}`
})

function generateTimes(length: number, periodStart?: string, periodEnd?: string): number[] {
  const times: number[] = []

  let startMs = periodStart ? new Date(periodStart).getTime() : null
  let endMs = periodEnd ? new Date(periodEnd).getTime() : null

  if (!startMs || !endMs || isNaN(startMs) || isNaN(endMs)) {
    endMs = Date.now()
    startMs = endMs - 365 * 24 * 60 * 60 * 1000
  }

  const stepMs = (endMs - startMs) / Math.max(1, length - 1)
  for (let i = 0; i < length; i++) {
    times.push(Math.round((startMs + i * stepMs) / 1000))
  }
  return times
}

function updateSplitOverlay(times: number[]) {
  if (!chart || !container.value || !hasOosSplit.value) {
    splitX.value = null
    return
  }
  const totalWidth = container.value.clientWidth
  const rightScaleWidth = chart.priceScale('right').width() || 62
  const currentPlotWidth = Math.max(100, totalWidth - rightScaleWidth)
  plotWidth.value = currentPlotWidth

  const oosIdx = Math.min(props.data.length - 1, Math.max(1, props.oosStartIndex ?? 0))
  const splitTime = times[oosIdx]
  const coord = splitTime !== undefined ? chart.timeScale().timeToCoordinate(splitTime as any) : null

  if (coord !== null && coord !== undefined && coord > 0 && coord < currentPlotWidth) {
    splitX.value = Math.round(coord)
  } else {
    // Proportional fallback before canvas layout settles
    splitX.value = Math.round((oosIdx / Math.max(1, props.data.length - 1)) * currentPlotWidth)
  }
}

async function render() {
  await nextTick()
  if (!container.value || !props.data.length) return

  if (chart) {
    chart.remove()
    chart = null
    lineSeries = null
    isAreaSeries = null
    oosAreaSeries = null
    drawdownSeries = null
  }

  chart = createChart(container.value, {
    height: props.height ?? 200,
    layout: {
      background: { type: ColorType.Solid, color: '#0d1322' },
      textColor: '#94a3b8',
    },
    grid: {
      vertLines: { color: 'rgba(30, 41, 59, 0.55)' },
      horzLines: { color: 'rgba(30, 41, 59, 0.55)' },
    },
    timeScale: {
      visible: props.showTimeScale ?? false,
      borderColor: '#1e293b',
      timeVisible: false,
    },
    rightPriceScale: {
      borderColor: '#1e293b',
      scaleMargins: {
        top: 0.16,
        bottom: 0.25,
      },
    },
    crosshair: {
      vertLine: { color: '#64748b', labelBackgroundColor: '#1e293b' },
      horzLine: { color: '#64748b', labelBackgroundColor: '#1e293b' },
    },
    handleScroll: props.showTimeScale ?? false,
    handleScale: props.showTimeScale ?? false,
  })

  const times = generateTimes(props.data.length, props.periodStart, props.periodEnd)

  if (hasOosSplit.value) {
    const oosIdx = Math.min(props.data.length - 1, Math.max(1, props.oosStartIndex ?? 0))

    // In-Sample Series (Training section — Sky Blue)
    isAreaSeries = chart.addSeries(AreaSeries, {
      lineColor: '#38bdf8',
      topColor: 'rgba(56, 189, 248, 0.24)',
      bottomColor: 'rgba(56, 189, 248, 0.02)',
      lineWidth: 2,
      crosshairMarkerVisible: true,
      crosshairMarkerRadius: 4,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      lastValueVisible: false,
      priceLineVisible: false,
    })

    // Out-of-Sample Series (Blind Validation section — Emerald Green)
    oosAreaSeries = chart.addSeries(AreaSeries, {
      lineColor: '#10b981',
      topColor: 'rgba(16, 185, 129, 0.30)',
      bottomColor: 'rgba(16, 185, 129, 0.02)',
      lineWidth: 2,
      crosshairMarkerVisible: true,
      crosshairMarkerRadius: 4,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: 'rgba(16, 185, 129, 0.45)',
    })

    // Include oosIdx in both so the transition at the boundary is 100% continuous
    const isData: LineData[] = props.data.slice(0, oosIdx + 1).map((v, i) => ({
      time: times[i] as any,
      value: v,
    }))
    const oosData: LineData[] = props.data.slice(oosIdx).map((v, idxOffset) => ({
      time: times[oosIdx + idxOffset] as any,
      value: v,
    }))

    isAreaSeries.setData(isData)
    oosAreaSeries.setData(oosData)
  } else {
    lineSeries = chart.addSeries(LineSeries, {
      color: '#d97706',
      lineWidth: 2,
      crosshairMarkerVisible: true,
      crosshairMarkerRadius: 4,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: '#444',
    })

    const lineData: LineData[] = props.data.map((v, i) => ({
      time: times[i] as any,
      value: v,
    }))
    lineSeries.setData(lineData)
  }

  drawdownSeries = chart.addSeries(HistogramSeries, {
    color: '#ef4444',
    priceFormat: { type: 'percent', precision: 2 },
    priceScaleId: 'drawdown-scale',
  })

  chart.priceScale('drawdown-scale').applyOptions({
    scaleMargins: {
      top: 0.8,
      bottom: 0,
    },
    visible: false,
  })

  let peak = props.data[0] || 0
  const drawdownData = props.data.map((v, i) => {
    if (v > peak) {
      peak = v
    }
    const dd = peak === 0 ? 0 : ((v - peak) / peak) * 100
    return {
      time: times[i] as any,
      value: dd,
      color: 'rgba(239, 68, 68, 0.72)',
    }
  })

  drawdownSeries.setData(drawdownData)
  chart.timeScale().fitContent()

  // Update the IS/OOS visual split overlay after fitContent
  requestAnimationFrame(() => {
    updateSplitOverlay(times)
    setTimeout(() => updateSplitOverlay(times), 60)
  })

  chart.timeScale().subscribeVisibleLogicalRangeChange(() => {
    updateSplitOverlay(times)
  })
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ height: props.height ?? 200, width: container.value.clientWidth })
    chart.timeScale().fitContent()
    const times = generateTimes(props.data.length, props.periodStart, props.periodEnd)
    requestAnimationFrame(() => updateSplitOverlay(times))
  }
}

onMounted(() => {
  render()
  if (container.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => resize())
    resizeObserver.observe(container.value)
  }
})

watch(() => props.data, render, { deep: true })
watch(() => props.oosStartIndex, render)
watch(() => props.oosSplitPct, render)
watch(() => props.periodStart, render)
watch(() => props.periodEnd, render)
watch(() => props.height, resize)

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.remove()
  chart = null
  lineSeries = null
  isAreaSeries = null
  oosAreaSeries = null
  drawdownSeries = null
})
</script>

<template>
  <div class="equity-chart-wrapper" :style="{ height: (height ?? 200) + 'px' }">
    <div ref="container" class="equity-chart-canvas" :style="{ height: (height ?? 200) + 'px' }"></div>

    <!-- Visual In-Sample vs Out-of-Sample Section Overlay -->
    <div
      v-if="hasOosSplit && splitX !== null && plotWidth > 0"
      class="is-oos-chart-overlay"
      :style="{ width: plotWidth + 'px' }"
    >
      <!-- In-Sample Zone (Left) -->
      <div class="chart-zone is-zone" :style="{ width: splitX + 'px' }">
        <div class="zone-header-pill is-pill">
          <span class="zone-dot is-dot"></span>
          <span>IN-SAMPLE (TRAIN · {{ inSamplePctLabel }})</span>
        </div>
      </div>

      <!-- Vertical Split Boundary Line -->
      <div class="oos-split-line" :style="{ left: splitX + 'px' }">
        <span class="oos-split-tag">OOS SPLIT #{{ oosStartIndex }}</span>
      </div>

      <!-- Out-of-Sample Zone (Right) -->
      <div
        class="chart-zone oos-zone"
        :style="{ left: splitX + 'px', width: oosZoneWidth + 'px' }"
      >
        <div class="zone-header-pill oos-pill">
          <span class="zone-dot oos-dot"></span>
          <span>{{ oosHeaderText }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.equity-chart-wrapper {
  position: relative;
  border-radius: 9px;
  overflow: hidden;
  border: 1px solid #1e293b;
}

.equity-chart-canvas {
  width: 100%;
}

.is-oos-chart-overlay {
  position: absolute;
  top: 0;
  left: 0;
  bottom: 0;
  pointer-events: none;
  z-index: 4;
  overflow: hidden;
}

.chart-zone {
  position: absolute;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: flex-start;
  padding: 8px 10px;
}

.chart-zone.is-zone {
  left: 0;
  background: linear-gradient(
    180deg,
    rgba(56, 189, 248, 0.09) 0%,
    rgba(56, 189, 248, 0.02) 45%,
    rgba(56, 189, 248, 0.0) 100%
  );
}

.chart-zone.oos-zone {
  justify-content: center;
  padding: 8px 6px;
  box-sizing: border-box;
  background: linear-gradient(
    180deg,
    rgba(16, 185, 129, 0.13) 0%,
    rgba(16, 185, 129, 0.04) 55%,
    rgba(16, 185, 129, 0.01) 100%
  );
}

.zone-header-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 5px;
  font-size: 0.64rem;
  font-weight: 800;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  backdrop-filter: blur(4px);
  white-space: nowrap;
  max-width: 100%;
  box-sizing: border-box;
}

.zone-header-pill.is-pill {
  background: rgba(15, 23, 42, 0.82);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.38);
}

.zone-header-pill.oos-pill {
  background: rgba(6, 78, 59, 0.75);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.45);
}

.zone-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.zone-dot.is-dot {
  background: #38bdf8;
  box-shadow: 0 0 6px rgba(56, 189, 248, 0.8);
}

.zone-dot.oos-dot {
  background: #10b981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.8);
}

.oos-split-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  border-left: 2px dashed rgba(16, 185, 129, 0.85);
  box-shadow: 0 0 10px rgba(16, 185, 129, 0.35);
}

.oos-split-tag {
  position: absolute;
  bottom: 24px;
  left: 0;
  transform: translateX(-50%);
  background: rgba(6, 78, 59, 0.94);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.55);
  font-size: 0.61rem;
  font-weight: 800;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  padding: 2px 6px;
  border-radius: 4px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}
</style>
