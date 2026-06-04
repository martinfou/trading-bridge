<script setup lang="ts">
import { ref, computed } from 'vue'
import type { Trade } from '@/types/control-plane'

const props = defineProps<{
  trades: Trade[]
}>()

const sortColumn = ref<string>('entryTime')
const sortDir = ref<'asc' | 'desc'>('desc')
const pageSize = 20
const page = ref(1)

const totalPages = computed(() => Math.max(1, Math.ceil(props.trades.length / pageSize)))

const sorted = computed(() => {
  const list = [...props.trades]
  list.sort((a, b) => {
    let cmp = 0
    switch (sortColumn.value) {
      case 'entryTime':
        cmp = a.entryTime.localeCompare(b.entryTime)
        break
      case 'exitTime':
        cmp = a.exitTime.localeCompare(b.exitTime)
        break
      case 'side':
        cmp = a.side.localeCompare(b.side)
        break
      case 'entryPrice':
        cmp = a.entryPrice - b.entryPrice
        break
      case 'exitPrice':
        cmp = a.exitPrice - b.exitPrice
        break
      case 'quantity':
        cmp = a.quantity - b.quantity
        break
      case 'pnl':
        cmp = a.pnl - b.pnl
        break
    }
    return sortDir.value === 'asc' ? cmp : -cmp
  })
  return list
})

const pagedTrades = computed(() => {
  const start = (page.value - 1) * pageSize
  return sorted.value.slice(start, start + pageSize)
})

function toggleSort(col: string) {
  if (sortColumn.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortColumn.value = col
    sortDir.value = 'desc'
  }
  page.value = 1
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString('fr-CA', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function canPrev() { return page.value > 1 }
function canNext() { return page.value < totalPages.value }
function prev() { if (canPrev()) page.value-- }
function next() { if (canNext()) page.value++ }
</script>

<template>
  <div class="trade-table-wrapper">
    <div class="table-header">
      <h3>Trades <span class="trade-count">{{ trades.length }}</span></h3>
    </div>

    <div v-if="trades.length === 0" class="empty">No trades recorded.</div>

    <table v-else class="trade-table">
      <thead>
        <tr>
          <th class="sortable" @click="toggleSort('entryTime')">
            Entry {{ sortColumn === 'entryTime' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
          <th class="sortable" @click="toggleSort('side')">
            Side {{ sortColumn === 'side' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
          <th class="sortable" @click="toggleSort('entryPrice')">
            Entry Price {{ sortColumn === 'entryPrice' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
          <th class="sortable" @click="toggleSort('exitPrice')">
            Exit Price {{ sortColumn === 'exitPrice' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
          <th class="sortable" @click="toggleSort('quantity')">
            Qty {{ sortColumn === 'quantity' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
          <th class="sortable" @click="toggleSort('pnl')">
            PnL {{ sortColumn === 'pnl' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(t, i) in pagedTrades" :key="i">
          <td class="cell-time">{{ formatTime(t.entryTime) }}</td>
          <td>
            <span :class="['side-badge', t.side === 'BUY' ? 'buy' : 'sell']">
              {{ t.side }}
            </span>
          </td>
          <td class="cell-num">{{ t.entryPrice.toFixed(5) }}</td>
          <td class="cell-num">{{ t.exitPrice.toFixed(5) }}</td>
          <td class="cell-num">{{ t.quantity }}</td>
          <td :class="['cell-num', 'cell-pnl', t.pnl >= 0 ? 'profit' : 'loss']">
            {{ t.pnl >= 0 ? '+' : '' }}{{ t.pnl.toFixed(2) }}
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="!canPrev()" @click="prev">◀ Prev</button>
      <span class="page-info">{{ page }} / {{ totalPages }}</span>
      <button :disabled="!canNext()" @click="next">Next ▶</button>
    </div>
  </div>
</template>

<style scoped>
.trade-table-wrapper {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}

.table-header {
  padding: 0.85rem 1rem;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.table-header h3 {
  font-size: 0.9rem;
  font-weight: 600;
}

.trade-count {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-weight: 400;
}

.empty {
  padding: 2rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.trade-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8rem;
}

th {
  text-align: left;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  user-select: none;
}

th.sortable {
  cursor: pointer;
}

th.sortable:hover {
  color: var(--text-primary);
}

td {
  padding: 0.4rem 0.75rem;
  border-bottom: 1px solid var(--border);
  color: var(--text-primary);
  vertical-align: middle;
}

tr:last-child td {
  border-bottom: none;
}

tr:hover td {
  background: rgba(255, 255, 255, 0.02);
}

.cell-time {
  font-size: 0.75rem;
  color: var(--text-secondary);
  white-space: nowrap;
}

.cell-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.75rem;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.cell-pnl {
  font-weight: 600;
}

.cell-pnl.profit {
  color: var(--success);
}

.cell-pnl.loss {
  color: var(--danger);
}

.side-badge {
  display: inline-block;
  padding: 0.1rem 0.4rem;
  border-radius: 3px;
  font-size: 0.65rem;
  font-weight: 700;
}

.side-badge.buy {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.side-badge.sell {
  background: rgba(239, 68, 68, 0.15);
  color: var(--danger);
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 0.75rem;
  padding: 0.6rem;
  border-top: 1px solid var(--border);
}

.pagination button {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.3rem 0.7rem;
  color: var(--text-secondary);
  font-size: 0.75rem;
  cursor: pointer;
  transition: all 0.15s;
}

.pagination button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.pagination button:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.page-info {
  font-size: 0.75rem;
  color: var(--text-secondary);
}
</style>
