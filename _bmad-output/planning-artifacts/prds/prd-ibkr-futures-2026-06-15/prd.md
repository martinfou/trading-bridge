---
title: Epics 29 & 30 — Interactive Brokers (IBKR) Futures Trading & Backtesting
status: final
created: 2026-06-15
updated: 2026-06-15
---

# PRD: Epics 29 & 30 — Interactive Brokers (IBKR) Futures Trading & Backtesting

## 0. Document Purpose
Ce document définit les exigences fonctionnelles et techniques pour le support du trading de contrats à terme (Futures), spécifiquement le **Micro E-mini S&P 500 (MES)**, via le courtier **Interactive Brokers (IBKR)** au sein de Trading Bridge, ainsi que le moteur de backtest associé. 
Ce PRD s'adresse aux développeurs de la plateforme pour implémenter le moteur Java (`trading-backtest`, `trading-data`, `trading-broker`) et l'IHM desktop (Electron/Vue 3). Il intègre les concepts clés des Futures (multiplicateurs, rollover, marges) et s'appuie sur l'API native Java d'IBKR (TWS API). Les détails purement techniques d'implémentation de la TWS API sont déportés dans [addendum.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/addendum.md).

## 1. Vision
Martin souhaite diversifier ses stratégies de trading au-delà du Forex en y intégrant les Futures américains, à commencer par le Micro E-mini S&P 500 (MES). Pour ce faire, Trading Bridge doit être capable d'importer les données historiques directement depuis IBKR pour réaliser des backtests réalistes et précis. Le moteur de backtest doit simuler les spécificités des Futures : calcul des gains basé sur le multiplicateur du contrat (5$ par point pour le MES), appels de marge, et transition glissante automatique des contrats (rollover) pour simuler des périodes pluriannuelles.
Enfin, le système doit permettre de déployer ces stratégies en mode Paper/Live avec exécution asynchrone des ordres au marché et réconciliation rigoureuse des frais de transaction et commissions réelles fournies par l'API IBKR.

## 2. Target User

### 2.1 Primary Persona
* **Martin** : Quantitative trader indépendant qui conçoit et exécute des stratégies automatiques. Il souhaite backtester et déployer sa stratégie MES avec une précision prop-shop directement depuis son IHM desktop.

### 2.2 Jobs To Be Done
* **Alimentation autonome** : Télécharger l'historique MES depuis IBKR sans dépendre de fichiers CSV externes.
* **Simuler avec fidélité** : Backtester des stratégies sur plusieurs années en tenant compte du rollover automatique des échéances trimestrielles et du multiplicateur de point de 5$.
* **Gérer le risque de marge** : Monitorer et simuler la marge requise pour éviter les liquidations forcées par le courtier.
* **Exécuter de façon fiable** : Acheminer les ordres au marché vers IBKR (TWS/Gateway) et comptabiliser les frais réels.

### 2.3 Key User Journeys

* **UJ-1 : Backtest complet d'une stratégie MES**
  * **Persona + context** : Martin veut valider une nouvelle stratégie sur le MES sur les 3 dernières années.
  * **Entry state** : Connecté à l'IHM Desktop, section Backtest.
  * **Path** :
    1. Martin sélectionne le symbole de référence `MES`.
    2. Il définit la période (ex: 2023-2026).
    3. Le système vérifie si les données historiques locales sont complètes. Si non, il télécharge automatiquement les bougies manquantes directement depuis IBKR.
    4. Il lance le backtest en configurant le Rollover Glissant Automatique (Série de Prix Continue).
  * **Climax** : Le rapport de backtest s'affiche, montrant un profil de courbe d'équité continu qui prend en compte les glissements de contrats et le multiplicateur de point.
  * **Resolution** : Martin peut analyser les performances et décider de promouvoir la stratégie.

* **UJ-2 : Promotion et Exécution Live/Paper**
  * **Persona + context** : Martin promeut sa stratégie MES validée vers son compte IBKR Paper.
  * **Entry state** : Stratégie validée dans l'IHM, écran de promotion.
  * **Path** :
    1. Martin sélectionne l'environnement `PAPER_IBKR`.
    2. Le RunManager de Trading Bridge initialise la session TWS via le connecteur IBKR.
    3. Lorsque la stratégie émet un ordre d'achat au marché, le connecteur résout automatiquement l'échéance active du MES et envoie l'ordre à la TWS.
  * **Climax** : L'IHM affiche la position ouverte et met à jour en temps réel l'équité du compte IBKR et les frais réels réconciliés.
  * **Resolution** : La stratégie tourne en tâche de fond et Martin garde le contrôle grâce au Kill Switch.

## 3. Glossary
* **MES** : Micro E-mini S&P 500 Futures, contrat à terme sur l'indice S&P 500 traded sur le CME.
* **Multiplicateur (Multiplier)** : Facteur d'échelle pour calculer le PnL. Pour le MES, 1 point = 5 USD. Le PnL est calculé par : `(PrixSortie - PrixEntree) * Quantite * 5`.
* **Rollover** : Action de clôturer la position sur le contrat arrivant à expiration pour en ouvrir une équivalente sur le contrat d'échéance suivante.
* **Série de Prix Continue (Continuous Price Series)** : Courbe synthétique de prix construite en raccordant les contrats trimestriels successifs du MES.
* **Marge Initiale / Marge de Maintenance** : Capital requis pour initier (Marge Initiale) et maintenir (Marge de Maintenance) une position sur Futures.
* **TWS / IB Gateway** : Applications clientes d'Interactive Brokers servant de passerelle de communication API.
* **CommissionReport** : Message asynchrone émis par l'API IBKR détaillant le coût exact de commission pour un ordre exécuté.

## 4. Features

### 4.1 Ingestion de données historiques (IBKR Historical Data Ingest)
**Description :** Trading Bridge doit pouvoir télécharger des bougies historiques (candlesticks) directement depuis les serveurs d'IBKR en utilisant l'API TWS/Gateway, éliminant les imports CSV manuels pour les Futures.
[ASSUMPTION : L'API d'IBKR requiert une connexion TWS/Gateway active pour effectuer des requêtes de données historiques].

**Functional Requirements :**
* #### FR-1 : Téléchargement asynchrone de bougies
  * L'utilisateur peut demander le chargement de données historiques pour le contrat `MES` sur une plage temporelle donnée. Realise UJ-1.
  * **Consequences (testable) :**
    * Le système télécharge les bougies (Timeframe: 1m, 1h, 1d) via `reqHistoricalData` et les stocke dans le répertoire local `data/historical/`.
    * En cas d'échec de connexion à la Gateway, le système lève une exception claire "IB Gateway not reachable".
* #### FR-2 : Constitution de la Série de Prix Continue historique
  * Le chargeur de données doit assembler les échéances trimestrielles successives (Mars, Juin, Septembre, Décembre) pour créer une Série de Prix Continue sans look-ahead bias. Realise UJ-1.
  * **Consequences (testable) :**
    * Le système utilise la règle de Rollover Glissant Automatique : le basculement s'effectue à T-10 (10 jours calendaires avant la date d'expiration du contrat). [ASSUMPTION : 10 jours avant l'expiration est le standard optimal de liquidité pour le MES].
    * L'assemblage se fait par juxtaposition des prix bruts des échéances successives.
  * **Notes :**
    * *Gestion du gap de transition* : Pour le MVP, l'assemblage utilise les prix bruts des contrats successifs sans ajustement (backward/forward adjustment). L'ajustement des indicateurs techniques lors de la transition est reporté en v2.

---

### 4.2 Support des Futures dans le moteur de Backtest (Futures Backtesting Engine)
**Description :** Le module `trading-backtest` doit être étendu pour prendre en compte les spécificités des contrats Futures : valeur des points, appels de marge et rollovers.

**Functional Requirements :**
* #### FR-3 : Calcul de PnL basé sur le multiplicateur
  * Le moteur de backtest doit appliquer le multiplicateur de point pour toutes les transactions sur Futures. Realise UJ-1.
  * **Consequences (testable) :**
    * Pour le symbole `MES`, le PnL de chaque trade simulé est multiplié par `5.0`.
* #### FR-4 : Simulation des marges et liquidation
  * Le moteur de backtest doit simuler les exigences de Marge Initiale et de Marge de Maintenance et forcer la clôture des positions si les fonds propres (Equity) tombent sous la Marge de Maintenance globale. Realise UJ-1.
  * **Consequences (testable) :**
    * Marge Initiale fixe par contrat MES : `1500 USD` ; Marge de Maintenance : `1200 USD`. [ASSUMPTION : Ces exigences de marges sont configurables dans `config.yaml` et reflètent les exigences de marge typiques d'IBKR].
    * Si `Equity < (PositionQuantity * MaintenanceMargin)`, le moteur génère un événement de liquidation forcée au cours d'ouverture de la bougie suivante.
* #### FR-5 : Exécution du rollover en cours de simulation
  * Pendant la simulation d'un backtest pluri-annuel, le moteur doit automatiquement exécuter le Rollover Glissant Automatique : fermer la position sur le contrat expirant et réouvrir la position sur le nouveau contrat au cours de clôture de la bougie de transition, en appliquant des frais de transaction doubles. Realise UJ-1.
  * **Consequences (testable) :**
    * L'événement de rollover est consigné dans le rapport de backtest (`RunEvent`).
  * **Arbitrage de conception :**
    * *Option A (Sélectionnée) : Rollover Glissant Automatique (Série de Prix Continue)* — Le moteur bascule les positions et les indicateurs de manière transparente à T-10 de l'expiration. Indispensable pour évaluer des stratégies de position sur plusieurs années.
    * *Option B (Rejetée pour le MVP) : Backtest par échéance individuelle* — Lancer des backtests fragmentés par contrat sans raccordement. Rejeté car cela ne permet pas une évaluation de la performance globale continue.

---

### 4.3 Connecteur d'exécution IBKR Futures (IBKR Broker Connector)
**Description :** Le module `trading-broker` doit acheminer les ordres réels/paper vers la TWS/IB Gateway et assurer le suivi asynchrone des transactions et commissions.

**Functional Requirements :**
* #### FR-6 : Résolution de contrat et soumission d'ordres
  * Lors de la soumission d'un ordre pour le symbole `MES`, le connecteur doit générer un objet `Contract` IBKR de type `FUT` ciblant l'échéance active. Realise UJ-2.
  * **Consequences (testable) :**
    * L'objet `Contract` envoyé à la Gateway a les propriétés suivantes : `secType = "FUT"`, `symbol = "MES"`, `exchange = "CME"`, `currency = "USD"`, `multiplier = "5"`, `lastTradeDateOrContractMonth = [Active Contract Month]`.
* #### FR-7 : Suivi asynchrone des Fills
  * Le connecteur doit intercepter les événements d'exécution de la TWS API et mettre à jour le statut des ordres en local de façon thread-safe. Realise UJ-2.
  * **Consequences (testable) :**
    * Les callbacks `execDetails` sont capturés et convertis en `BrokerEvent.fill` contenant l'ID d'exécution `execId` unique d'IBKR.
* #### FR-8 : Réconciliation des frais réels
  * Le connecteur doit associer les rapports de commissions asynchrones aux exécutions correspondantes. Realise UJ-2.
  * **Consequences (testable) :**
    * Les callbacks `commissionReport` sont écoutés et associés aux fills via l'`execId` afin d'enregistrer la commission réelle et le PnL réalisé dans le journal de transactions local.
  * **Notes :**
    * *Structure des frais réels* : Pour le MVP, les commissions sont simulées en backtest à hauteur de frais fixes de 0.87 USD par contrat et par transaction (valeur par défaut dans `config.yaml`), tandis que l'exécution réelle/paper utilise les commissions asynchrones exactes d'IBKR.

---

### 4.4 Intégration Dashboard & IHM (Desktop Integration)
**Description :** L'IHM Desktop (Electron/Vue 3) doit afficher les informations de compte et de positions Futures et permettre de configurer les paramètres de backtest Futures.

**Functional Requirements :**
* #### FR-9 : Visualisation des métriques de marge
  * L'écran du Live Room doit afficher les indicateurs de marge spécifiques aux Futures en cas de sélection d'un compte IBKR. Realise UJ-2.
  * **Consequences (testable) :**
    * Affichage distinct de : Marge Initiale Actuelle, Marge de Maintenance Actuelle, et Marge Libre (Buying Power).
  * **Interface de Données (API Contract) :**
    * Le backend expose l'endpoint HTTP GET `/api/ibkr/account-summary` renvoyant le DTO de compte incluant `balance`, `equity`, `initMarginReq`, `maintMarginReq`, et `buyingPower`.
* #### FR-10 : Configuration du backtest Futures
  * L'utilisateur peut configurer le multiplicateur et la marge du contrat directement depuis le formulaire de configuration de backtest. Realise UJ-1.
  * **Consequences (testable) :**
    * Des champs d'options pour les Futures apparaissent si le symbole sélectionné est de type Futures.

## 5. Non-Goals (Explicit)
* **Pas de support multi-devises complexe (v1)** : Tous les calculs de marge et de PnL pour le MES sont supposés s'effectuer en USD.
* **Pas de Smart Routing pour les Futures** : Les contrats Futures MES sont acheminés exclusivement vers l'échange CME.
* **Pas d'autres types d'ordres que MARKET** : Seuls les ordres au marché (MARKET) sont supportés pour le connecteur IBKR en v1. Les ordres LIMIT/STOP/trailing-stop sur Futures sont hors de portée.
* **Pas de rollover automatique en Live (v1)** : Le Rollover Glissant Automatique s'applique uniquement au Backtest. En trading réel/paper, Martin est responsable de basculer manuellement sa stratégie sur la nouvelle échéance active dans l'IHM.

## 6. MVP Scope

### 6.1 In Scope
* Support exclusif du contrat Micro E-mini S&P 500 (`MES`).
* Moteur de backtest gérant le multiplicateur de point (=5), les frais, les marges (initiale 1500$, maintenance 1200$) et les rollovers trimestriels automatiques (Rollover Glissant Automatique).
* Téléchargement de données historiques MES via l'API IBKR pour alimenter le backtest.
* Connecteur d'exécution IBKR (`PAPER_IBKR` et `LIVE_IBKR`) pour les ordres MARKET avec réconciliation asynchrone des fills et des commissions (`execDetails` / `commissionReport`).
* Affichage des métriques de marge spécifiques aux Futures sur le Dashboard.

### 6.2 Out of Scope for MVP
* Support d'autres contrats Futures (ex: `NQ`, `GC`, `CL`). [NOTE FOR PM : Facile à étendre en v2 en enrichissant un fichier de configuration des contrats `futures-contracts.json`].
* Rollover automatisé des positions actives en production (Live/Paper).
* Ordres complexes (LIMIT, STOP, Bracket orders) sur IBKR.

## 7. Success Metrics
* **SM-1 : Précision du PnL simulé** : La différence de PnL calculée entre le Backtest Engine et un rapport de trading réel sur le même contrat MES avec les mêmes prix d'exécution doit être de **0%** (hors slippage aléatoire). Validates FR-3.
* **SM-2 : Réconciliation des commissions** : 100% des ordres exécutés sur `PAPER_IBKR`/`LIVE_IBKR` doivent avoir leur commission finale réconciliée dans la base de données locale sous 5 secondes après le fill. Validates FR-8.
* **SM-C1 (Contre-métrique)** : L'ajout du calcul des marges et du rollover ne doit pas ralentir la vitesse d'exécution d'un backtest standard (Forex) de plus de **5%**.

## 8. Open Questions
*Aucune question ouverte bloquante pour l'implémentation de la v1. Les décisions de conception clés (modèle de frais fixe à 0.87 USD, prix de transition brut) ont été validées et intégrées directement dans les exigences fonctionnelles.

## 9. Assumptions Index
* **ASSUMPTION-1 (API Active)** : L'API d'IBKR requiert une connexion TWS/Gateway active pour effectuer des requêtes de données historiques (FR-1).
* **ASSUMPTION-2 (Standard de roll à T-10)** : 10 jours avant l'expiration est le standard optimal de liquidité pour effectuer le rollover du MES (FR-2).
* **ASSUMPTION-3 (Paramètres de marge)** : Les exigences de marges (1500$ initiale / 1200$ maintenance) sont représentatives et suffisantes pour la v1 (FR-4).
