<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { createChart, type IChartApi, type ISeriesApi, type LineData, ColorType, LineSeries, LineStyle } from 'lightweight-charts'

const props = defineProps<{
  baseline: number[]
  paths: number[][]
  periodStart?: string
  periodEnd?: string
  height?: number
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let baselineSeries: ISeriesApi<'Line'> | null = null

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
  if (!container.value || !props.baseline.length) return

  if (chart) {
    chart.remove()
    chart = null
    baselineSeries = null
  }

  chart = createChart(container.value, {
    height: props.height ?? 350,
    layout: {
      background: { type: ColorType.Solid, color: '#1a1a1a' },
      textColor: '#888',
    },
    grid: {
      vertLines: { color: '#222' },
      horzLines: { color: '#222' },
    },
    timeScale: {
      visible: true,
      borderColor: '#333',
      timeVisible: false,
    },
    rightPriceScale: {
      borderColor: '#333',
      scaleMargins: {
        top: 0.1,
        bottom: 0.1,
      },
    },
    crosshair: {
      vertLine: { color: '#555', labelBackgroundColor: '#333' },
      horzLine: { color: '#555', labelBackgroundColor: '#333' },
    },
    handleScroll: true,
    handleScale: true,
  })

  const times = generateTimes(props.baseline.length, props.periodStart, props.periodEnd)

  const mapToLineData = (arr: number[]): LineData[] =>
    arr.map((v, i) => ({
      time: times[i] as any,
      value: v,
    }))

  if (!chart) return

  // Add the 100 simulated paths first so baseline is drawn on top
  const p5Index = 4
  const p50Index = 49
  const p95Index = 94

  props.paths.forEach((path, idx) => {
    let color = 'rgba(59, 130, 246, 0.12)' // Semi-transparent blue for the background paths
    let lineWidth: 1 | 2 | 3 | 4 | 5 = 1
    let lineStyle = LineStyle.Solid
    let crosshairMarkerVisible = false

    if (idx === p5Index) {
      color = 'rgba(239, 68, 68, 0.8)' // Red P5
      lineWidth = 2
      lineStyle = LineStyle.Dashed
    } else if (idx === p50Index) {
      color = 'rgba(59, 130, 246, 0.8)' // Blue P50
      lineWidth = 2
      lineStyle = LineStyle.Dashed
    } else if (idx === p95Index) {
      color = 'rgba(16, 185, 129, 0.8)' // Green P95
      lineWidth = 2
      lineStyle = LineStyle.Dashed
    }

    const series = chart!.addSeries(LineSeries, {
      color,
      lineWidth,
      lineStyle,
      crosshairMarkerVisible,
      priceLineVisible: false,
      lastValueVisible: false,
    })
    series.setData(mapToLineData(path))
  })

  // Baseline Series (Orange, Solid, Thick) on top
  baselineSeries = chart.addSeries(LineSeries, {
    color: '#d97706',
    lineWidth: 3,
    crosshairMarkerVisible: true,
    crosshairMarkerRadius: 4,
    priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
    lastValueVisible: true,
    priceLineVisible: true,
    priceLineColor: '#444',
  })

  baselineSeries.setData(mapToLineData(props.baseline))

  chart.timeScale().fitContent()
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ height: props.height ?? 350, width: container.value.clientWidth })
  }
}

onMounted(render)

watch(() => props.baseline, render, { deep: true })
watch(() => props.paths, render, { deep: true })
watch(() => props.periodStart, render)
watch(() => props.periodEnd, render)
watch(() => props.height, resize)

onUnmounted(() => {
  chart?.remove()
  chart = null
  baselineSeries = null
})
</script>

<template>
  <div ref="container" class="monte-carlo-chart" :style="{ height: (height ?? 350) + 'px' }"></div>
</template>

<style scoped>
.monte-carlo-chart {
  border-radius: 8px;
  overflow: hidden;
}
</style>
