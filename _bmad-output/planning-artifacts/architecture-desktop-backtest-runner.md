# Architecture — Trading Bridge Desktop (Electron)

> Phase 3 — Solutioning
> Projet : Trading Bridge — Backtest Runner Desktop
> Stack : Electron + Vue 3 + Vite + TypeScript + TradingView Lightweight Charts

---

## 1. Vue d'ensemble

```
┌──────────────────────────────────┐     REST + WS        ┌─────────────────────┐
│  Electron App (Vue 3 + Vite)     │ ◄───────────────────►│  Java Control Plane  │
│                                  │                      │  (localhost:8080)    │
│  ┌──── Main Process ──────────┐  │                      │                     │
│  │  electron/main.ts          │  │   POST /api/runs     │  POST /api/runs     │
│  │  electron/preload.ts       │  │   GET  /api/runs/{id} │  GET /api/strategies│
│  │  electron/ipc-handlers.ts  │  │   GET  /api/strategies│  WS /ws/runs/{id}   │
│  └────────────────────────────┘  │                      │  GET /runs/{id}/... │
│         ↕ IPC (contextBridge)    │                      └─────────────────────┘
│  ┌──── Renderer (Vue 3) ──────┐  │
│  │  src/                      │  │
│  │  ├── views/                │  │
│  │  │   ├── DashboardView     │  │
│  │  │   ├── ResultsView       │  │
│  │  │   ├── StrategiesView    │  │
│  │  │   └── CompareView       │  │
│  │  ├── components/           │  │
│  │  │   ├── CandlestickChart  │  │
│  │  │   ├── EquityChart       │  │
│  │  │   ├── KpiStrip          │  │
│  │  │   ├── TradeTable        │  │
│  │  │   ├── MonthlyReturns    │  │
│  │  │   └── StrategyCard      │  │
│  │  └── composables/          │  │
│  │      ├── useControlPlane   │  │
│  │      └── useRunWebSocket   │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

## 2. Communication avec le Java Control Plane

### REST Endpoints utilisés

| Méthode | Endpoint | Usage |
|---------|----------|-------|
| `POST` | `/api/runs` | Lancer un backtest |
| `GET` | `/api/runs/{runId}` | Récupérer le résultat |
| `GET` | `/api/runs/{runId}/events?afterSequence=N` | Replay des events (polling) |
| `GET` | `/api/strategies` | Catalogue des 55+ stratégies |
| `GET` | `/api/runs/{runId}/export?format=html` | Rapport HTML complet |

### WebSocket

- `WS /ws/runs/{runId}` — Événements temps réel (STARTED → ENDED/FAILED)

### ⚠️ Gap identifié : pas d'equity curve dans le payload REST

Le `RUN_ENDED` du control plane ne contient que : `totalTrades`, `totalReturnPct`, `finalEquity`, `maxDrawdownPct`.  
Pas d'equity curve, pas de trade list, pas de Sharpe/PF.

**Stratégie v1 : modifier légèrement le Java control plane**

Dans `RunManager.executeRun()`, enrichir le payload ended avec :

```java
record.markCompleted(Map.of(
    "totalTrades", result.totalTrades(),
    "totalReturnPct", result.totalReturnPct(),
    "finalEquity", result.finalEquity(),
    "maxDrawdownPct", result.maxDrawdownPct(),
    "sharpeRatio", result.sharpeRatio(),
    "profitFactor", result.profitFactor(),
    "winRatePct", result.winRatePct(),
    "totalCommission", result.totalCommission(),
    "totalSlippage", result.totalSlippage(),
    // Optionnel : échantillon d'equity curve (1 point / N barres)
    "equityCurveSample", sampleEquityCurve(result.equityCurve(), 500)
));
```

Et ajouter un endpoint dédié :

```
GET /api/runs/{runId}/trades → JSON array des trades
GET /api/runs/{runId}/equity-curve → JSON array des points d'equity (échantillonnés)
```

Ces endpoints évitent de charger 100k points dans un seul payload.

## 3. Structure du projet Electron

```
trading-bridge/
├── desktop/                     ← Nouveau projet
│   ├── electron/
│   │   ├── main.ts              ← Electron main process
│   │   ├── preload.ts           ← contextBridge API
│   │   └── ipc-handlers.ts      ← IPC handlers (file dialogs, etc.)
│   ├── src/
│   │   ├── main.ts              ← Vue 3 entry point
│   │   ├── App.vue
│   │   ├── router/
│   │   │   └── index.ts         ← Vue Router (4 routes)
│   │   ├── views/
│   │   │   ├── DashboardView.vue    ← Lancer un backtest
│   │   │   ├── ResultsView.vue      ← Analyser les résultats
│   │   │   ├── StrategiesView.vue   ← Catalogue des stratégies
│   │   │   └── CompareView.vue      ← Comparaison
│   │   ├── components/
│   │   │   ├── BacktestForm.vue     ← Formulaire de lancement
│   │   │   ├── CandlestickChart.vue ← TradingView chart (bars + trades)
│   │   │   ├── EquityChart.vue      ← Courbe d'equity
│   │   │   ├── KpiStrip.vue         ← Métriques clés
│   │   │   ├── TradeTable.vue       ← Liste des trades filtrable
│   │   │   ├── MonthlyReturns.vue   ← Returns mensuels (heatmap/bars)
│   │   │   └── StrategyCard.vue     ← Carte de stratégie
│   │   ├── composables/
│   │   │   ├── useControlPlane.ts   ← Client REST
│   │   │   └── useRunWebSocket.ts   ← Client WebSocket
│   │   ├── types/
│   │   │   └── control-plane.ts     ← Types TS (Run, Strategy, Trade, etc.)
│   │   └── assets/
│   ├── index.html
│   ├── vite.config.ts
│   ├── electron-builder.yml
│   ├── package.json
│   └── tsconfig.json
```

## 4. Data Flow — Lancer un backtest

```
User → sélectionne stratégie, paire, année, capital, costs
  → appuie sur RUN
  → POST /api/runs { strategyId, symbol, mode: "BACKTEST",
      barsSource: { type: "year", year: 2012 },
      capital, commissionPerTrade, slippagePct }
  → reçoit { runId, status: "RUNNING" }
  → connecte WS /ws/runs/{runId}
  → reçoit RUN_STARTED → (rien entre les deux) → RUN_ENDED
  → GET /api/runs/{runId} → metrics + endedPayload
  → GET /api/runs/{runId}/trades → trade list
  → GET /api/runs/{runId}/equity-curve → equity points
  → Affiche ResultsView
```

## 5. Composants détaillés

### CandlestickChart.vue (TradingView Lightweight Charts)
- Série `CandlestickSeries` : OHLC des barres historiques
- Marqueurs (`markers`) : flèches BUY/SELL aux points d'entrée/sortie
- Série `HistogramSeries` : volume
- Time scale interactive (zoom, pan)
- Props : `bars: Bar[], trades: Trade[]`

### EquityChart.vue
- Série `LineSeries` : equity curve
- Area fill optionnel
- Props : `points: number[]`

### KpiStrip.vue
- Grille de tuiles : Sharpe, PF, Total Return %, Max DD, Total Trades, Win Rate
- Couleur conditionnelle (vert si positif, rouge si négatif)
- Props : `result: BacktestResult`

### TradeTable.vue
- Tableau filtrable (date, sens, P&L)
- Colonnes : #, Date, Side, Entry, Exit, Quantity, P&L, P&L%
- Tri par colonne, filtre par date
- Props : `trades: Trade[]`

### MonthlyReturns.vue
- Histogramme ou heatmap des returns mensuels
- TradingView histogram series ou tableau HTML stylé
- Props : `trades: Trade[]` (calcul aggrégé côté client)

### BacktestForm.vue
- Sélecteur de stratégie (dropdown depuis le catalogue)
- Sélecteur de paire (défaut = defaultSymbol de la stratégie)
- Sélecteur d'année (2010-2026)
- Input capital (default $100k)
- Input commission (default 0.07)
- Input slippage (default 0.0001)
- Bouton RUN avec état de chargement
- Props : `strategies: Strategy[]`
- Emits : `run(config: RunConfig)`

### StrategyCard.vue
- Nom + famille + paire par défaut
- Badge de déploiement (BACKTEST/PAPER/LIVE)
- Click → navigue vers Dashboard pré-rempli

## 6. Types principaux (TypeScript)

```typescript
interface Strategy {
  id: string
  family: string
  defaultSymbol: string
  deployedMode?: string
  executionLabel?: string
  brokerAccountId?: string
}

interface RunConfig {
  strategyId: string
  symbol: string
  mode: 'BACKTEST' | 'PAPER' | 'LIVE'
  barsSource: { type: 'year'; year?: number } | { type: 'ci' }
  capital: number
  commissionPerTrade?: number
  slippagePct?: number
}

interface RunResult {
  runId: string
  strategyId: string
  status: string
  result?: {
    totalTrades: number
    totalReturnPct: number
    finalEquity: number
    maxDrawdownPct: number
    sharpeRatio: number
    profitFactor: number
    winRatePct: number
    totalCommission: number
    totalSlippage: number
  }
  trades?: Trade[]
  equityCurve?: number[]
}

interface Trade {
  symbol: string
  side: 'BUY' | 'SELL'
  entryPrice: number
  exitPrice: number
  quantity: number
  entryTime: string
  exitTime: string
  pnl: number
}

interface Bar {
  time: string  // ISO date
  open: number
  high: number
  low: number
  close: number
  volume: number
}
```

## 7. Changements Java nécessaires

| Changement | Classe | Effort |
|---|---|---|
| Ajouter Sharpe/PF/WinRate dans endedPayload | `RunManager.executeRun()` | XS |
| Ajouter endpoint `GET /runs/{id}/trades` | `ControlPlaneServer` | XS |
| Ajouter endpoint `GET /runs/{id}/equity-curve` | `ControlPlaneServer` | XS |
| Échantillonnage equity curve (max 500 points) | Nouvelle util | XS |

Ces changements sont minimes et indépendants du frontend.

## 8. Pages / vues

### DashboardView
- BacktestForm en haut
- Résultat du dernier run en bas (KpiStrip + EquityChart + CandlestickChart)

### ResultsView
- Si run en cours : streaming status + equity chart partiel
- Si run terminé : KpiStrip + CandlestickChart + TradeTable + MonthlyReturns + Export buttons

### StrategiesView
- Grille de StrategyCard
- Barre de recherche et filtres (famille, déploiement)
- Click → navigue vers DashboardView avec la stratégie pré-sélectionnée

### CompareView
- Liste des runs récents avec cases à cocher
- Tableau comparatif : Strategy, Sharpe, PF, DD, Trades, Win Rate
- Equity curves superposées (multi-line)
