import { ref } from 'vue'
import type { CostPreset, AssetClass } from '../types/control-plane'

export const COST_PRESETS: Record<string, CostPreset> = {
  IBKR_FUTURES_TIERED: {
    id: 'IBKR_FUTURES_TIERED',
    name: '⚡ CME Futures (IBKR Tiered)',
    assetClass: 'FUTURES',
    lotSize: 1.0,
    unitLabel: 'contracts',
    capital: 50000,
    commissionPerTrade: 0.62,
    slippagePct: 0.0001,
    description: '$0.62/side exchange + NFA + clearing per micro contract (MES/MNQ/M2K)'
  },
  OANDA_FOREX_CORE: {
    id: 'OANDA_FOREX_CORE',
    name: '💱 Forex ECN (OANDA Core)',
    assetClass: 'FOREX',
    lotSize: 0.01,
    unitLabel: 'lots (0.01 = 1k units)',
    capital: 1000,
    commissionPerTrade: 0.07,
    slippagePct: 0.00005,
    description: '$0.07/side per 0.01 lot ($7.00/100k standard lot ECN commission)'
  },
  IBKR_EQUITY_TIERED: {
    id: 'IBKR_EQUITY_TIERED',
    name: '📈 US Equities (IBKR Tiered)',
    assetClass: 'EQUITY',
    lotSize: 10,
    unitLabel: 'shares',
    capital: 10000,
    commissionPerTrade: 0.35,
    slippagePct: 0.0002,
    description: '$0.005/share, $0.35 minimum order ticket'
  }
}

export function useCostPresets() {
  const isUserModified = ref<boolean>(false)

  const getPresetForAssetClass = (assetClass: AssetClass): CostPreset => {
    switch (assetClass) {
      case 'FUTURES':
        return COST_PRESETS.IBKR_FUTURES_TIERED
      case 'EQUITY':
        return COST_PRESETS.IBKR_EQUITY_TIERED
      case 'FOREX':
      case 'COMMODITIES':
      default:
        return COST_PRESETS.OANDA_FOREX_CORE
    }
  }

  const getPresetForSymbol = (symbol: string): CostPreset => {
    if (!symbol) return COST_PRESETS.OANDA_FOREX_CORE
    const sym = symbol.toUpperCase().replace('/', '_')
    if (sym.startsWith('MES') || sym.startsWith('MNQ') || sym.startsWith('M2K') || sym.startsWith('EMD') || sym.startsWith('ES') || sym.startsWith('NQ')) {
      return COST_PRESETS.IBKR_FUTURES_TIERED
    }
    if (['IJR', 'VB', 'SCHA', 'PLTR', 'TSLA', 'SOXL', 'AAPL', 'NVDA', 'SPY', 'QQQ', 'IWM'].includes(sym)) {
      return COST_PRESETS.IBKR_EQUITY_TIERED
    }
    return COST_PRESETS.OANDA_FOREX_CORE
  }

  const markUserModified = () => {
    isUserModified.value = true
  }

  const resetUserModified = () => {
    isUserModified.value = false
  }

  return {
    COST_PRESETS,
    isUserModified,
    getPresetForAssetClass,
    getPresetForSymbol,
    markUserModified,
    resetUserModified
  }
}
