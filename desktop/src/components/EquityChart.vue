<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { createChart, type IChartApi, type ISeriesApi, type LineData, ColorType, LineSeries } from 'lightweight-charts'

const props = defineProps<{
  data: number[]
  height?: number
  showTimeScale?: boolean
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let lineSeries: ISeriesApi<'Line'> | null = null

function render() {
  if (!container.value || !props.data.length) return

  if (!chart) {
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

    chart.timeScale().fitContent()
  }

  const lineData: LineData[] = props.data.map((v, i) => ({
    time: i as any,
    value: v,
  }))

  lineSeries?.setData(lineData)
  chart?.timeScale().fitContent()
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ height: props.height ?? 200, width: container.value.clientWidth })
  }
}

onMounted(render)

watch(() => props.data, render, { deep: true })
watch(() => props.height, resize)

onUnmounted(() => {
  chart?.remove()
  chart = null
  lineSeries = null
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
