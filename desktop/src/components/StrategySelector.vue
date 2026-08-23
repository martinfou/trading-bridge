<template>
  <div class="strategy-selector-root" ref="containerRef">
    <!-- Trigger Button -->
    <button
      type="button"
      ref="triggerButtonRef"
      class="selector-trigger"
      :class="{ 'is-open': isOpen, 'is-disabled': disabled }"
      :disabled="disabled"
      :aria-expanded="isOpen"
      aria-haspopup="listbox"
      :aria-controls="dropdownId"
      @click="toggleDropdown"
      @keydown.down.prevent="openAndFocusFirst"
      @keydown.up.prevent="openAndFocusLast"
    >
      <div v-if="selectedStrategy" class="selected-content">
        <div class="selected-header">
          <span class="selected-id">{{ selectedStrategy.id }}</span>
          <span class="badge" :class="getAssetClassBadgeClass(selectedStrategy)">
            {{ getAssetClassEmoji(selectedStrategy) }} {{ getPrimaryAssetClass(selectedStrategy) }}
          </span>
          <span v-if="selectedStrategy.tradingStyle" class="badge badge-style">
            {{ formatStyle(selectedStrategy.tradingStyle) }}
          </span>
        </div>
        <div class="selected-meta">
          <span class="selected-desc">{{ selectedStrategy.description || selectedStrategy.type }}</span>
          <span class="selected-symbol">Default: <strong>{{ selectedStrategy.defaultSymbol }}</strong></span>
        </div>
      </div>
      <div v-else class="placeholder">
        Select a strategy...
      </div>
      <div class="trigger-icons">
        <kbd class="shortcut-hint">⌘K</kbd>
        <span class="chevron" :class="{ 'chevron-rotated': isOpen }">▼</span>
      </div>
    </button>

    <!-- Dropdown Panel -->
    <Teleport to="body">
      <div
        v-if="isOpen"
        class="strategy-modal-backdrop"
        @click.self="closeDropdown"
      >
        <div
          :id="dropdownId"
          ref="dropdownPanelRef"
          class="strategy-dropdown-panel"
          role="dialog"
          aria-modal="true"
          aria-label="Select Strategy"
          @keydown="handleKeyDown"
        >
          <!-- Search & Filter Header -->
          <div class="panel-header">
            <div class="search-box">
              <span class="search-icon">🔍</span>
              <input
                ref="searchInputRef"
                type="text"
                v-model="searchQuery"
                placeholder="Search strategies, indicators, symbols, or styles (e.g. MES, ATR, Trend)..."
                class="search-input"
                @input="highlightedIndex = 0"
              />
              <button
                v-if="searchQuery"
                class="clear-search-btn"
                @click="searchQuery = ''; searchInputRef?.focus()"
              >
                ✕
              </button>
            </div>

            <!-- Filter Chips -->
            <div class="filter-chips">
              <button
                v-for="chip in filterChips"
                :key="chip.id"
                type="button"
                class="filter-chip"
                :class="{ 'chip-active': isChipActive(chip) }"
                @click="applyChip(chip)"
              >
                {{ chip.label }}
              </button>
            </div>
          </div>

          <!-- Strategy List Categorized -->
          <div class="panel-body" role="listbox" ref="listboxRef">
            <div v-if="loading" class="panel-state-msg">
              <span class="spinner">⏳</span> Loading strategy catalog...
            </div>
            <div v-else-if="flattenedFilteredList.length === 0" class="panel-state-msg">
              <p>No strategies found matching your search and filter criteria.</p>
              <button type="button" class="btn-reset-filters" @click="resetAllFilters">
                Reset Filters
              </button>
            </div>
            <div v-else class="strategy-groups">
              <div
                v-for="(strats, groupName) in groupedStrategies"
                :key="groupName"
                class="strategy-group-section"
              >
                <div class="group-title">{{ groupName }} ({{ strats.length }})</div>
                <div class="group-items">
                  <div
                    v-for="item in strats"
                    :key="item.id"
                    :id="'opt-' + item.id"
                    role="option"
                    :aria-selected="item.id === modelValue"
                    class="strategy-card"
                    :class="{
                      'is-selected': item.id === modelValue,
                      'is-highlighted': item.id === highlightedStrategyId
                    }"
                    @click="selectStrategy(item)"
                    @mouseenter="highlightStrategy(item.id)"
                  >
                    <div class="card-top">
                      <div class="card-title-group">
                        <span class="strat-name">{{ item.id }}</span>
                        <span class="badge" :class="getAssetClassBadgeClass(item)">
                          {{ getAssetClassEmoji(item) }} {{ getPrimaryAssetClass(item) }}
                        </span>
                        <span v-if="item.tradingStyle" class="badge badge-style">
                          {{ formatStyle(item.tradingStyle) }}
                        </span>
                        <span v-if="item.complexity" class="badge badge-complexity" :class="'complexity-' + item.complexity.toLowerCase()">
                          {{ item.complexity }}
                        </span>
                      </div>
                      <div class="card-symbols">
                        <span class="rec-label">Recommended:</span>
                        <span
                          v-for="sym in (item.recommendedSymbols || [item.defaultSymbol])"
                          :key="sym"
                          class="symbol-tag"
                        >
                          {{ sym }}
                        </span>
                      </div>
                    </div>

                    <div class="card-description">
                      {{ item.description || item.type || 'Custom Quantitative Model' }}
                    </div>

                    <div v-if="item.indicators && item.indicators.length > 0" class="card-indicators">
                      <span v-for="ind in item.indicators" :key="ind" class="indicator-chip">
                        {{ ind }}
                      </span>
                      <span v-if="item.timeframeSuitability && item.timeframeSuitability.length > 0" class="timeframe-tag">
                        ⏱️ {{ item.timeframeSuitability.join(', ') }}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Panel Footer -->
          <div class="panel-footer">
            <span class="footer-hint">Use <strong>↑</strong> <strong>↓</strong> to navigate, <strong>Enter</strong> to select, <strong>Esc</strong> to dismiss</span>
            <span class="catalog-count">{{ flattenedFilteredList.length }} strategies available</span>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import type { Strategy, AssetClass } from '../types/control-plane'
import { useStrategyCatalog } from '../composables/useStrategyCatalog'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    disabled?: boolean
    allowedAssetClasses?: AssetClass[]
  }>(),
  {
    modelValue: '',
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'change', strategy: Strategy): void
}>()

const dropdownId = 'strategy-dropdown-' + Math.random().toString(36).substring(2, 9)

const {
  strategies,
  loading,
  selectedAssetClass,
  selectedFamily,
  selectedStyle,
  searchQuery,
  groupedStrategies,
  filteredStrategies,
  fetchCatalog,
  getStrategy,
  resetFilters
} = useStrategyCatalog()

const isOpen = ref(false)
const triggerButtonRef = ref<HTMLButtonElement | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const dropdownPanelRef = ref<HTMLDivElement | null>(null)
const listboxRef = ref<HTMLDivElement | null>(null)
const highlightedIndex = ref(0)

const filterChips = [
  { id: 'all', label: 'All', type: 'asset', value: 'ALL' },
  { id: 'futures', label: '⚡ Futures', type: 'asset', value: 'FUTURES' },
  { id: 'forex', label: '💱 Forex', type: 'asset', value: 'FOREX' },
  { id: 'equity', label: '📈 Equities', type: 'asset', value: 'EQUITY' },
  { id: 'trend', label: 'Trend', type: 'style', value: 'TREND_FOLLOWING' },
  { id: 'mean_rev', label: 'Mean Rev', type: 'style', value: 'MEAN_REVERSION' },
  { id: 'prop', label: 'Prop Desk', type: 'family', value: 'PROP' }
]

const selectedStrategy = computed(() => {
  return getStrategy(props.modelValue)
})

const flattenedFilteredList = computed(() => {
  return filteredStrategies.value
})

const highlightedStrategyId = computed(() => {
  if (flattenedFilteredList.value.length === 0) return null
  const idx = Math.min(Math.max(0, highlightedIndex.value), flattenedFilteredList.value.length - 1)
  return flattenedFilteredList.value[idx]?.id || null
})

const isChipActive = (chip: { type: string; value: string }) => {
  if (chip.type === 'asset') return selectedAssetClass.value === chip.value
  if (chip.type === 'style') return selectedStyle.value === chip.value
  if (chip.type === 'family') return selectedFamily.value === chip.value
  return false
}

const applyChip = (chip: { type: string; value: string }) => {
  if (chip.type === 'asset') {
    selectedAssetClass.value = selectedAssetClass.value === chip.value ? 'ALL' : (chip.value as AssetClass)
  } else if (chip.type === 'style') {
    selectedStyle.value = selectedStyle.value === chip.value ? 'ALL' : chip.value
  } else if (chip.type === 'family') {
    selectedFamily.value = selectedFamily.value === chip.value ? 'ALL' : chip.value
  }
  highlightedIndex.value = 0
}

const resetAllFilters = () => {
  resetFilters()
  highlightedIndex.value = 0
  searchInputRef.value?.focus()
}

const getPrimaryAssetClass = (s: Strategy): string => {
  if (s.assetClasses && s.assetClasses.length > 0) return s.assetClasses[0]
  if (s.id.startsWith('Futures') || s.defaultSymbol.startsWith('MES') || s.defaultSymbol.startsWith('MNQ')) return 'FUTURES'
  if (['IJR', 'VB', 'SCHA', 'PLTR', 'TSLA', 'AAPL'].includes(s.defaultSymbol)) return 'EQUITY'
  return 'FOREX'
}

const getAssetClassEmoji = (s: Strategy): string => {
  const ac = getPrimaryAssetClass(s)
  if (ac === 'FUTURES') return '⚡'
  if (ac === 'EQUITY') return '📈'
  if (ac === 'COMMODITIES') return '🛢️'
  return '💱'
}

const getAssetClassBadgeClass = (s: Strategy): string => {
  const ac = getPrimaryAssetClass(s).toLowerCase()
  return `badge-asset-${ac}`
}

const formatStyle = (style: string): string => {
  return style.replace('_', ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase())
}

const toggleDropdown = () => {
  if (props.disabled) return
  if (isOpen.value) {
    closeDropdown()
  } else {
    openDropdown()
  }
}

const openDropdown = () => {
  fetchCatalog()
  isOpen.value = true
  // Set initial highlighted index to current selected if any
  const curIdx = flattenedFilteredList.value.findIndex(s => s.id === props.modelValue)
  highlightedIndex.value = curIdx >= 0 ? curIdx : 0
  nextTick(() => {
    searchInputRef.value?.focus()
  })
}

const closeDropdown = () => {
  isOpen.value = false
  // Return focus to trigger button (WAI-ARIA pattern)
  nextTick(() => {
    triggerButtonRef.value?.focus()
  })
}

const openAndFocusFirst = () => {
  openDropdown()
  highlightedIndex.value = 0
}

const openAndFocusLast = () => {
  openDropdown()
  highlightedIndex.value = Math.max(0, flattenedFilteredList.value.length - 1)
}

const highlightStrategy = (id: string) => {
  const idx = flattenedFilteredList.value.findIndex(s => s.id === id)
  if (idx >= 0) highlightedIndex.value = idx
}

const selectStrategy = (s: Strategy) => {
  emit('update:modelValue', s.id)
  emit('change', s)
  closeDropdown()
}

const handleKeyDown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') {
    e.preventDefault()
    closeDropdown()
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (highlightedIndex.value < flattenedFilteredList.value.length - 1) {
      highlightedIndex.value++
      scrollToHighlighted()
    }
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (highlightedIndex.value > 0) {
      highlightedIndex.value--
      scrollToHighlighted()
    }
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const target = flattenedFilteredList.value[highlightedIndex.value]
    if (target) {
      selectStrategy(target)
    }
  }
}

const scrollToHighlighted = () => {
  nextTick(() => {
    const el = document.querySelector('.strategy-card.is-highlighted') as HTMLElement
    if (el && listboxRef.value) {
      el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  })
}

// Global Cmd+K / Ctrl+K listener
const handleGlobalKeydown = (e: KeyboardEvent) => {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (isOpen.value) {
      closeDropdown()
    } else {
      openDropdown()
    }
  }
}

onMounted(() => {
  fetchCatalog()
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<style scoped>
.strategy-selector-root {
  position: relative;
  width: 100%;
  font-family: inherit;
}

.selector-trigger {
  width: 100%;
  min-height: 48px;
  background-color: var(--color-bg-secondary, #1e222d);
  border: 1px solid var(--color-border, #2a2e39);
  border-radius: 8px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  text-align: left;
  cursor: pointer;
  color: var(--color-text, #d1d4dc);
  transition: all 0.2s ease;
}

.selector-trigger:hover:not(:disabled) {
  border-color: var(--color-primary, #2962ff);
  background-color: var(--color-bg-tertiary, #2a2e39);
}

.selector-trigger.is-open {
  border-color: var(--color-primary, #2962ff);
  box-shadow: 0 0 0 2px rgba(41, 98, 255, 0.2);
}

.selector-trigger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.selected-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
}

.selected-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.selected-id {
  font-weight: 600;
  font-size: 14px;
  color: #fff;
}

.selected-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #848e9c;
}

.selected-desc {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 280px;
}

.selected-symbol strong {
  color: #2962ff;
}

.placeholder {
  color: #848e9c;
  font-size: 14px;
}

.trigger-icons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.shortcut-hint {
  font-size: 10px;
  padding: 2px 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  color: #848e9c;
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.chevron {
  font-size: 10px;
  color: #848e9c;
  transition: transform 0.2s;
}

.chevron-rotated {
  transform: rotate(180deg);
}

/* Modal Backdrop */
.strategy-modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  z-index: 9999;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 10vh;
}

.strategy-dropdown-panel {
  width: 100%;
  max-width: 680px;
  max-height: 80vh;
  background: #181b22;
  border: 1px solid #2a2e39;
  border-radius: 12px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: panelFadeIn 0.15s ease-out;
}

@keyframes panelFadeIn {
  from {
    opacity: 0;
    transform: scale(0.98) translateY(-10px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.panel-header {
  padding: 16px;
  border-bottom: 1px solid #2a2e39;
  background: #1e222d;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-box {
  display: flex;
  align-items: center;
  background: #131722;
  border: 1px solid #2a2e39;
  border-radius: 8px;
  padding: 8px 12px;
  gap: 8px;
}

.search-icon {
  font-size: 14px;
  color: #848e9c;
}

.search-input {
  flex: 1;
  background: transparent;
  border: none;
  color: #fff;
  font-size: 14px;
  outline: none;
}

.search-input::placeholder {
  color: #555d6e;
}

.clear-search-btn {
  background: transparent;
  border: none;
  color: #848e9c;
  cursor: pointer;
  padding: 2px 6px;
  font-size: 12px;
  border-radius: 4px;
}

.clear-search-btn:hover {
  background: #2a2e39;
  color: #fff;
}

.filter-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.filter-chip {
  background: #131722;
  border: 1px solid #2a2e39;
  color: #848e9c;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.filter-chip:hover {
  border-color: #2962ff;
  color: #fff;
}

.filter-chip.chip-active {
  background: #2962ff;
  border-color: #2962ff;
  color: #fff;
  font-weight: 500;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
  max-height: calc(80vh - 180px);
}

.panel-state-msg {
  padding: 40px 20px;
  text-align: center;
  color: #848e9c;
  font-size: 14px;
}

.btn-reset-filters {
  margin-top: 12px;
  background: #2962ff;
  border: none;
  color: #fff;
  padding: 6px 14px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.strategy-group-section {
  margin-bottom: 16px;
}

.group-title {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: #848e9c;
  margin-bottom: 8px;
  padding-left: 4px;
}

.group-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.strategy-card {
  background: #1e222d;
  border: 1px solid #2a2e39;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.strategy-card:hover,
.strategy-card.is-highlighted {
  border-color: #2962ff;
  background: #262b3d;
  transform: translateY(-1px);
}

.strategy-card.is-selected {
  border-color: #00c853;
  background: rgba(0, 200, 83, 0.08);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.card-title-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.strat-name {
  font-weight: 600;
  font-size: 14px;
  color: #fff;
}

.badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
}

.badge-asset-futures {
  background: rgba(255, 171, 0, 0.15);
  color: #ffab00;
  border: 1px solid rgba(255, 171, 0, 0.3);
}

.badge-asset-forex {
  background: rgba(41, 98, 255, 0.15);
  color: #2962ff;
  border: 1px solid rgba(41, 98, 255, 0.3);
}

.badge-asset-equity {
  background: rgba(0, 200, 83, 0.15);
  color: #00c853;
  border: 1px solid rgba(0, 200, 83, 0.3);
}

.badge-style {
  background: rgba(255, 255, 255, 0.08);
  color: #d1d4dc;
}

.badge-complexity {
  font-size: 10px;
}

.complexity-beginner {
  background: rgba(0, 200, 83, 0.1);
  color: #00e676;
}

.complexity-intermediate {
  background: rgba(255, 171, 0, 0.1);
  color: #ffb74d;
}

.complexity-advanced {
  background: rgba(244, 67, 54, 0.1);
  color: #e57373;
}

.card-symbols {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.rec-label {
  color: #848e9c;
}

.symbol-tag {
  background: #131722;
  color: #2962ff;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid #2a2e39;
}

.card-description {
  font-size: 12px;
  color: #848e9c;
  line-height: 1.4;
}

.card-indicators {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 2px;
}

.indicator-chip {
  background: #131722;
  color: #a0a6b5;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.timeframe-tag {
  font-size: 10px;
  color: #ffab00;
  margin-left: auto;
}

.panel-footer {
  padding: 10px 16px;
  border-top: 1px solid #2a2e39;
  background: #131722;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #848e9c;
}

.footer-hint strong {
  color: #d1d4dc;
  background: rgba(255, 255, 255, 0.1);
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
