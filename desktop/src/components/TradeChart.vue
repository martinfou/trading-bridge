<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick, computed } from 'vue'
import { createChart, type IChartApi, type ISeriesApi, ColorType, CandlestickSeries, createSeriesMarkers, LineStyle } from 'lightweight-charts'
import type { Bar, Trade } from '@/types/control-plane'

const props = defineProps<{
  bars: Bar[]
  trades: Trade[]
  positions?: any[]
  height?: number
}>()

const emit = defineEmits<{
  (e: 'loadMoreBars', oldestTime: string): void
}>()

const container = ref<HTMLDivElement>()
const timezone = ref<'local' | 'utc'>('local')
const selectedLineOption = ref<string>('auto')

let chart: IChartApi | null = null
let candlestickSeries: ISeriesApi<'Candlestick'> | null = null
let entryLine: any = null
let stopLossLine: any = null
let takeProfitLine: any = null

const lineOptions = computed(() => {
  const options = [
    { value: 'auto', label: 'Auto (Actif / Dernier)' },
    { value: 'none', label: 'Aucun' }
  ]
  if (props.positions && props.positions.length > 0) {
    options.push({ value: 'active_pos', label: 'Position active' })
  }
  props.trades.forEach((trade, idx) => {
    options.push({
      value: `trade_${idx}`,
      label: `Trade #${idx + 1} (${trade.side === 'BUY' ? 'Achat' : 'Vente'})`
    })
  })
  return options
})

function formatUTC(timestamp: number, showTime = true): string {
  const date = new Date(timestamp * 1000)
  const day = date.getUTCDate().toString().padStart(2, '0')
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const month = months[date.getUTCMonth()]
  if (!showTime) {
    return `${day} ${month}`
  }
  const hour = date.getUTCHours().toString().padStart(2, '0')
  const min = date.getUTCMinutes().toString().padStart(2, '0')
  return `${day} ${month} ${hour}:${min}`
}

function formatLocal(timestamp: number, showTime = true): string {
  const date = new Date(timestamp * 1000)
  const day = date.getDate().toString().padStart(2, '0')
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const month = months[date.getMonth()]
  if (!showTime) {
    return `${day} ${month}`
  }
  const hour = date.getHours().toString().padStart(2, '0')
  const min = date.getMinutes().toString().padStart(2, '0')
  return `${day} ${month} ${hour}:${min}`
}

function updatePriceLines() {
  if (!candlestickSeries) return

  // Clean old lines
  if (entryLine) {
    candlestickSeries.removePriceLine(entryLine)
    entryLine = null
  }
  if (stopLossLine) {
    candlestickSeries.removePriceLine(stopLossLine)
    stopLossLine = null
  }
  if (takeProfitLine) {
    candlestickSeries.removePriceLine(takeProfitLine)
    takeProfitLine = null
  }

  let entry: number | null = null
  let sl: number | null = null
  let tp: number | null = null
  let side: 'BUY' | 'SELL' | null = null
  let label = ''

  if (selectedLineOption.value === 'none') {
    return
  }

  if (selectedLineOption.value === 'auto') {
    if (props.positions && props.positions.length > 0) {
      const pos = props.positions[0]
      entry = pos.entryPrice
      if (pos.stopLoss && pos.stopLoss > 0) sl = pos.stopLoss
      if (pos.takeProfit && pos.takeProfit > 0) tp = pos.takeProfit
      side = pos.side
      label = 'Position Active'
    } else if (props.trades && props.trades.length > 0) {
      const lastTrade = props.trades[props.trades.length - 1]
      entry = lastTrade.entryPrice
      if (lastTrade.stopLoss && lastTrade.stopLoss > 0) sl = lastTrade.stopLoss
      if (lastTrade.takeProfit && lastTrade.takeProfit > 0) tp = lastTrade.takeProfit
      side = lastTrade.side
      label = `Dernier Trade (#${props.trades.length})`
    }
  } else if (selectedLineOption.value === 'active_pos') {
    if (props.positions && props.positions.length > 0) {
      const pos = props.positions[0]
      entry = pos.entryPrice
      if (pos.stopLoss && pos.stopLoss > 0) sl = pos.stopLoss
      if (pos.takeProfit && pos.takeProfit > 0) tp = pos.takeProfit
      side = pos.side
      label = 'Position Active'
    }
  } else if (selectedLineOption.value.startsWith('trade_')) {
    const idx = parseInt(selectedLineOption.value.replace('trade_', ''))
    const trade = props.trades[idx]
    if (trade) {
      entry = trade.entryPrice
      if (trade.stopLoss && trade.stopLoss > 0) sl = trade.stopLoss
      if (trade.takeProfit && trade.takeProfit > 0) tp = trade.takeProfit
      side = trade.side
      label = `Trade #${idx + 1}`
    }
  }

  if (entry !== null && entry > 0 && side !== null) {
    const isBuy = side === 'BUY'
    entryLine = candlestickSeries.createPriceLine({
      price: entry,
      color: '#3B82F6', // Blue
      lineWidth: 1,
      lineStyle: LineStyle.Solid,
      axisLabelVisible: true,
      title: `${isBuy ? 'Buy' : 'Sell'} (${label})`,
    })
  }

  if (sl !== null && sl > 0) {
    stopLossLine = candlestickSeries.createPriceLine({
      price: sl,
      color: '#EF4444', // Red
      lineWidth: 1,
      lineStyle: LineStyle.Dashed,
      axisLabelVisible: true,
      title: `SL (${label})`,
    })
  }

  if (tp !== null && tp > 0) {
    takeProfitLine = candlestickSeries.createPriceLine({
      price: tp,
      color: '#10B981', // Green
      lineWidth: 1,
      lineStyle: LineStyle.Dashed,
      axisLabelVisible: true,
      title: `TP (${label})`,
    })
  }
}

function render() {
  if (!container.value || !props.bars.length) return

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

  const isInitial = !chart

  if (isInitial) {
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
      localization: {
        timeFormatter: (timestamp: number) => {
          const isUtc = timezone.value === 'utc'
          return isUtc ? formatUTC(timestamp) : formatLocal(timestamp)
        },
      },
      timeScale: {
        visible: true,
        borderColor: '#374151',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: (time: any, tickMarkType: number, locale: string) => {
          const timestamp = typeof time === 'number' ? time : time.timestamp || 0
          const date = new Date(timestamp * 1000)
          
          const isUtc = timezone.value === 'utc'
          const hour = isUtc ? date.getUTCHours().toString().padStart(2, '0') : date.getHours().toString().padStart(2, '0')
          const min = isUtc ? date.getUTCMinutes().toString().padStart(2, '0') : date.getMinutes().toString().padStart(2, '0')
          
          if (tickMarkType >= 3) {
            return `${hour}:${min}`
          }
          
          const day = isUtc ? date.getUTCDate().toString().padStart(2, '0') : date.getDate().toString().padStart(2, '0')
          const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
          const month = months[isUtc ? date.getUTCMonth() : date.getMonth()]
          
          if (tickMarkType === 2) {
            return `${day} ${month}`
          }
          return `${month} ${isUtc ? date.getUTCFullYear() : date.getFullYear()}`
        },
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
      priceFormat: {
        type: 'price',
        precision: 5,
        minMove: 0.00001,
      },
    })

    chart.timeScale().subscribeVisibleLogicalRangeChange(logicalRange => {
      if (logicalRange !== null && logicalRange.from < 50) {
        if (props.bars.length > 0) {
          emit('loadMoreBars', props.bars[0].time)
        }
      }
    })
  }

  candlestickSeries!.setData(chartBars)

  // Map trades to markers
  const barTimes = chartBars.map(b => b.time as number)
  const markers: any[] = []

  function isTimeInBounds(tradeTimeStr: string | null): boolean {
    if (!tradeTimeStr) return false
    const target = Math.floor(new Date(tradeTimeStr).getTime() / 1000)
    // Check if epoch (less than a day from 1970)
    if (target < 86400) return false
    if (barTimes.length === 0) return false
    
    // Allow a small buffer (e.g., 2 bar intervals) if possible, but simplest is strictly within bounds
    // Let's assume standard intervals, but strictly checking against first/last bar time is safest
    const firstBar = barTimes[0]
    const lastBar = barTimes[barTimes.length - 1]
    
    // We allow it to be drawn if it's roughly within the chart's start and end times
    return target >= firstBar && target <= lastBar
  }

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
    const isLong = trade.side === 'BUY'

    // Entry Marker
    if (isTimeInBounds(trade.entryTime)) {
      const entryTime = findClosestBarTime(trade.entryTime)
      markers.push({
        time: entryTime,
        position: isLong ? 'belowBar' : 'aboveBar',
        color: isLong ? '#10B981' : '#EF4444',
        shape: isLong ? 'arrowUp' : 'arrowDown',
        text: `${isLong ? 'Buy' : 'Sell'} #${idx + 1}`,
      })
    }

    // Exit Marker
    if (isTimeInBounds(trade.exitTime)) {
      const exitTime = findClosestBarTime(trade.exitTime)
      markers.push({
        time: exitTime,
        position: isLong ? 'aboveBar' : 'belowBar',
        color: isLong ? '#EF4444' : '#10B981',
        shape: isLong ? 'arrowDown' : 'arrowUp',
        text: `Exit #${idx + 1}`,
      })
    }
  })

  if (props.positions) {
    props.positions.forEach((pos) => {
      let entryTime: number;
      if (isTimeInBounds(pos.entryTime)) {
        entryTime = findClosestBarTime(pos.entryTime!)
      } else {
        // If the entry time is unknown (epoch) or before the chart loaded, 
        // fallback to placing the marker on the most recent bar so it's visible.
        entryTime = barTimes[barTimes.length - 1]
      }
      
      const isLong = pos.side === 'BUY'
      markers.push({
        time: entryTime,
        position: isLong ? 'belowBar' : 'aboveBar',
        color: '#3B82F6', // Blue to match the active position line
        shape: isLong ? 'arrowUp' : 'arrowDown',
        text: `Active ${isLong ? 'Buy' : 'Sell'}`,
      })
    })
  }

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

  // Only fit content on initial rendering so we don't snap away from user's zoom/pan on ticks
  if (isInitial && chart) {
    chart.timeScale().fitContent()
  }

  // Draw take profit & stop loss lines
  updatePriceLines()
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
    candlestickSeries = null
    entryLine = null
    stopLossLine = null
    takeProfitLine = null
  }
})

watch(() => props.bars, async () => {
  await nextTick()
  render()
})

watch(() => props.trades, async () => {
  await nextTick()
  render()
}, { deep: true })

watch(() => props.positions, () => {
  updatePriceLines()
}, { deep: true })

watch(selectedLineOption, () => {
  updatePriceLines()
})

watch(timezone, () => {
  if (chart) {
    chart.remove()
    chart = null
    candlestickSeries = null
    entryLine = null
    stopLossLine = null
    takeProfitLine = null
  }
  render()
})

function updateBar(bar: Bar) {
  if (!candlestickSeries) return
  candlestickSeries.update({
    time: Math.floor(new Date(bar.time).getTime() / 1000) as any,
    open: bar.open,
    high: bar.high,
    low: bar.low,
    close: bar.close,
  })
}

defineExpose({ updateBar, updatePriceLines })
</script>

<template>
  <div class="trade-chart-container">
    <div ref="container" class="trade-chart"></div>
    <div class="legend">
      <div class="legend-item"><span class="dot buy"></span>Buy Entry</div>
      <div class="legend-item"><span class="dot sell"></span>Sell Entry</div>
      <div style="flex-grow: 1;"></div>
      <div style="display: flex; gap: 1rem; align-items: center;">
        <div class="timezone-select-container">
          <label for="line-select" class="tz-label">Afficher SL/TP :</label>
          <select id="line-select" v-model="selectedLineOption" class="tz-select">
            <option v-for="opt in lineOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </div>
        <div class="timezone-select-container">
          <label for="tz-select" class="tz-label">Axe temporel :</label>
          <select id="tz-select" v-model="timezone" class="tz-select">
            <option value="local">Heure locale</option>
            <option value="utc">UTC</option>
          </select>
        </div>
      </div>
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
  align-items: center;
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

.timezone-select-container {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.tz-label {
  font-size: 0.75rem;
  color: #9CA3AF;
}

.tz-select {
  background: #111317;
  border: 1px solid #374151;
  border-radius: 4px;
  color: #E5E7EB;
  font-size: 0.75rem;
  padding: 0.2rem 0.5rem;
  outline: none;
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.tz-select:hover {
  border-color: #4B5563;
}

.tz-select:focus {
  border-color: #d97706;
}
</style>
