<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useControlPlane } from '../composables/useControlPlane'

const props = defineProps<{
  instrument: string
}>()

const { getSentiment } = useControlPlane()

const loading = ref(true)
const error = ref<string | null>(null)
const orderBook = ref<any>(null)
let pollTimer: any = null

const fetchOrderBook = async () => {
  if (!props.instrument) return
  try {
    const data = await getSentiment(props.instrument)
    orderBook.value = data.orderBook
    error.value = null
  } catch (err: any) {
    error.value = err.message || 'Failed to fetch order book'
  } finally {
    loading.value = false
  }
}

watch(() => props.instrument, () => {
  loading.value = true
  fetchOrderBook()
})

onMounted(() => {
  fetchOrderBook()
  pollTimer = setInterval(fetchOrderBook, 15000) // Poll every 15s
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="order-book-panel panel">
    <div class="panel-header">
      <h3 class="panel-title">Market Sentiment Order Book</h3>
      <span v-if="orderBook" class="subtitle text-sm text-gray-400 ml-3">
        {{ props.instrument }} • As of {{ new Date(orderBook.time).toLocaleTimeString() }}
      </span>
    </div>
    <div class="panel-body">
      <div v-if="loading && !orderBook" class="flex justify-center p-8">
        <div class="loading-spinner"></div>
      </div>
      <div v-else-if="error" class="text-red-400 p-4">{{ error }}</div>
      <div v-else-if="!orderBook || !orderBook.buckets" class="text-gray-400 p-4">No order book data available.</div>
      <div v-else class="order-book-container">
        <div class="buckets-list">
          <div v-for="(bucket, idx) in orderBook.buckets" :key="idx" class="bucket-row flex text-xs mb-1">
            <div class="price-col w-20 font-mono text-right pr-4 text-gray-300">{{ bucket.price }}</div>
            <div class="long-bar-col flex-1 relative h-4 bg-gray-800 mr-1 overflow-hidden">
              <div class="absolute right-0 top-0 h-full bg-green-500 bg-opacity-50" :style="{ width: (parseFloat(bucket.longCountPercent) * 2) + '%' }"></div>
              <span class="absolute right-1 text-[10px] text-green-200 leading-4">{{ bucket.longCountPercent }}%</span>
            </div>
            <div class="short-bar-col flex-1 relative h-4 bg-gray-800 ml-1 overflow-hidden">
              <div class="absolute left-0 top-0 h-full bg-red-500 bg-opacity-50" :style="{ width: (parseFloat(bucket.shortCountPercent) * 2) + '%' }"></div>
              <span class="absolute left-1 text-[10px] text-red-200 leading-4">{{ bucket.shortCountPercent }}%</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-book-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.order-book-container {
  max-height: 400px;
  overflow-y: auto;
}
.bucket-row:hover {
  background: rgba(255, 255, 255, 0.05);
}
</style>
