import { ref } from 'vue'
import type { CostPreset, AssetClass } from '../types/control-plane'

const MICRO_FUTURES_PREFIXES = ['MGC', 'MNQ', 'MES', 'M2K', 'MYM', 'MCL']
const FULL_FUTURES_PREFIXES = ['GC', 'NQ', 'ES', 'EMD', 'YM', 'RTY', 'CL', 'SI', 'ZB', 'ZN']
const EQUITY_SYMBOLS = new Set([
  'IJR', 'VB', 'SCHA', 'PLTR', 'TSLA', 'SOXL',
  'AAPL', 'NVDA', 'MSFT', 'AMZN', 'META', 'GOOGL', 'AMD', 'NFLX', 'JPM',
  'SPY', 'QQQ', 'IWM', 'MDY'
])

export const COST_PRESETS: Record<string, CostPreset> = {
  CME_MICRO_FUTURES: {
    id: 'CME_MICRO_FUTURES',
    name: '⚡ CME Micro Futures (MGC / MNQ / MES)',
    assetClass: 'FUTURES',
    lotSize: 1.0,
    unitLabel: 'contracts',
    capital: 10000,
    commissionPerTrade: 0.62,
    slippagePct: 0.00005,
    description: '$10,000 capital · 1 micro contract · $0.62/side exchange + clearing · 0.00005 slippage'
  },
  CME_FULL_FUTURES: {
    id: 'CME_FULL_FUTURES',
    name: '🏛️ CME Full-Size Futures (GC / NQ / ES / EMD)',
    assetClass: 'FUTURES',
    lotSize: 1.0,
    unitLabel: 'contracts',
    capital: 50000,
    commissionPerTrade: 2.10,
    slippagePct: 0.00005,
    description: '$50,000 capital · 1 standard contract · $2.10/side exchange + clearing · 0.00005 slippage'
  },
  IBKR_FUTURES_TIERED: {
    id: 'IBKR_FUTURES_TIERED',
    name: '⚡ CME Micro Futures (MGC / MNQ / MES)',
    assetClass: 'FUTURES',
    lotSize: 1.0,
    unitLabel: 'contracts',
    capital: 10000,
    commissionPerTrade: 0.62,
    slippagePct: 0.00005,
    description: '$10,000 capital · 1 micro contract · $0.62/side exchange + clearing · 0.00005 slippage'
  },
  OANDA_FOREX_CORE: {
    id: 'OANDA_FOREX_CORE',
    name: '💱 Forex Standard ECN (OANDA / Institutional)',
    assetClass: 'FOREX',
    lotSize: 0.10,
    unitLabel: 'lots (0.10 = 10k units)',
    capital: 10000,
    commissionPerTrade: 0.70,
    slippagePct: 0.00005,
    description: '$10,000 capital · 0.10 lots (10,000 units) · $0.70/side ECN commission · 0.5 pip (0.00005) slippage'
  },
  OANDA_SPOT_GOLD: {
    id: 'OANDA_SPOT_GOLD',
    name: '🪙 Spot Gold CFD (XAU/USD)',
    assetClass: 'FOREX',
    lotSize: 0.10,
    unitLabel: 'lots (0.10 = 10 oz)',
    capital: 10000,
    commissionPerTrade: 0.70,
    slippagePct: 0.00005,
    description: '$10,000 capital · 0.10 lots (10 troy oz) · $0.70/side commission · 0.00005 slippage'
  },
  OANDA_SPOT_SILVER: {
    id: 'OANDA_SPOT_SILVER',
    name: '🪙 Spot Silver CFD (XAG/USD)',
    assetClass: 'FOREX',
    lotSize: 0.10,
    unitLabel: 'lots (0.10 = 500 oz)',
    capital: 10000,
    commissionPerTrade: 0.70,
    slippagePct: 0.00005,
    description: '$10,000 capital · 0.10 lots (500 troy oz) · $0.70/side commission · 0.00005 slippage'
  },
  IBKR_EQUITY_TIERED: {
    id: 'IBKR_EQUITY_TIERED',
    name: '📈 US Equities & ETFs (IBKR Tiered)',
    assetClass: 'EQUITY',
    lotSize: 10,
    unitLabel: 'shares',
    capital: 10000,
    commissionPerTrade: 1.00,
    slippagePct: 0.0002,
    description: '$10,000 capital · 10 shares · $1.00/order ticket · 2 bps (0.0002) slippage'
  }
}

export function useCostPresets() {
  const isUserModified = ref<boolean>(false)

  const getPresetForAssetClass = (assetClass: AssetClass): CostPreset => {
    switch (assetClass) {
      case 'FUTURES':
        return COST_PRESETS.CME_MICRO_FUTURES
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
    const sym = symbol.trim().toUpperCase().replace('/', '_')
    if (MICRO_FUTURES_PREFIXES.some(p => sym === p || sym.startsWith(`${p}_`))) {
      return COST_PRESETS.CME_MICRO_FUTURES
    }
    if (FULL_FUTURES_PREFIXES.some(p => sym === p || sym.startsWith(`${p}_`))) {
      return COST_PRESETS.CME_FULL_FUTURES
    }
    if (sym.startsWith('XAU')) {
      return COST_PRESETS.OANDA_SPOT_GOLD
    }
    if (sym.startsWith('XAG')) {
      return COST_PRESETS.OANDA_SPOT_SILVER
    }
    if (EQUITY_SYMBOLS.has(sym)) {
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
