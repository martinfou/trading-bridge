<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { createChart, type IChartApi, type ISeriesApi, ColorType, CandlestickSeries, createSeriesMarkers } from 'lightweight-charts'
import type { Bar, Trade } from '@/types/control-plane'

const props = defineProps<{
  bars: Bar[]
  trades: Trade[]
  height?: number
}>()

const container = ref<HTMLDivElement>()
let chart: IChartApi | null = null
let candlestickSeries: ISeriesApi<'Candlestick'> | null = null

function render() {
  if (!container.value || !props.bars.length) return

  // Clean old chart if exists
  if (chart) {
    chart.remove()
    chart = null
    candlestickSeries = null
  }

  // Create Chart
  chart = createChart(container.value, {
    height: props.height ?? 400,
    layout: {
      background: { type: ColorType.Solid, color: '#111317' },
      textColor: '#9CA3AF',
    },
    grid: {
      vertLines: { color: '#1F2937' },
      horzLines: { color: '#1F2937' },
    },
    timeScale: {
      visible: true,
      borderColor: '#374151',
      timeVisible: true,
      secondsVisible: false,
    },
    rightPriceScale: {
      borderColor: '#374151',
      autoScale: true,
    },
    crosshair: {
      vertLine: { color: '#4B5563', labelBackgroundColor: '#1F2937' },
      horzLine: { color: '#4B5563', labelBackgroundColor: '#1F2937' },
    },
    handleScroll: true,
    handleScale: true,
  })

  // Add Candlestick Series
  candlestickSeries = chart.addSeries(CandlestickSeries, {
    upColor: '#10B981',
    downColor: '#EF4444',
    borderUpColor: '#10B981',
    borderDownColor: '#EF4444',
    wickUpColor: '#10B981',
    wickDownColor: '#EF4444',
  })

  // Map bars data
  const chartBars = props.bars.map(b => ({
    time: Math.floor(new Date(b.time).getTime() / 1000) as any,
    open: b.open,
    high: b.high,
    low: b.low,
    close: b.close,
  }))

  // Sort bars by time (lightweight-charts requirement)
  chartBars.sort((a, b) => (a.time as number) - (b.time as number))
  candlestickSeries.setData(chartBars)

  // Map trades to markers
  const barTimes = chartBars.map(b => b.time as number)
  const markers: any[] = []

  function findClosestBarTime(tradeTimeStr: string): number {
    const target = Math.floor(new Date(tradeTimeStr).getTime() / 1000)
    if (barTimes.length === 0) return target
    let closest = barTimes[0]
    let minDiff = Math.abs(closest - target)
    for (const t of barTimes) {
      const diff = Math.abs(t - target)
      if (diff < minDiff) {
        minDiff = diff
        closest = t
      }
    }
    return closest
  }

  props.trades.forEach((trade, idx) => {
    // Aligns trades to closest bar
    const entryTime = findClosestBarTime(trade.entryTime)
    const exitTime = findClosestBarTime(trade.exitTime)
    const isLong = trade.side === 'BUY'

    // Entry Marker
    markers.push({
      time: entryTime,
      position: isLong ? 'belowBar' : 'aboveBar',
      color: isLong ? '#10B981' : '#EF4444',
      shape: isLong ? 'arrowUp' : 'arrowDown',
      text: `${isLong ? 'Buy' : 'Sell'} #${idx + 1}`,
    })

    // Exit Marker
    markers.push({
      time: exitTime,
      position: isLong ? 'aboveBar' : 'belowBar',
      color: isLong ? '#EF4444' : '#10B981',
      shape: isLong ? 'arrowDown' : 'arrowUp',
      text: `Exit #${idx + 1}`,
    })
  })

  console.log('[TradeChart] Trades count:', props.trades.length, 'Bars count:', props.bars.length, 'Generated markers:', markers.length)

  // Sort markers by time
  markers.sort((a, b) => a.time - b.time)

  // Deduplicate markers on exact same timestamp (shift slightly if needed or combine text)
  const dedupedMarkers: any[] = []
  const markersByTime = new Map<number, any[]>()
  markers.forEach(m => {
    if (!markersByTime.has(m.time)) {
      markersByTime.set(m.time, [])
    }
    markersByTime.get(m.time)!.push(m)
  })

  markersByTime.forEach((list, time) => {
    if (list.length === 1) {
      dedupedMarkers.push(list[0])
    } else {
      // Combine multiple markers on the same bar
      const first = list[0]
      const combinedText = list.map(m => m.text).join(' & ')
      dedupedMarkers.push({
        time: first.time,
        position: first.position,
        color: first.color,
        shape: first.shape,
        text: combinedText,
      })
    }
  })
  dedupedMarkers.sort((a, b) => a.time - b.time)

  if (candlestickSeries) {
    console.log('[TradeChart] Setting markers:', dedupedMarkers);
    createSeriesMarkers(candlestickSeries, dedupedMarkers);
  }
  if (chart) {
    chart.timeScale().fitContent()
  }
}

function resize() {
  if (chart && container.value) {
    chart.applyOptions({ width: container.value.clientWidth })
  }
}

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  if (chart) {
    chart.remove()
    chart = null
  }
})

watch(() => props.bars, async () => {
  await nextTick()
  render()
}, { deep: true })

watch(() => props.trades, async () => {
  await nextTick()
  render()
}, { deep: true })
</script>

<template>
  <div class="trade-chart-container">
    <div ref="container" class="trade-chart"></div>
    <div class="legend">
      <div class="legend-item"><span class="dot buy"></span>Buy Entry</div>
      <div class="legend-item"><span class="dot sell"></span>Sell Entry</div>
    </div>
  </div>
</template>

<style scoped>
.trade-chart-container {
  background: #111317;
  border: 1px solid #1F2937;
  border-radius: 10px;
  overflow: hidden;
  position: relative;
}

.trade-chart {
  width: 100%;
}

.legend {
  display: flex;
  gap: 1.5rem;
  padding: 0.5rem 1rem;
  background: #15181f;
  border-top: 1px solid #1F2937;
  font-size: 0.75rem;
  color: #9CA3AF;
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

.dot.buy {
  background: #10B981;
}

.dot.sell {
  background: #EF4444;
}
</style>
