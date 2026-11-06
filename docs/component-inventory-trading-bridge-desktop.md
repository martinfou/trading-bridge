# Inventaire des Composants UI — Trading Bridge Desktop (`trading-bridge-desktop`)

**Date de mise à jour :** 2026-11-05  
**Emplacement :** `desktop/src/views/`, `desktop/src/components/`, `desktop/src/composables/`  
**Framework :** Vue 3.5 (Composition API) + TypeScript 5.7 + TradingView Lightweight Charts 5.0

---

## 1. Vue d'Ensemble du Système de Design

L'interface de **Trading Bridge Desktop** repose sur un thème sombre institutionnel haute densité (`#0b0f19` / `#131b2e`) structuré autour de jetons de couleur par classe d'actifs :
* **Contrats à Terme CME (`--asset-futures`)** : Violet (`#a855f7`) — ex. `MES`, `MNQ`, `MGC`, `MCL`.
* **Actions US (`--asset-equity`)** : Cyan (`#06b6d4`) — ex. `AAPL`, `NVDA`, paniers Small-Cap.
* **Devises & Matières Premières Spot (`--asset-forex`)** : Bleu (`#3b82f6`) / Ambre (`#f59e0b`) — ex. `EUR_USD`, `GBP_JPY`, `XAU_USD`.

---

## 2. Vues Principales (`desktop/src/views/` — 9 Vues)

| Fichier Vue | Route | Lignes | Rôle & Fonctionnalités Clés |
| :--- | :--- | ---: | :--- |
| **`DashboardView.vue`** | `/dashboard` | 599 | **Poste de lancement et de suivi** : intègre `BacktestForm.vue`, surveille les exécutions actives par polling (`1 000 ms`), affiche `KpiStrip`, `EquityChart`, `PromoteModal`, et inclut une matrice rapide de couverture des données historiques (`m1`..`w1`). |
| **`StrategyGeneratorView.vue`** | `/generator` | 3 113 | **Studio Évolutionnaire Natif (Alternative SQX)** : génération de stratégies en mode `GENETIC` (tournoi, croisement uniforme, mutation) ou `RANDOM` (Monte-Carlo) à partir de **24 blocs de construction** répartis en 10 catégories (`Breakout`, `Volatility`, `Trend`, `Structure`, `Volume`, `Momentum`, `Oscillator`, `Mean-Rev`, `Price Action`, `Regime`). Inclut des info-bulles de formules mathématiques, le split strict **IS / Blind OOS** (`80/20`, `70/30`, `60/40`), une banque de candidats (Databank) avec inspection côte à côte IS/OOS, prévisualisation du code source Java et promotion en 1 clic vers le catalogue Java. |
| **`WfaView.vue`** | `/wfa` | 1 582 | **Studio Walk-Forward Analysis (WFA)** : configuration des fenêtres glissantes ou ancrées (`inSampleDays`, `outOfSampleDays`), choix de la métrique d'optimisation (`SHARPE`, `PROFIT_FACTOR`, `CALMAR`, `NET_PROFIT`), grilles de paramètres dynamiques, calcul du verdict automatique (`ROBUST` si WFE $\ge 60\%$ et Sharpe OOS $\ge 1,0$, `MODERATE`, `OVERFITTED`), frise `WfaTimeline` et matrice `WfaParameterStability`. |
| **`DataManagerView.vue`** | `/data-manager` | 1 208 | **Gestionnaire de Données Multi-Actifs** : couvre **8 unités de temps** (`m1`, `m5`, `m15`, `m30`, `h1`, `h4`, `d1`, `w1`), **3 classes d'actifs** (Forex Majors, CME Micro/Mini Futures, Actions US) et **4 fournisseurs** (`yahoo`, `dukascopy`, `oanda`, `ibkr`). Suivi en direct des tâches d'ingestion (`800 ms`) et ouverture du tiroir `UniverseManagerDrawer`. |
| **`StrategiesView.vue`** | `/strategies` | 549 | **Catalogue Multi-Actifs** : exploration des stratégies groupées en 5 pôles métiers (`🤖 StrategyQuant & Generated Models`, `⚡ CME Futures (IBKR)`, `🏛️ Prop Mechanical Desk`, `📈 Systematic Multi-Asset / Long-Term`, `💱 Forex Strategies`) avec filtres persistants et lancement direct vers `/dashboard` ou `/wfa`. |
| **`BacktestHistoryView.vue`** | `/results` | 534 | **Archives & Optimisation SQLite** : liste paginée/filtrable des backtests enregistrés, tiroir d'aperçu rapide, suppression unitaire ou globale, onglet **Parameter Heatmap** (`ParameterSensitivityHeatmap`) et onglet **Pareto Frontier** (`ParetoFrontierChart`). |
| **`ResultsView.vue`** | `/results/:runId` | 936 | **Analyse Détaillée d'Exécution** : suivi temps réel WebSocket (`useRunWebSocket`), éditeur de seuils de promotion, et 4 onglets (`Overview`, `Price Chart` avec chargement progressif de l'historique, `Monte Carlo` 1 000 tirages serveur + 100 trajectoires client, et `Trades`). |
| **`LiveTradingView.vue`** | `/live-trading` | 1 804 | **Desk d'Exécution Paper & Live** : gestionnaire multi-courtiers **OANDA** (v20) et **Interactive Brokers (IBKR)** (TWS / IB Gateway ports `7497`/`7496`/`4002` avec test de connexion en direct), convertisseur de devises temps réel (`USD`, `CAD`, `EUR`, `GBP`, `JPY`), jauge d'utilisation de marge, bouton d'arrêt d'urgence (*Kill Switch*) et carnet d'ordres de sentiment (`OrderBook`). |
| **`CompareView.vue`** | `/compare` | 1 578 | **Comparateur & Audit de Dérive** : superposition jusqu'à 4 courbes d'équité (`Vue Globale`), comparaison hebdomadaire (`Par semaines` via `/weekly-stats`), et audit **Alignement & Drift** (`/alignment`) comparant un run `PAPER`/`LIVE` à son `BACKTEST` de référence (score d'alignement %, dérive temporelle/prix, anomalies `MATCHED`, `MISSING_LIVE`, `GHOST_LIVE`, `TIME_DRIFT`, `PRICE_DRIFT`). |

---

## 3. Composants Réutilisables (`desktop/src/components/` — 15 Composants)

### 3.1 Graphiques & Visualisation Quantitative
| Composant | Lignes | Description Technique |
| :--- | ---: | :--- |
| **`TradeChart.vue`** | 834 | Graphique de prix OHLC (`lightweight-charts`) avec superpositions d'indicateurs (`SMA`, `EMA`, `BB`, `RSI`, `ATR`), marqueurs d'achats/ventes, lignes de positions actives (Entry/SL/TP) et d'ordres en attente, lignes Bid/Ask en direct, bascule UTC/Local, précision décimale adaptative (`2`/`3`/`5` décimales), nettoyage des doublons de rollover (`getCleanBars`) et défilement arrière infini (`loadMoreBars`). |
| **`EquityChart.vue`** | 356 | Courbe de capital et histogramme de drawdown. Prend en charge la **division visuelle In-Sample / Out-of-Sample (`oosSplitIndex`, `oosSplitRatio`)** avec double série `AreaSeries` (bleu IS vs émeraude OOS), ligne de séparation verticale et bandeaux d'état synchronisés. |
| **`MonteCarloChart.vue`** | 216 | Rendu simultané de 100 trajectoires d'équité simulées par permutation aléatoire des trades (mise en valeur des percentiles P5 rouge, P50 bleu, P95 vert) sous la courbe d'équité réelle. |
| **`ParameterSensitivityHeatmap.vue`** | 269 | Matrice de chaleur 2D interactive permettant de croiser deux paramètres de stratégie selon une métrique cible (`sharpeRatio`, `profitFactor`, `netProfit`, `maxDrawdown`). |
| **`ParetoFrontierChart.vue`** | 308 | Nuage de points SVG interactif et enveloppe convexe de la frontière de Pareto (`Sharpe Ratio` vs `Max Drawdown`) permettant d'ouvrir directement les runs non-dominés. |
| **`WfaTimeline.vue`** | 290 | Frise chronologique horizontale visualisant chaque pli (fold) Walk-Forward (segment IS 70 % bleu + segment OOS 30 % vert) avec badges Sharpe IS/OOS et efficacité WFE %. |
| **`WfaParameterStability.vue`** | 293 | Matrice de stabilité paramétrique sur l'ensemble des plis WFA, calculant la moyenne, l'écart-type et le coefficient de variation (`CV < 35 %` jugé stable vs alerte de dérive). |
| **`OrderBook.vue`** | 253 | Visualisation du carnet de positionnement client OANDA (`/api/sentiment/:instrument`, rafraîchi toutes les 15 s) affichant les poches acheteuses (Long) et vendeuses (Short) autour du prix courant. |

### 3.2 Formulaires, Catalogue & Gouvernance
| Composant | Lignes | Description Technique |
| :--- | ---: | :--- |
| **`BacktestForm.vue`** | 739 | Formulaire multi-actifs intégrant `StrategySelector`, `useCostPresets` (auto-sélection des commissions/slippage selon l'instrument), sélection de panier multi-symboles avec garde-fou `isCompatibleBasket`, choix du timeframe (`M1`..`W1`), raccourcis de dates (`YTD`, `1Y`, `3Y`, `5Y`, `MAX`), paramètres dynamiques et sélecteur de compte courtier. |
| **`StrategySelector.vue`** | 629 | Sélecteur modal/popover de stratégies accessible au clavier (`⌘K` / `Ctrl+K`) avec filtres par classe d'actifs, style de trading et famille algorithmique. |
| **`StrategyCard.vue`** | 369 | Carte de présentation d'une stratégie (badges de classe d'actifs, symboles et timeframes recommandés, indicateurs utilisés, aperçu des paramètres, boutons d'action rapide `Run Backtest` et `WFA Check`). |
| **`UniverseManagerDrawer.vue`** | 635 | Tiroir latéral de gestion du référentiel d'instruments (`/api/instruments`) : ajout de symboles personnalisés (valeur du point, taille de tick), suppression et importation en 1 clic de paniers d'actions prédéfinis (`Small-Cap Core`, `High-Beta Momentum`, `Mega-Cap Leaders`). |
| **`PromoteModal.vue`** | 251 | Modale de gouvernance pour promouvoir une stratégie (`BACKTEST` $\rightarrow$ `PAPER` $\rightarrow$ `LIVE`) avec vérification automatique des portes de promotion et justification obligatoire en cas de dérogation. |
| **`KpiStrip.vue`** | 150 | Grille de 8 cartes KPI (Rendement Net, Max DD, Sharpe, Sortino, Taux de réussite, Profit Factor, Espérance, Nombre de trades) avec seuils de couleur institutionnels. |
| **`TradeTable.vue`** | 189 | Tableau paginé (50 lignes/page), triable et filtrable des transactions exécutées, avec export CSV intégré. |

---

## 4. Composables & Typage (`desktop/src/composables/`, `desktop/src/types/`)

| Fichier | Lignes | Responsabilité |
| :--- | ---: | :--- |
| **`src/types/control-plane.ts`** | 329 | Interfaces TypeScript complètes des contrats REST et WebSocket du Control Plane Java. |
| **`src/composables/useControlPlane.ts`** | 242 | Client HTTP REST centralisé (runs, WFA, générateur, stratégies, courtiers, données historiques, instruments, backtests SQLite). |
| **`src/composables/useStrategyCatalog.ts`** | 325 | Classification multi-actifs, persistance `localStorage` des filtres et validation de compatibilité des paniers (`isCompatibleBasket`). |
| **`src/composables/useCostPresets.ts`** | 166 | Présélections institutionnelles de coûts de transaction et de dimensionnement (`CME_MICRO_FUTURES`, `CME_FULL_FUTURES`, `IBKR_FUTURES_TIERED`, `OANDA_FOREX_CORE`, `OANDA_SPOT_GOLD`, `IBKR_EQUITY_TIERED`). |
| **`src/composables/useRunWebSocket.ts`** | 88 | Abonnement WebSocket temps réel `/ws/runs/:runId` avec reconnexion automatique. |
| **`src/composables/useStatusBar.ts`** | 39 | Gestionnaire réactif des notifications de la barre d'état inférieure. |
| **`src/composables/controlPlaneConfig.ts`** | 4 | Pointeur réactif vers l'URL du Control Plane (`http://localhost:8080`). |
