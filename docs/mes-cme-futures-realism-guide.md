# Guide Pratique & Référence : CME Futures Realism, Intégration IBKR & Taxonomie Multi-Actifs

> **Cible** : Développeurs, quants, opérateurs de plateforme et traders.  
> **Périmètre** : Modules `trading-core`, `trading-backtest`, `trading-data`, `trading-strategies`, `desktop/` (Épics 43, 44, 45).  
> **Dernière mise à jour** : Août 2026

---

## 1. Vue d'Ensemble & Raison d'Être

Traditionnellement, les moteurs de backtesting forex ou actions souffrent de **biais d'exécution majeurs** lorsqu'ils sont appliqués aux contrats à terme (Futures CME) tels que le **Micro E-mini S&P 500 (`MES`)** :

1. **Biais de "Touch Fill"** : Un ordre limite est considéré exécuté dès que le plus haut/bas de la barre effleure le prix, gonflant artificiellement le taux de réussite.
2. **Ignorance des ticks discrets** : Les prix et stops calculés au centième ($0.01) ne respectent pas le tick minimum de $0.25 (valeur de $1.25 par tick sur MES).
3. **Rollover synthétique sans friction** : Les transitions trimestrielles de contrat ne comptaient pas les commissions d'écartement (double leg) ni le slippage de calendrier.
4. **Déphasage horaire UTC/ET** : L'importation de barres sans prise en compte des changements d'heure (EST/EDT) générait un décalage de 4 à 5 heures.
5. **Règles réglementaires inappropriées** : Application indue de la règle FINRA PDT ($25,000 min) aux contrats futures et forex régulés par la CFTC.

Cette mise à jour majeure (Épics 43 à 45) apporte une **parité d'exécution institutionnelle** entre le backtest, le paper trading et le live trading sur Interactive Brokers (IBKR) et OANDA.

---

## 2. Fonctionnalités Clés du Moteur Futures & Backtest Realism

### 2.1 Quantisation des Ticks & Modèle de Valorisation
* **Enregistrement des contrats (`FuturesRegistry`)** : Chaque contrat futures (`MES`, `MNQ`, `M2K`, `MYM`, etc.) définit son `minTick` ($0.25 sur MES) et sa `tickValue` ($1.25).
* **Quantisation stricte** : Tous les ordres d'entrée, Stop Loss et Take Profit sont alignés sur les ticks valides via `FuturesRegistry.quantizePrice(symbol, price)`. Tout prix hors-tick est rejeté ou automatiquement ajusté.
* **PnL par tick entier** : Les PnL calculés sont obligatoirement des multiples de la valeur du tick.

```java
// Exemple d'alignement de tick
double orderPrice = 5012.3333;
double validTickPrice = FuturesRegistry.quantizePrice("MES", orderPrice); // -> 5012.25
```

### 2.2 Mode d'Exécution `FillMode.TRADE_THROUGH`
Pour éliminer le biais d'exécution des ordres limites, le moteur supporte deux modes :
* `FillMode.TOUCH` : Exécution dès contact (mode optimiste / legacy).
* `FillMode.TRADE_THROUGH` (**Défaut recommandé pour la qualification**) : Un ordre d'achat limite à $5000.00 n'est exécuté que si le `low` de la barre est strictement inférieur ($< 5000.00$), garantissant qu'au moins 1 tick a traversé le carnet d'ordres.

```java
BacktestEngine engine = new BacktestEngine()
    .withFillMode(FillMode.TRADE_THROUGH)
    .withSlippagePoints(0.25) // 1 tick de slippage moyen
    .withCommissionPerContract(0.62); // Commission CME/IBKR
```

### 2.3 Calendrier de Session CME Globex (`CmeGlobexMarketCalendar`)
Simulation fidèle des horaires électroniques du Chicago Mercantile Exchange :
* **Ouverture** : Dimanche à 18:00 ET (23:00 / 22:00 UTC selon saison).
* **Fermeture** : Vendredi à 17:00 ET.
* **Pause quotidienne de maintenance** : Lundi au Jeudi de 17:00 à 18:00 ET (aucun trade accepté).
* **Classification des sessions** :
  - **RTH (Regular Trading Hours)** : 09:30 à 16:00 ET (Monday–Friday).
  - **ETH (Extended Trading Hours)** : Nuit / sessions électroniques.
  - **CLOSED** : Samedi et fenêtres de maintenance.

### 2.4 Friction & Comptabilité des Rollovers de Contrats
Lors des expirations trimestrielles (Mars, Juin, Septembre, Décembre) :
* Fermeture de l'ancienne échéance et ouverture de la nouvelle.
* Application de la double commission (2 legs) et du slippage de spread de calendrier (1 tick min).
* Enregistrement sans distorsion des métriques de ratio de gain/perte (Win Rate) grâce au tag de groupe `rolloverGroupId`.

### 2.5 Exemption FINRA Pattern Day Trader (PDT)
Dans `MarginTracker.java`, les comptes négociant la classe d'actifs `AssetClass.FUTURES` ou `AssetClass.FOREX` sont exemptés de la restriction des 3 day trades par période de 5 jours imposée aux actions américaines sous $25k.

### 2.6 Dimensionnement Volatilité-Adaptatif (`AtrFuturesPositionSizer`)
Calcul dynamique des tailles de lots et distances de stop basées sur l'ATR (Average True Range) :
* Calcul de la distance en points quantisée en ticks.
* Calcul du nombre entier de contrats respectant le budget de risque et l'exigence de marge initiale.

---

## 3. Intégration Interactive Brokers (IBKR) & Outillage CLI

### 3.1 Client API TWS Embarqué (Pure Java)
Le client TWS IBKR a été intégré directement dans `trading-data` (package `com.ib.client`) sans dépendance Protobuf conflictuelle, garantissant une compilation Maven ultra-propre et sans conflit d'ombrage.

### 3.2 Outil Interactif `ConnectorRunner`
Un menu CLI complet permet de configurer et tester la passerelle IBKR ainsi que les flux de données :

```bash
# Lancer le ConnectorRunner
mvn compile -q -pl trading-core,trading-data -am
mvn exec:java -pl trading-data \
  -Dexec.mainClass=com.martinfou.trading.data.ConnectorRunner
```

**Options disponibles** :
1. ForexFactory Economic Calendar
2. CFTC Commitment of Traders (COT)
3. Sentiment OANDA
4. Analyse de saisonnalité
5. Outlook de marché consolidé
6. **Setup de connexion IBKR (`IbkrConfigurationTool`)** : Test de connectivité TWS/Gateway sur les ports standard (`7496`, `7497`, `4001`, `4002`).
7. **Téléchargement de données historiques IBKR (`IbkrHistoricalDataDownloader`)** : Export de barres 1-minute / 1-heure / Daily normalisées en UTC.

### 3.3 Normalisation des Fuseaux Horaires (`America/New_York` → UTC)
`IbkrHistoricalDataLoader.java` prend en charge l'analyse automatique des chaînes de dates IBKR avec prise en compte du fuseau `America/New_York` et des bascules automatiques EDT/EST (Daylight Saving Time), évitant tout décalage temporel avec les données Forex/OANDA.

---

## 4. Interface Bureau Electron / Vue 3 (Desktop)

### 4.1 Sélecteur de Stratégies Interactif (`StrategySelector.vue`)
* **Taxonomie & Filtres** : Recherche instantanée par classe d'actifs (`FOREX`, `FUTURES`, `EQUITIES`), style (`MOMENTUM`, `BREAKOUT`, `MEAN_REVERSION`), timeframe (`M5`, `M15`, `H1`, `D1`), et régime de volatilité (`LOW_VOL`, `HIGH_VOL`, `ANY`).
* **Avertissement d'Isolation de Panier** : Détection et notification visuelle si une stratégie mono-actif est lancée sur un panier multi-symboles sans compatibilité cross-actifs.
* **Inspection des Paramètres** : Visualisation en temps réel des valeurs par défaut et métadonnées de la stratégie sélectionnée.

### 4.2 Presets Automatiques de Coûts & Dimensionnement (`useCostPresets.ts`)
Sélectionner un symbole applique automatiquement les paramètres réalistes de courtage :
* **CME Futures (MES, MNQ)** : Commission de $0.62/contrat, Slippage de 0.25 pt, Multiplicateur de 5.0, Mode `TRADE_THROUGH`.
* **Forex (EUR/USD, USD/JPY)** : Spread typique de 1.2 pips, Slippage de 0.5 pip, Multiplicateur de 100,000, Mode `TOUCH` / `TRADE_THROUGH`.
* **US Equities** : Commission de $0.005/action, Slippage de $0.01, Marge de 50%.

### 4.3 Gestionnaire d'Univers & Mini-Caps (`UniverseManagerDrawer.vue`)
* Tiroir dédié dans `DataManagerView` pour créer, éditer et persister les paniers d'instruments dans SQLite (`SqliteUniverseStore`).
* Ingestion et synchronisation des barres 2006–2026 pour les indices CME et les Small Caps (IJR).

### 4.4 Standardisation de l'Affichage Temporel
Toutes les vues (`TradeTable`, `BacktestHistoryView`, `CompareView`, `DashboardView`, `LiveTradingView`, `ResultsView`, `WfaView`) affichent désormais les dates sous un format standardisé et non ambigu : `yyyy-MM-dd HH:mm`.

---

## 5. Recette Rapide : Lancer un Backtest Futures Réaliste

```java
// 1. Initialiser le contrat et le calendrier
FuturesContract mes = FuturesRegistry.get("MES");
List<Bar> bars = IbkrHistoricalDataLoader.loadCsv(
    Path.of("data/historical/MES_H1_2020_2026.csv"), 
    "MES", 
    ZoneId.of("America/New_York")
);

// 2. Configurer le moteur avec réalisme maximal
BacktestEngine engine = new BacktestEngine()
    .withSymbol("MES")
    .withInitialBalance(10_000.0)
    .withFillMode(FillMode.TRADE_THROUGH)
    .withCommissionPerContract(0.62)
    .withSlippagePoints(0.25)
    .withMarginCheck(true);

// 3. Exécuter la stratégie
RunResult result = engine.run(new MyFuturesStrategy(), bars);

// 4. Analyser les résultats
System.out.printf("Sharpe: %.2f | Max DD: %.2f%% | Total PnL: $%.2f%n",
    result.metrics().sharpeRatio(),
    result.metrics().maxDrawdownPct(),
    result.metrics().netProfit()
);
```
