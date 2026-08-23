import { ref, computed, onMounted, watch } from 'vue'
import type { Strategy, AssetClass } from '../types/control-plane'
import { useControlPlane } from './useControlPlane'

const STORAGE_KEY_FILTERS = 'trading_bridge_strategy_filters'

// Global shared state across component instances
const strategies = ref<Strategy[]>([])
const loading = ref<boolean>(false)
const error = ref<string | null>(null)

// Filters
const selectedAssetClass = ref<AssetClass>('ALL')
const selectedFamily = ref<string>('ALL')
const selectedStyle = ref<string>('ALL')
const searchQuery = ref<string>('')

export function useStrategyCatalog() {
  const { getStrategies } = useControlPlane()

  // Load persisted preferences
  const loadPersistedFilters = () => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY_FILTERS)
      if (saved) {
        const parsed = JSON.parse(saved)
        if (parsed.assetClass) selectedAssetClass.value = parsed.assetClass
        if (parsed.family) selectedFamily.value = parsed.family
        if (parsed.style) selectedStyle.value = parsed.style
      }
    } catch {
      // Ignore parsing errors
    }
  }

  const persistFilters = () => {
    try {
      localStorage.setItem(STORAGE_KEY_FILTERS, JSON.stringify({
        assetClass: selectedAssetClass.value,
        family: selectedFamily.value,
        style: selectedStyle.value
      }))
    } catch {
      // Ignore storage errors
    }
  }

  watch([selectedAssetClass, selectedFamily, selectedStyle], persistFilters)

  const fetchCatalog = async (force = false) => {
    if (strategies.value.length > 0 && !force) return
    loading.value = true
    error.value = null
    try {
      const list = await getStrategies()
      strategies.value = list.map(s => ({
        ...s,
        assetClasses: s.assetClasses && s.assetClasses.length > 0 ? s.assetClasses : inferAssetClassList(s.defaultSymbol),
        tradingStyle: s.tradingStyle || 'TREND_FOLLOWING',
        timeframeSuitability: s.timeframeSuitability || ['H1'],
        recommendedSymbols: s.recommendedSymbols || [s.defaultSymbol],
        complexity: s.complexity || 'INTERMEDIATE'
      }))
    } catch (err: any) {
      error.value = err?.message || 'Failed to fetch strategy catalog'
    } finally {
      loading.value = false
    }
  }

  const inferAssetClass = (symbol: string): AssetClass => {
    if (!symbol) return 'FOREX'
    const sym = symbol.toUpperCase().replace('/', '_')
    if (sym.startsWith('MES') || sym.startsWith('MNQ') || sym.startsWith('M2K') || sym.startsWith('EMD') || sym.startsWith('ES') || sym.startsWith('NQ')) {
      return 'FUTURES'
    }
    if (['IJR', 'VB', 'SCHA', 'PLTR', 'TSLA', 'SOXL', 'AAPL', 'NVDA', 'SPY', 'QQQ', 'IWM'].includes(sym)) {
      return 'EQUITY'
    }
    if (sym.includes('XAU') || sym.includes('XAG') || sym.includes('BRENT') || sym.includes('WTICO')) {
      return 'COMMODITIES'
    }
    return 'FOREX'
  }

  const inferAssetClassList = (symbol: string): string[] => {
    const ac = inferAssetClass(symbol)
    if (ac === 'COMMODITIES') return ['COMMODITIES', 'FOREX']
    return [ac]
  }

  // Multi-Asset Basket Isolation Guard (Red Team Hardened)
  const isCompatibleBasket = (symbols: string[]): { compatible: boolean; primaryAssetClass: AssetClass; reason?: string } => {
    if (!symbols || symbols.length <= 1) {
      const ac = symbols && symbols.length === 1 ? inferAssetClass(symbols[0]) : 'FOREX'
      return { compatible: true, primaryAssetClass: ac }
    }
    const firstAc = inferAssetClass(symbols[0])
    for (let i = 1; i < symbols.length; i++) {
      const currentAc = inferAssetClass(symbols[i])
      if (firstAc !== currentAc) {
        return {
          compatible: false,
          primaryAssetClass: firstAc,
          reason: `Mixed asset classes detected (${firstAc} and ${currentAc}). Discrete futures contracts cannot mix with fractional forex lots in a single run.`
        }
      }
    }
    return { compatible: true, primaryAssetClass: firstAc }
  }

  const filteredStrategies = computed(() => {
    let result = strategies.value

    // Asset Class filter
    if (selectedAssetClass.value !== 'ALL') {
      result = result.filter(s => 
        s.assetClasses?.includes(selectedAssetClass.value) ||
        (selectedAssetClass.value === 'FOREX' && s.assetClasses?.includes('COMMODITIES'))
      )
    }

    // Family filter
    if (selectedFamily.value !== 'ALL') {
      result = result.filter(s => s.family === selectedFamily.value)
    }

    // Style filter
    if (selectedStyle.value !== 'ALL') {
      result = result.filter(s => s.tradingStyle === selectedStyle.value)
    }

    // Search query (fuzzy multi-field search)
    if (searchQuery.value.trim()) {
      const q = searchQuery.value.toLowerCase().trim()
      result = result.filter(s => {
        const idMatch = s.id.toLowerCase().includes(q)
        const descMatch = s.description?.toLowerCase().includes(q) || false
        const typeMatch = s.type?.toLowerCase().includes(q) || false
        const indMatch = s.indicators?.some(i => i.toLowerCase().includes(q)) || false
        const symMatch = s.recommendedSymbols?.some(sym => sym.toLowerCase().includes(q)) || false
        const defaultSymMatch = s.defaultSymbol?.toLowerCase().includes(q) || false
        return idMatch || descMatch || typeMatch || indMatch || symMatch || defaultSymMatch
      })
    }

    return result
  })

  const groupedStrategies = computed(() => {
    const groups: Record<string, Strategy[]> = {}
    for (const s of filteredStrategies.value) {
      let groupName = '💱 Forex Strategies'
      if (s.assetClasses?.includes('FUTURES') || s.id.startsWith('Futures')) {
        groupName = '⚡ CME Futures (IBKR)'
      } else if (s.family === 'PROP') {
        groupName = '🏛️ Prop Mechanical Desk'
      } else if (s.family === 'LONG_TERM') {
        groupName = '📈 Systematic Multi-Asset / Long-Term'
      } else if (s.family === 'SQ_IMPORTED') {
        groupName = '🤖 StrategyQuant Models'
      }
      if (!groups[groupName]) groups[groupName] = []
      groups[groupName].push(s)
    }
    return groups
  })

  const getStrategy = (id: string): Strategy | undefined => {
    return strategies.value.find(s => s.id === id)
  }

  const getRecommendedInstruments = (strategyId: string): string[] => {
    const strat = getStrategy(strategyId)
    if (!strat) return []
    return strat.recommendedSymbols || [strat.defaultSymbol]
  }

  const getCompatibleStrategiesForSymbol = (symbol: string): Strategy[] => {
    const ac = inferAssetClass(symbol)
    return strategies.value.filter(s => s.assetClasses?.includes(ac))
  }

  const resetFilters = () => {
    selectedAssetClass.value = 'ALL'
    selectedFamily.value = 'ALL'
    selectedStyle.value = 'ALL'
    searchQuery.value = ''
    persistFilters()
  }

  // Initialize on composable creation
  loadPersistedFilters()

  return {
    strategies,
    loading,
    error,
    selectedAssetClass,
    selectedFamily,
    selectedStyle,
    searchQuery,
    filteredStrategies,
    groupedStrategies,
    fetchCatalog,
    getStrategy,
    inferAssetClass,
    isCompatibleBasket,
    getRecommendedInstruments,
    getCompatibleStrategiesForSymbol,
    resetFilters
  }
}
