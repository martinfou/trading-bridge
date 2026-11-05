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

export interface ChartPartitionBand {
  startIndex: number
  endIndex: number
  oos: boolean
  label: string
}

const props = defineProps<{
  data: number[]
  periodStart?: string
  periodEnd?: string
  height?: number
  showTimeScale?: boolean
  valStartIndex?: number
  oosStartIndex?: number
  oosSplitPct?: number
  splitFolds?: number
  partitionBands?: ChartPartitionBand[]
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let lineSeries: ISeriesApi<'Line'> | null = null
let bandSeriesList: ISeriesApi<'Area'>[] = []
let drawdownSeries: ISeriesApi<'Histogram'> | null = null
let resizeObserver: ResizeObserver | null = null
let splitTimer: number | null = null
let lastTimes: number[] = []

interface RenderedBandBox {
  leftPx: number
  widthPx: number
  oos: boolean
  label: string
  startIndex: number
}

const renderedBands = ref<RenderedBandBox[]>([])
const plotWidth = ref<number>(0)
const maxDrawdownVal = ref<number>(0)

const ddMidLabel = computed(() => {
  const half = maxDrawdownVal.value / 2
  return `${half.toFixed(1)}%`
})

const ddMaxLabel = computed(() => {
  return `${maxDrawdownVal.value.toFixed(1)}%`
})

const effectiveBands = computed<ChartPartitionBand[]>(() => {
  const len = props.data.length
  if (len < 4) return []
  const maxIdx = len - 1
  if (props.partitionBands && props.partitionBands.length >= 2) {
    const sanitized: ChartPartitionBand[] = []
    for (let i = 0; i < props.partitionBands.length; i++) {
      const b = props.partitionBands[i]
      const start = i === 0 ? 0 : Math.min(maxIdx - 1, Math.max(0, b.startIndex))
      const end = i === props.partitionBands.length - 1 ? maxIdx : Math.min(maxIdx, Math.max(start + 1, b.endIndex))
      if (end > start) {
        sanitized.push({ startIndex: start, endIndex: end, oos: Boolean(b.oos), label: b.label || (b.oos ? 'OOS' : 'IS') })
      }
    }
    if (sanitized.length >= 2) {
      return sanitized
    }
  }
  const hasOos =
    typeof props.oosStartIndex === 'number' &&
    props.oosStartIndex > 0 &&
    props.oosStartIndex < len - 1
  if (!hasOos) return []

  const folds = typeof props.splitFolds === 'number' && props.splitFolds >= 1 ? props.splitFolds : 3
  const oosPct =
    typeof props.oosSplitPct === 'number' && props.oosSplitPct > 0 && props.oosSplitPct < 100
      ? Math.round(props.oosSplitPct)
      : Math.round(((len - 1 - (props.oosStartIndex ?? 0)) / (len - 1)) * 100)

  if (folds <= 1) {
    const splitIdx = Math.min(maxIdx - 1, Math.max(1, props.oosStartIndex ?? Math.round(maxIdx * (100 - oosPct) / 100)))
    return [
      { startIndex: 0, endIndex: splitIdx, oos: false, label: `IS (${100 - oosPct}%)` },
      { startIndex: splitIdx, endIndex: maxIdx, oos: true, label: `OOS (${oosPct}%)` },
    ]
  }

  const list: ChartPartitionBand[] = []
  for (let k = 0; k < folds; k++) {
    const cycleStart = Math.round((k / folds) * maxIdx)
    const cycleEnd = k === folds - 1 ? maxIdx : Math.round(((k + 1) / folds) * maxIdx)
    const cycleLen = Math.max(2, cycleEnd - cycleStart)
    const isEnd = Math.min(
      cycleEnd - 1,
      Math.max(cycleStart + 1, cycleStart + Math.round((cycleLen * (100 - oosPct)) / 100))
    )
    list.push({ startIndex: cycleStart, endIndex: isEnd, oos: false, label: `IS${k + 1}` })
    list.push({ startIndex: isEnd, endIndex: cycleEnd, oos: true, label: `OOS${k + 1}` })
  }
  return list
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

function indexToX(idx: number, times: number[], currentPlotWidth: number): number {
  const clampedIdx = Math.min(props.data.length - 1, Math.max(0, idx))
  if (clampedIdx === 0) return 0
  if (clampedIdx >= props.data.length - 1) return currentPlotWidth
  const t = times[clampedIdx]
  const coord = chart && t !== undefined ? chart.timeScale().timeToCoordinate(t as any) : null
  if (coord !== null && coord !== undefined) {
    return Math.max(0, Math.min(currentPlotWidth, Math.round(coord)))
  }
  return Math.round((clampedIdx / Math.max(1, props.data.length - 1)) * currentPlotWidth)
}

function updateSplitOverlay(times: number[]) {
  if (!chart || !container.value) {
    renderedBands.value = []
    return
  }
  const totalWidth = container.value.clientWidth
  const rightScaleWidth = chart.priceScale('right').width() || 62
  if (totalWidth <= rightScaleWidth + 20) {
    renderedBands.value = []
    return
  }
  const currentPlotWidth = totalWidth - rightScaleWidth
  plotWidth.value = currentPlotWidth

  const bands = effectiveBands.value
  if (!bands.length) {
    renderedBands.value = []
    return
  }

  const boxes: RenderedBandBox[] = []
  for (let i = 0; i < bands.length; i++) {
    const b = bands[i]
    const leftPx = i === 0 ? 0 : indexToX(b.startIndex, times, currentPlotWidth)
    const rightPx = i === bands.length - 1 ? currentPlotWidth : indexToX(b.endIndex, times, currentPlotWidth)
    boxes.push({
      leftPx,
      widthPx: Math.max(0, rightPx - leftPx),
      oos: b.oos,
      label: b.label,
      startIndex: b.startIndex,
    })
  }
  renderedBands.value = boxes
}

async function render() {
  await nextTick()
  if (!container.value || !props.data.length) return

  if (chart) {
    chart.remove()
    chart = null
    lineSeries = null
    bandSeriesList = []
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
        top: 0.15,
        bottom: 0.30,
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
  lastTimes = times

  const bands = effectiveBands.value
  if (bands.length >= 2) {
    bandSeriesList = []
    for (let bIdx = 0; bIdx < bands.length; bIdx++) {
      const band = bands[bIdx]
      const isLastBand = bIdx === bands.length - 1
      const s = chart.addSeries(AreaSeries, {
        lineColor: band.oos ? '#10b981' : '#38bdf8',
        topColor: band.oos ? 'rgba(16, 185, 129, 0.30)' : 'rgba(56, 189, 248, 0.24)',
        bottomColor: band.oos ? 'rgba(16, 185, 129, 0.02)' : 'rgba(56, 189, 248, 0.02)',
        lineWidth: 2,
        crosshairMarkerVisible: true,
        crosshairMarkerRadius: 4,
        priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
        lastValueVisible: isLastBand,
        priceLineVisible: isLastBand,
        priceLineColor: band.oos ? 'rgba(16, 185, 129, 0.45)' : 'rgba(56, 189, 248, 0.45)',
      })
      const start = Math.max(0, Math.min(props.data.length - 1, band.startIndex))
      const end = Math.max(start, Math.min(props.data.length - 1, band.endIndex))
      const sliceData: LineData[] = props.data.slice(start, end + 1).map((v, offset) => ({
        time: times[start + offset] as any,
        value: v,
      }))
      s.setData(sliceData)
      bandSeriesList.push(s)
    }
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

  let peak = props.data[0] || 0
  let minDd = 0
  const drawdownData = props.data.map((v, i) => {
    if (v > peak) {
      peak = v
    }
    const dd = peak === 0 ? 0 : ((v - peak) / peak) * 100
    if (dd < minDd) {
      minDd = dd
    }
    return {
      time: times[i] as any,
      value: dd,
      color: 'rgba(239, 68, 68, 0.72)',
    }
  })

  const effectiveMinDd = minDd < -0.01 ? minDd : -1.0
  maxDrawdownVal.value = effectiveMinDd

  drawdownSeries = chart.addSeries(HistogramSeries, {
    color: '#ef4444',
    priceFormat: {
      type: 'custom',
      minMove: 0.01,
      formatter: (val: number) => `${val.toFixed(1)}%`,
    },
    priceScaleId: 'drawdown-scale',
    lastValueVisible: false,
    priceLineVisible: false,
    autoscaleInfoProvider: () => ({
      priceRange: {
        minValue: effectiveMinDd,
        maxValue: 0,
      },
    }),
  })

  chart.priceScale('drawdown-scale').applyOptions({
    scaleMargins: {
      top: 0.73,
      bottom: 0.04,
    },
    visible: false,
  })

  drawdownSeries.setData(drawdownData)
  chart.timeScale().fitContent()

  // Update the IS/OOS visual split overlay after fitContent
  if (splitTimer !== null) {
    clearTimeout(splitTimer)
    splitTimer = null
  }
  requestAnimationFrame(() => {
    updateSplitOverlay(times)
    splitTimer = window.setTimeout(() => updateSplitOverlay(times), 60)
  })

  chart.timeScale().subscribeVisibleLogicalRangeChange(() => {
    updateSplitOverlay(times)
  })
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ height: props.height ?? 200, width: container.value.clientWidth })
    chart.timeScale().fitContent()
    const times = lastTimes.length === props.data.length
      ? lastTimes
      : generateTimes(props.data.length, props.periodStart, props.periodEnd)
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
watch(() => props.valStartIndex, render)
watch(() => props.oosStartIndex, render)
watch(() => props.oosSplitPct, render)
watch(() => props.splitFolds, render)
watch(() => props.partitionBands, render, { deep: true })
watch(() => props.periodStart, render)
watch(() => props.periodEnd, render)
watch(() => props.height, resize)

onUnmounted(() => {
  if (splitTimer !== null) {
    clearTimeout(splitTimer)
    splitTimer = null
  }
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.remove()
  chart = null
  lineSeries = null
  bandSeriesList = []
  drawdownSeries = null
})
</script>

<template>
  <div class="equity-chart-wrapper" :style="{ height: (height ?? 200) + 'px' }">
    <div ref="container" class="equity-chart-canvas" :style="{ height: (height ?? 200) + 'px' }"></div>

    <!-- Visual Alternating In-Sample vs Out-of-Sample Bands Overlay -->
    <div
      v-if="renderedBands.length >= 2 && plotWidth > 0"
      class="is-oos-chart-overlay"
      :style="{ width: plotWidth + 'px' }"
    >
      <template v-for="(band, idx) in renderedBands" :key="idx">
        <!-- Vertical Boundary Line between alternating bands -->
        <div
          v-if="idx > 0"
          :class="['band-split-line', band.oos ? 'to-oos' : 'to-is']"
          :style="{ left: band.leftPx + 'px' }"
        >
          <span
            v-if="renderedBands.length === 2 && band.oos"
            class="oos-split-tag"
          >
            OOS SPLIT #{{ band.startIndex }}
          </span>
        </div>

        <!-- Band Shaded Zone & Top Pill -->
        <div
          :class="['chart-zone', band.oos ? 'oos-zone' : 'is-zone', { 'multi-band': renderedBands.length > 2 }]"
          :style="{ left: band.leftPx + 'px', width: band.widthPx + 'px' }"
        >
          <div
            v-if="band.widthPx >= 36"
            :class="['zone-header-pill', band.oos ? 'oos-pill' : 'is-pill', { compact: renderedBands.length > 2 }]"
          >
            <span :class="['zone-dot', band.oos ? 'oos-dot' : 'is-dot']"></span>
            <span>{{ band.label }}</span>
          </div>
        </div>
      </template>
    </div>

    <!-- Dedicated Drawdown % Scale & Sub-Pane Grid (73% -> 96% height) -->
    <div
      v-if="plotWidth > 0"
      class="dd-scale-overlay"
      :style="{ width: plotWidth + 'px' }"
    >
      <div class="dd-grid-line dd-zero-line">
        <span class="dd-scale-tag dd-zero-tag">DD 0.0%</span>
      </div>
      <div class="dd-grid-line dd-mid-line">
        <span class="dd-scale-tag">{{ ddMidLabel }}</span>
      </div>
      <div class="dd-grid-line dd-max-line">
        <span class="dd-scale-tag dd-max-tag">Max {{ ddMaxLabel }}</span>
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
  justify-content: center;
  padding: 7px 4px;
  box-sizing: border-box;
}

.chart-zone.is-zone {
  background: linear-gradient(
    180deg,
    rgba(56, 189, 248, 0.09) 0%,
    rgba(56, 189, 248, 0.025) 48%,
    rgba(56, 189, 248, 0.0) 100%
  );
}

.chart-zone.oos-zone {
  background: linear-gradient(
    180deg,
    rgba(16, 185, 129, 0.14) 0%,
    rgba(16, 185, 129, 0.045) 55%,
    rgba(16, 185, 129, 0.01) 100%
  );
}

.zone-header-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border-radius: 5px;
  font-size: 0.62rem;
  font-weight: 800;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  backdrop-filter: blur(4px);
  white-space: nowrap;
  max-width: 100%;
  box-sizing: border-box;
}

.zone-header-pill.compact {
  padding: 2px 5px;
  font-size: 0.58rem;
}

.zone-header-pill.is-pill {
  background: rgba(15, 23, 42, 0.84);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.38);
}

.zone-header-pill.oos-pill {
  background: rgba(6, 78, 59, 0.78);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.48);
}

.zone-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.zone-dot.is-dot {
  background: #38bdf8;
  box-shadow: 0 0 6px rgba(56, 189, 248, 0.8);
}

.zone-dot.oos-dot {
  background: #10b981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.8);
}

.band-split-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  z-index: 2;
}

.band-split-line.to-oos {
  border-left: 1.5px dashed rgba(16, 185, 129, 0.78);
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.25);
}

.band-split-line.to-is {
  border-left: 1.5px dashed rgba(56, 189, 248, 0.65);
}

.oos-split-tag {
  position: absolute;
  bottom: 29%;
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

.dd-scale-overlay {
  position: absolute;
  top: 73%;
  bottom: 4%;
  left: 0;
  pointer-events: none;
  z-index: 5;
}

.dd-grid-line {
  position: absolute;
  left: 0;
  right: 0;
  height: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding-right: 6px;
}

.dd-zero-line {
  top: 0;
  border-top: 1px solid rgba(239, 68, 68, 0.32);
}

.dd-mid-line {
  top: 50%;
  border-top: 1px dashed rgba(239, 68, 68, 0.18);
}

.dd-max-line {
  bottom: 0;
  border-top: 1px dashed rgba(239, 68, 68, 0.38);
}

.dd-scale-tag {
  transform: translateY(-50%);
  background: rgba(15, 23, 42, 0.86);
  color: #fca5a5;
  border: 1px solid rgba(239, 68, 68, 0.32);
  font-size: 0.58rem;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  padding: 1px 5px;
  border-radius: 3px;
  line-height: 1.25;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.dd-zero-tag {
  color: #94a3b8;
  border-color: rgba(148, 163, 184, 0.28);
}

.dd-max-tag {
  color: #f87171;
  background: rgba(69, 10, 10, 0.78);
  border-color: rgba(239, 68, 68, 0.52);
}
</style>
