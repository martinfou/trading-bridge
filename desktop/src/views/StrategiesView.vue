<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { Strategy } from '@/types/control-plane'
import StrategyCard from '@/components/StrategyCard.vue'

const { getStrategies } = useControlPlane()

const strategies = ref<Strategy[]>([])
const loading = ref(true)
const viewError = ref<string | null>(null)
const activeTab = ref<string>('ALL')
const searchQuery = ref('')
const expandedId = ref<string | null>(null)

const families = computed(() => {
  const set = new Set(strategies.value.map((s) => s.family))
  return Array.from(set).sort()
})

const filtered = computed(() => {
  let list = strategies.value
  if (activeTab.value !== 'ALL') {
    list = list.filter((s) => s.family === activeTab.value)
  }
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    list = list.filter(
      (s) =>
        s.id.toLowerCase().includes(q) ||
        s.family.toLowerCase().includes(q) ||
        (s.defaultSymbol && s.defaultSymbol.toLowerCase().includes(q)) ||
        (s.type && s.type.toLowerCase().includes(q)) ||
        (s.indicators && s.indicators.some((ind) => ind.toLowerCase().includes(q))),
    )
  }
  return list
})

const grouped = computed(() => {
  const groups: Record<string, Strategy[]> = {}
  for (const s of filtered.value) {
    const key = s.family
    if (!groups[key]) groups[key] = []
    groups[key].push(s)
  }
  return groups
})

function countByFamily(family: string): number {
  if (family === 'ALL') return strategies.value.length
  return strategies.value.filter((s) => s.family === family).length
}

function toggleExpand(id: string) {
  expandedId.value = expandedId.value === id ? null : id
}

onMounted(async () => {
  try {
    strategies.value = await getStrategies()
  } catch (e: any) {
    viewError.value = `Failed to load strategies: ${e.message}`
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="view">
    <div class="header-row">
      <div>
        <h1>Strategy Catalog</h1>
        <p class="subtitle">
          {{ strategies.length }} strategies across {{ families.length }} families
        </p>
      </div>
      <div class="search-wrapper">
        <input
          v-model="searchQuery"
          type="text"
          class="search-input"
          placeholder="Search strategies..."
        />
      </div>
    </div>

    <!-- Error -->
    <div v-if="viewError" class="banner error">{{ viewError }}</div>

    <!-- Tab filters -->
    <div class="tabs">
      <button
        :class="['tab', { active: activeTab === 'ALL' }]"
        @click="activeTab = 'ALL'"
      >
        All
        <span class="tab-count">{{ countByFamily('ALL') }}</span>
      </button>
      <button
        v-for="f in families"
        :key="f"
        :class="['tab', { active: activeTab === f }]"
        @click="activeTab = f"
      >
        {{ f }}
        <span class="tab-count">{{ countByFamily(f) }}</span>
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>Loading strategies…</p>
    </div>

    <!-- Empty state -->
    <div v-else-if="filtered.length === 0" class="empty-state">
      <p v-if="searchQuery">No strategies match "{{ searchQuery }}".</p>
      <p v-else>No strategies loaded. Is the control plane running?</p>
    </div>

    <!-- Grid -->
    <div v-for="(stratList, family) in grouped" :key="family" class="family-section">
      <h2 class="family-heading" :style="{ color: family === 'PROP' ? '#6366f1' : family === 'SQ_IMPORTED' ? '#f59e0b' : family === 'GENERATED' ? '#22c55e' : family === 'LONG_TERM' ? '#f97316' : '#a855f7' }">
        {{ family }}
        <span class="family-count">{{ stratList.length }}</span>
      </h2>
      <div class="card-grid">
        <StrategyCard
          v-for="s in stratList"
          :key="s.id"
          :strategy="s"
          :expanded="expandedId === s.id"
          @toggle-expand="toggleExpand"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }

h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  gap: 1rem;
}

.search-wrapper {
  flex-shrink: 0;
  min-width: 240px;
}

.search-input {
  width: 100%;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.6rem 0.85rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  outline: none;
  transition: border-color 0.15s;
}

.search-input:focus {
  border-color: var(--accent);
}

.search-input::placeholder {
  color: var(--text-secondary);
}

.banner {
  padding: 0.75rem 1rem;
  border-radius: 6px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.banner.error { background: #2d1212; color: #fca5a5; border: 1px solid #7f1d1d; }

.tabs {
  display: flex;
  gap: 0.4rem;
  margin-bottom: 1.25rem;
  flex-wrap: wrap;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.4rem 0.75rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.15s;
}

.tab:hover {
  border-color: #444;
  color: var(--text-primary);
}

.tab.active {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(217, 119, 6, 0.08);
}

.tab-count {
  background: var(--bg-primary);
  border-radius: 10px;
  padding: 0.05rem 0.45rem;
  font-size: 0.65rem;
  font-weight: 600;
}

.tab.active .tab-count {
  background: rgba(217, 119, 6, 0.15);
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 3rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.spinner {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.empty-state {
  padding: 3rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.family-section {
  margin-bottom: 1.5rem;
}

.family-heading {
  font-size: 0.95rem;
  font-weight: 700;
  margin-bottom: 0.6rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.family-count {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 0.6rem;
}
</style>
