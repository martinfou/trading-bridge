<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { createChart, type IChartApi, type ISeriesApi, type LineData, ColorType, LineSeries, HistogramSeries } from 'lightweight-charts'

const props = defineProps<{
  data: number[]
  periodStart?: string
  periodEnd?: string
  height?: number
  showTimeScale?: boolean
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let lineSeries: ISeriesApi<'Line'> | null = null
let drawdownSeries: ISeriesApi<'Histogram'> | null = null

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

async function render() {
  await nextTick()
  if (!container.value || !props.data.length) return

  if (chart) {
    chart.remove()
    chart = null
    lineSeries = null
    drawdownSeries = null
  }

  chart = createChart(container.value, {
    height: props.height ?? 200,
    layout: {
      background: { type: ColorType.Solid, color: '#1a1a1a' },
      textColor: '#888',
    },
    grid: {
      vertLines: { color: '#222' },
      horzLines: { color: '#222' },
    },
    timeScale: {
      visible: props.showTimeScale ?? false,
      borderColor: '#333',
      timeVisible: false,
    },
    rightPriceScale: {
      borderColor: '#333',
      scaleMargins: {
        top: 0.1,
        bottom: 0.25,
      },
    },
    crosshair: {
      vertLine: { color: '#555', labelBackgroundColor: '#333' },
      horzLine: { color: '#555', labelBackgroundColor: '#333' },
    },
    handleScroll: props.showTimeScale ?? false,
    handleScale: props.showTimeScale ?? false,
  })

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

  const times = generateTimes(props.data.length, props.periodStart, props.periodEnd)

  const lineData: LineData[] = props.data.map((v, i) => ({
    time: times[i] as any,
    value: v,
  }))

  let peak = props.data[0] || 0
  const drawdownData = props.data.map((v, i) => {
    if (v > peak) {
      peak = v
    }
    const dd = peak === 0 ? 0 : ((v - peak) / peak) * 100
    return {
      time: times[i] as any,
      value: dd,
      color: '#ef4444',
    }
  })

  if (lineSeries) {
    lineSeries.setData(lineData)
  }
  if (drawdownSeries) {
    drawdownSeries.setData(drawdownData)
  }
  if (chart) {
    chart.timeScale().fitContent()
  }
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ height: props.height ?? 200, width: container.value.clientWidth })
  }
}

onMounted(render)

watch(() => props.data, render, { deep: true })
watch(() => props.periodStart, render)
watch(() => props.periodEnd, render)
watch(() => props.height, resize)

onUnmounted(() => {
  chart?.remove()
  chart = null
  lineSeries = null
  drawdownSeries = null
})
</script>

<template>
  <div ref="container" class="equity-chart" :style="{ height: (height ?? 200) + 'px' }"></div>
</template>

<style scoped>
.equity-chart {
  border-radius: 8px;
  overflow: hidden;
}
</style>
