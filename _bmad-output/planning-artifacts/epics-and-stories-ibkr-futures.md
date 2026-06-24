---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/prd.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/addendum.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/architecture-ibkr-futures.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/project-context.md"
---

# Trading Bridge - Epics 29 & 30 (Interactive Brokers Futures Trading & Backtesting) Breakdown

## Overview

This document provides the complete epic and story breakdown for Epics 29 & 30 in Trading Bridge, decomposing the requirements from the PRD, technical addendum, and Architecture decisions into implementable stories.

## Requirements Inventory

### Functional Requirements

- **FR-1 : Ingestion asynchrone des bougies MES** : Téléchargement des bougies (1m, 1h, 1d) via `reqHistoricalData` et stockage dans `data/historical/`. Lève une exception claire si la Gateway n'est pas joignable.
- **FR-2 : Constitution de la Série de Prix Continue** : Assemblage des contrats trimestriels successifs à T-10 de l'expiration sans lissage/ajustement de prix (juxtaposition des prix bruts).
- **FR-3 : Calcul de PnL basé sur le multiplicateur** : Application d'un multiplicateur de point fixe (=5.0 pour le MES) pour toutes les transactions Futures en simulation.
- **FR-4 : Simulation des marges et liquidation** : Exigences statiques locales de Marge Initiale (1500 USD) et de Marge de Maintenance (1200 USD) configurables, avec liquidation automatique au cours d'ouverture de la bougie suivante si l'équité passe sous le seuil global.
- **FR-5 : Exécution du rollover en cours de simulation** : Clôture à T-10 de l'échéance active et réouverture immédiate sur le nouveau contrat de transition, en appliquant des commissions doubles.
- **FR-6 : Résolution de contrat et soumission d'ordres** : Génération asynchrone d'un objet `Contract` de type `FUT` sur CME et soumission d'ordres MARKET via l'API TWS d'IBKR.
- **FR-7 : Suivi asynchrone des Fills** : Interception asynchrone des callbacks `execDetails` et conversion en `BrokerEvent.fill` contenant l'ID `execId`.
- **FR-8 : Réconciliation des frais réels** : Association asynchrone de `commissionReport` aux fills via l'`execId` pour inscrire les frais exacts dans le journal local.
- **FR-9 : Visualisation des métriques de marge** : Affichage distinct des exigences de Marge Initiale, Marge de Maintenance et Marge Libre dans le Live Room via l'endpoint `/api/brokers/{brokerId}/account-summary`.
- **FR-10 : Configuration du backtest Futures** : Options IHM dans le formulaire de backtest pour spécifier le multiplicateur, la marge initiale/maintenance et les frais.

### NonFunctional Requirements

- **SM-1 : Précision du PnL simulé** : Écart de PnL calculé de 0% entre le simulateur et l'exécution réelle (hors slippage aléatoire).
- **SM-2 : Réconciliation des commissions** : Corrélation et enregistrement de 100% des commissions en < 5 secondes en mode réel/paper (avec un timeout de secours à 500ms).
- **SM-C1 : Contre-métrique de performance** : Ralentissement de la vitesse du backtest Forex existant limité à moins de 5% après intégration des calculs de marges et de rollover.

### Additional Requirements

- **Spécifications statiques de contrat** : Chargement et validation de schéma au démarrage du système pour le fichier statique local `data/runtime/futures-contracts.json`.
- **AssetValuationModel** : Abstraction de la logique de valorisation (`AssetValuationModel`) avec les implémentations `ForexValuationModel` et `FuturesValuationModel` pour garantir l'absence de régression Forex (delta de 0.0).
- **Routage API générique** : Exposer des routes génériques découplées `/api/brokers/{brokerId}/account-summary` et `/api/portfolio/margins` dans `BrokerController` au lieu de contrôleurs spécifiques à un courtier.
- **Mock de socket TCP local** : Mise en place d'un simulateur léger de serveur TCP local (`MockTcpGatewayServer`) pour tester le connecteur de courtier en CI sans dépendre de la TWS active.
- **Groupement logique de Rollover** : Liaison des deux exécutions physiques de transition par un `rolloverGroupId` unique (UUID) et consolidation dans le journal des trades pour préserver la cohérence des statistiques de trading (Win Rate, nombre de trades).

### UX Design Requirements

*(Aucune exigence d'UX/IHM spécifique n'a été spécifiée dans cet Epic ; les maquettes réutilisent les structures de formulaires et composants de dashboard existants)*

### FR Coverage Map

- **FR-1** (Ingestion historique MES) : Epic 29 (Story 29.4)
- **FR-2** (Série Continue à T-10) : Epic 29 (Story 29.3)
- **FR-3** (PnL Multiplicateur MES x5.0) : Epic 29 (Story 29.1)
- **FR-4** (Simulation marges & liquidation) : Epic 29 (Story 29.2)
- **FR-5** (Rollover & double commission en simulation) : Epic 29 (Story 29.3)
- **FR-6** (Résolution de contrat & ordres MARKET FUT) : Epic 30 (Story 30.1)
- **FR-7** (Interception fills execDetails) : Epic 30 (Story 30.2)
- **FR-8** (Réconciliation Commissions) : Epic 30 (Story 30.2)
- **FR-9** (Visualisation marges Live Room) : Epic 30 (Story 30.3)
- **FR-10** (Formulaire configuration backtest) : Epic 29 (Story 29.1)

## Epic List

### Epic 29 : Moteur de Backtest Futures & Simulation des Risques (MES)
Cet épique ajoute les capacités hors-ligne de simulation des Futures au moteur de backtest existant. Il gère l'évaluation de portefeuille, le levier de marge et les transitions de contrats à T-10, sans modifier la suite Forex existante.
- **Stories incluses :** Story 29.1, Story 29.2, Story 29.3, Story 29.4
- **FRs couverts :** FR-1, FR-2, FR-3, FR-4, FR-5, FR-10

### Epic 30 : Exécution Réelle/Paper & Dashboard Temps Réel (IBKR)
Cet épique implémente le connecteur physique TWS asynchrone pour IBKR. Il permet d'émettre des ordres de futures au marché sur le CME, d'écouter les callbacks de fills et de réconciliation de frais réels, et d'exposer les métriques de marges à l'IHM via des endpoints génériques.
- **Stories incluses :** Story 30.1, Story 30.2, Story 30.3, Story 30.4
- **FRs couverts :** FR-6, FR-7, FR-8, FR-9

## Epic 29: Moteur de Backtest Futures & Simulation des Risques (MES)

### Story 29.1: Refactoring de valorisation d'actifs et Multiplicateur MES (PnL)

As a quantitative trader,
I want to configure the MES point value to 5.0 and calculate PnL correctly in backtests,
So that my strategy returns are accurate.

**Acceptance Criteria:**

**Given** a configuration file `data/runtime/futures-contracts.json` containing the multiplier `5.0` for `MES`.
**When** the backtest engine starts for `MES`.
**Then** the `FuturesRegistry` loads the configurations and validates its JSON schema.
**Given** the `AssetValuationModel` interface and its implementations `ForexValuationModel` and `FuturesValuationModel`.
**When** a position on `MES` is closed (e.g., BUY 1 at 4500, SELL 1 at 4510).
**Then** the PnL is calculated as `50.0` USD.
**When** the existing `GoldenBacktestTest` is run.
**Then** the PnL delta on Forex is strictly `0.0`.

### Story 29.2: Simulation des marges et Liquidation forcée

As a risk manager,
I want to simulate Initial and Maintenance margin requirements and enforce liquidation,
So that leverage risk is realistically simulated.

**Acceptance Criteria:**

**Given** an open position on `MES` with `1500` USD Initial Margin and `1200` USD Maintenance Margin requirements.
**When** the initial account equity is `2000` USD and an order is submitted.
**Then** the `MarginTracker` allows the order.
**When** the initial equity is `1000` USD.
**Then** the order is rejected.
**When** the simulated account equity drops to `1100` USD (below `1200` USD Maintenance Margin requirement).
**Then** the `BacktestEngine` triggers a market order to close the position at the next bar's open price, and logs a `LIQUIDATION` event in the report.

### Story 29.3: Série de prix continue et exécution de Rollover

As a long-term position trader,
I want to stitch trimestral contracts at T-10 and simulate rollover with double commissions,
So that my multi-year backtests are realistic.

**Acceptance Criteria:**

**Given** separate historical data files for `MESM6.csv` and `MESU6.csv`.
**When** the `DataLoader` loads the `MES` symbol.
**Then** it stitches them together at T-10 of ESM6 contract expiry, creating a raw price transition.
**Given** an open position on the expiring contract at T-10.
**When** the transition bar is processed.
**Then** the engine closes the position on Leg A, opens a position in the same direction on Leg B, links both transactions with a single `rolloverGroupId` (UUID), and charges double commission (flat 0.87 USD x 2).
**When** final trade reports are generated.
**Then** the two legs of the rollover are consolidated into a single logical trade line to prevent skewing trade count and win rate statistics.

### Story 29.4: Ingesteur de données historiques via TWS API

As a developer,
I want to download historical MES data directly from IBKR,
So that I can run backtests offline without manual CSV files.

**Acceptance Criteria:**

**Given** an active local IB Gateway or TWS connection.
**When** historical data is requested for `MES`.
**Then** the client calls `reqHistoricalData` for the resolved contract.
**When** callbacks `historicalData` and `historicalDataEnd` are received.
**Then** the returned candles are parsed and written under `data/historical/`.
**When** the gateway is not reachable.
**Then** the system fails-fast and logs `IllegalStateException: Failed to connect to IB Gateway`.

## Epic 30: Exécution Réelle/Paper & Dashboard Temps Réel (IBKR)

### Story 30.1: Résolution de contrat et soumission d'ordres MARKET FUT

As a trader,
I want to submit MARKET orders for Futures contracts (like MES) to IBKR CME,
So that my trading signals are executed immediately in the market.

**Acceptance Criteria:**

**Given** a running local `MockTcpGatewayServer` listening on an ephemeral port.
**When** `IbkrBroker` connects and sends a MARKET order request for `MES`.
**Then** the contract details are resolved as type `FUT` and exchange `CME`, and an order ID is requested via TWS API.
**When** the `MockTcpGatewayServer` receives a market order submission.
**Then** `IbkrBroker` records the order state as `SUBMITTED` internally without blocking the execution thread.
**And** no OANDA Forex components are modified or impacted (Forex delta remains strictly 0.0).

### Story 30.2: Interception des Fills et Réconciliation double barrière des commissions

As a portfolio manager,
I want to capture execution details (fills) and correlate them with exact commission reports,
So that my actual trading costs are precisely logged.

**Acceptance Criteria:**

**Given** a market order submitted via `IbkrBroker`.
**When** the TWS callback `execDetails` is triggered with a unique `execId`.
**Then** the engine maps it to a `BrokerEvent.fill` event.
**When** the corresponding `commissionReport` callback is received with the same `execId` within 500ms.
**Then** the broker reconciles the actual commission (e.g., 0.87 USD) and commits the fill with the exact fee to the database.
**When** the `commissionReport` is delayed by more than 500ms.
**Then** the reconciliation registry times out, logs a warning, falls back to a default fee (0.0 USD or a configured standard rate), and commits the fill without blocking the execution thread.
**And** unit tests verify the timeout asynchronously using a virtual clock/time mock.

### Story 30.3: Résumé de compte et Métriques de marge (API REST générique & cache)

As a risk manager,
I want to view margin requirements and account equity via generic API endpoints,
So that I can monitor account health from the Live Room.

**Acceptance Criteria:**

**Given** an active connection to TWS.
**When** TWS streams account summary events (`accountSummary`).
**Then** the values (Net Liquidation, Initial Margin, Maintenance Margin, Free Margin) are stored in an in-memory cache (`IbkrAccountCache`).
**When** a client queries the generic REST endpoint `/api/brokers/{brokerId}/account-summary` or `/api/portfolio/margins`.
**Then** the controller returns the cached values in a unified JSON DTO containing the account summary fields.
**And** the data structure decouples IBKR specifics from the control plane core.

### Story 30.4: Heartbeat de connexion, cache de fraîcheur et notifications temps réel (WebSocket & UI)

As a trader,
I want to be immediately warned when the connection to IBKR is lost or when account data is stale,
So that I do not make trading decisions based on outdated margin information.

**Acceptance Criteria:**

**Given** `IbkrAccountCache` tracking updates with UTC `Instant` timestamps.
**When** no EWrapper callback is received for more than 10 seconds.
**Then** the backend scheduled service triggers a liveness probe (`reqCurrentTime()`).
**When** the probe fails or no message is received within 5 seconds (total 15 seconds of silence), the status transitions to `DISCONNECTED`.
**When** the status changes, the backend immediately pushes a disconnection event via WebSocket to the desktop UI.
**Given** the Vue 3 Live Room dashboard header.
**When** the connection state is `CONNECTED` (latency < 2s).
**Then** a green badge is displayed.
**When** the connection state is `STALE` (no update > 5s).
**Then** an orange badge is displayed and margin values are grayed out with a warning message "Données figées il y a X secondes".
**When** the connection state is `DISCONNECTED`.
**Then** a red badge is displayed, manual order buttons are disabled, and warnings are shown.
**And** JUnit 5 tests verify the status transitions (`CONNECTED` -> `STALE` -> `DISCONNECTED`) deterministically using a mockable `Clock`.

