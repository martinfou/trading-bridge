---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/prd.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/addendum.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/project-context.md"
workflowType: 'architecture'
project_name: 'Trading Bridge'
user_name: 'Martin Fournier'
date: '2026-06-15'
lastStep: 8
status: 'complete'
completedAt: '2026-06-15'
---

# Architecture Decision Document: IBKR Futures Trading & Backtesting

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
- **REQ-FUT-INGEST (FR-1, FR-2) :** Ingestion asynchrone des bougies MES et assemblage d'une Série de Prix Continue à T-10 de l'expiration sans biais de look-ahead.
- **REQ-FUT-BACKTEST (FR-3, FR-4, FR-5) :** Simulation du multiplicateur (5.0), des exigences de Marge Initiale (1500$) et de Maintenance (1200$), et exécution du rollover automatique (Option A) avec doublement des commissions. En backtest, le moteur doit rejeter tout ordre si la marge libre est insuffisante.
- **REQ-FUT-EXEC (FR-6, FR-7, FR-8) :** Connecteur d'exécution de type "FUT" sur CME, écoute asynchrone des exécutions (fills) et réconciliation des frais via l'`execId`.
- **REQ-FUT-DASHBOARD (FR-9, FR-10) :** Affichage IHM des exigences de marge et configuration du backtest Futures.

**Non-Functional Requirements:**
- **NFR-FUT-PRECISION (SM-1) :** Écart de PnL simulé vs réel de 0% (hors slippage aléatoire).
- **NFR-FUT-RECONCILIATION (SM-2) :** Réconciliation des commissions en < 5 secondes en mode réel/paper.
- **NFR-FUT-PERFORMANCE (SM-C1) :** Impact sur la vitesse de backtest Forex existant limité à < 5%.

**Scale & Complexity:**
- Primary domain: Backend Java & Integration API (IBKR TWS API) + Frontend Electron/Vue 3.
- Complexity level: Medium-High (due to asynchronous thread correlation and margin calculations).
- Estimated architectural components: 4 components (data downloader, backtest engine simulator, broker connector, control plane HTTP endpoint).

### Technical Constraints & Dependencies
- Dépendance vers l'API Java native d'Interactive Brokers (TWS API).
- Nécessité d'une instance TWS ou IB Gateway active (connexion par Socket TCP).
- Maintien de l'acyclicité : toute structure de données partagée (ex. spécifications du contrat Futures) doit résider dans `trading-core`.
- La configuration des caractéristiques de marge et des multiplicateurs doit se faire statiquement par symbole pour le MVP (pas de requêtes dynamiques de marge par API pour simplifier le code).

### Cross-Cutting Concerns Identified
- **Thread Safety** : Traitement des callbacks asynchrones de l'API TWS (thread `EReader` en tâche de fond) via des files d'attente bloquantes (`BlockingQueue`) et synchronisation par `CompletableFuture` pour exposer une interface linéaire sans bloquer le runtime principal.
- **Corrélation d'état asynchrone** : Association fiable de `CommissionReport` à l'exécution de l'ordre via la clé `execId`.
- **Compatibilité ascendante** : Isolation complète des logiques Futures (multiplicateur, marges) pour ne pas altérer les backtests et le trading de devises sur OANDA.

---

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- **DEC-FUT-ROLLOVER (Option A) :** Raccordement brut des prix de contrats trimestriels à T-10 sans lissage/ajustement de prix pour la v1. Les indicateurs techniques liront le saut de prix brut lors de la transition. Un message d'avertissement ("Warning : Rollover non lissé") sera visible dans l'IHM lors d'un backtest Futures pour atténuer les erreurs d'optimisation de stratégie.
- **DEC-FUT-MARGIN-MODEL (Option A) :** Paramétrage statique local des marges (MES Marge Initiale : 1500$, Maintenance : 1200$, Multiplicateur : 5.0). Les caractéristiques sont stockées dans le répertoire runtime mutable `data/runtime/futures-contracts.json` pour permettre à Martin de mettre à jour les marges sans recompiler le projet, éliminant les requêtes réseau dynamiques lors des simulations.
- **DEC-FUT-ASYNC-CONCURRENCY (Option A) :** Encapsulation synchrone aux frontières de `trading-broker` via des `CompletableFuture` et des files d'attente bloquantes. L'API asynchrone d'IBKR est encapsulée pour exposer des méthodes bloquantes simples et sécuriser la boucle principale.

**Important Decisions (Shape Architecture):**
- **DEC-FUT-LIQUIDATION :** Le moteur de backtest simule les exigences de marge et liquide la position si l'équité simulée descend sous le seuil global de Marge de Maintenance. Pour compenser l'écart avec les règles exactes d'IBKR, le backtester applique une marge de sécurité de +5% lors des vérifications de marge (exigence de Marge de Maintenance simulée légèrement plus stricte).
- **DEC-FUT-DOUBLE-BARRIER :** Résolution des conditions de concurrence d'exécution. Pour associer `execDetails` et `commissionReport`, nous utilisons un registre thread-safe double barrière synchronisé (`IbkrTransactionRegistry` avec `ExecutionPending`). Si le rapport de commission tarde plus de 500ms après le fill, un timeout de secours complète la transaction avec une commission de 0 USD pour éviter le blocage.

**Deferred Decisions (Post-MVP):**
- **DEC-FUT-ADJUSTED-PRICES :** Lissage des prix lors des rollovers (backward/forward-adjustment) différé à la v2.
- **DEC-FUT-LIMIT-ORDERS :** Le support des ordres LIMIT/STOP/Bracket sur Futures via IBKR est différé à la v2.

### Data Architecture
- Le fichier `data/runtime/futures-contracts.json` est créé dans `trading-core` et lu par le `DataLoader` pour initialiser le multiplicateur et les seuils de marges de backtest. Un schéma JSON de validation garantit son intégrité au démarrage.

### API & Communication Patterns
- Les requêtes et commandes réseau vers la TWS Gateway (ex. placement d'ordre, récupération de données historiques) sont suivies à l'aide de tables de correspondance thread-safe (`ConcurrentHashMap`) indexées par `reqId` et résolues via des `CompletableFuture` avec des timeouts stricts (ex: 2000ms) pour éviter les blocages de thread.

### Frontend Architecture
- Les payloads JSON de l'API REST `/api/backtest/run` transmettent les paramètres spécifiques du contrat Futures sous forme de structure optionnelle. L'endpoint `/api/ibkr/account-summary` retourne le résumé de compte d'IBKR (balance, equity, initMarginReq, maintMarginReq, buyingPower).

### Infrastructure & Deployment
- L'exécution réelle/paper (`LIVE_IBKR`/`PAPER_IBKR`) nécessite une instance d'IB Gateway ou de TWS locale ou accessible sur le réseau (écoute asynchrone TCP sur port configuré).

---

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

**Critical Conflict Points Identified:**
5 zones clés de conflits potentiels résolues pour assurer la cohérence entre agents (nommage, structure, format, communication, processus).

### Naming Patterns

**API Naming Conventions:**
- Les routes REST doivent être en minuscules avec séparateurs par tirets ou slashes : `/api/ibkr/account-summary` et `/api/ibkr/margin-requirements`.
- Les paramètres de requête JSON et Query params doivent être en camelCase (ex: `contractSymbol`).

**Code Naming Conventions:**
- Packages Java : `com.martinfou.trading.broker.ibkr` pour l'intégration du broker, `com.martinfou.trading.core.instrument` pour les composants communs de domaine.
- Suffixes de classes : `IbkrBroker` (implémente `Broker`), `IbkrTransactionRegistry` (corrélation des transactions), `IbkrConnector` (gestion socket bas niveau).
- Noms de variables : camelCase (ex: `initMarginReq`, `maintMarginReq`).

### Structure Patterns

**Project Organization & File Structure:**
- Le fichier statique de configuration `futures-contracts.json` doit être placé dans `data/runtime/futures-contracts.json` (hors du classpath JAR) pour permettre sa modification manuelle sans recompilation.
- Les classes de tests JUnit 5 doivent être placées dans `src/test/java` de leurs modules respectifs (ex: `trading-broker` pour les tests unitaires du connecteur).
- Les mocks pour simuler les sockets TCP d'IBKR TWS API doivent être logés sous `src/test/java/com/martinfou/trading/broker/ibkr/mocks/`.

### Format Patterns

**API Response Formats:**
- Les objets d'échange JSON doivent utiliser le camelCase pour tous leurs attributs.
- Les dates et timestamps dans les payloads JSON doivent être sérialisés au format ISO-8601 en UTC (avec suffixe `Z`, ex: `2026-06-15T22:15:00Z`).

**Data Formats:**
- Les représentations booléennes doivent utiliser les booléens primitifs (`true`/`false`).
- Gestion des valeurs nulles : utiliser `Optional` pour retourner des valeurs optionnelles de domaine (comme StopLoss ou TakeProfit) au lieu de retourner `null`.

### Communication Patterns

**Async/Sync Correlation:**
- Chaque commande asynchrone envoyée à l'API TWS (via `EClientSocket`) nécessitant une corrélation de réponse doit utiliser un `reqId` unique.
- Ce `reqId` est enregistré dans une map thread-safe (`ConcurrentHashMap<Integer, CompletableFuture<T>>`) et complété dans le callback associé de l'implémentation de `EWrapper`.
- Tous les appels synchrones en attente de ces futures doivent définir un timeout strict de 2000ms.

**Double-Barrier Synchronization:**
- La corrélation de `CommissionReport` avec `execDetails` se fait via l'identifiant unique `execId` dans `IbkrTransactionRegistry`.
- Si le rapport de commission n'est pas reçu dans les 500ms suivant la réception du fill, la transaction est complétée avec une commission par défaut de `0.0` USD pour éviter une fuite mémoire.

### Process Patterns

**Error Handling & Fallbacks:**
- En cas de déconnexion du socket TCP avec IB Gateway / TWS, le connecteur doit mettre son statut à `DISCONNECTED`, annuler toutes les requêtes en cours dans le registre de corrélation avec une `CancellationException`, et lever un warning de log.
- Pas de reconnexion automatique intégrée au bas niveau ; la logique de reconnexion est gérée par la couche d'orchestration supérieure (`trading-runtime`).

### Enforcement Guidelines

**All AI Agents MUST:**
- Utiliser uniquement l'UTC (`Instant`) pour les représentations temporelles de trading.
- Garantir l'intégrité absolue du code Forex (OANDA) sans aucun effet de bord ni régression.
- Respecter les conventions de formatage checkstyle (indentation par espaces, accolades systématiques pour les `if` d'une seule ligne).

### Pattern Examples

**Good Examples:**
```java
// Corrélation de la double barrière via execId
CompletableFuture<Transaction> future = transactionRegistry.registerPendingExecution(execId);
// Compléter la commission à sa réception
transactionRegistry.completeCommission(execId, commissionReport.m_commission);
```

**Anti-Patterns:**
```java
// À NE PAS FAIRE : attente synchrone bloquante sans timeout
orderResponseFuture.get(); // BLOQUE INDÉFINIMENT si TWS ne répond pas
```

---

## Project Structure & Boundaries

### Complete Project Directory Structure

```
. (racine du projet)
├── pom.xml
├── data/
│   └── runtime/
│       └── futures-contracts.json [NEW]
├── trading-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/martinfou/trading/core/
│       │   ├── DataLoader.java
│       │   └── instrument/ [NEW]
│       │       ├── FuturesContractConfig.java [NEW] (Modèle immuable de config de marge et multiplicateur)
│       │       ├── FuturesRegistry.java [NEW] (Lecteur JSON thread-safe et cache de specs)
│       │       ├── AssetValuationModel.java [NEW] (Interface générique d'évaluation)
│       │       ├── ForexValuationModel.java [NEW] (Valorisation Forex historique conservée)
│       │       └── FuturesValuationModel.java [NEW] (Valorisation Futures avec marges/multiplicateurs)
│       └── test/java/com/martinfou/trading/core/
│           └── instrument/ [NEW]
│               └── FuturesRegistryTest.java [NEW] (Validation du schéma JSON au build)
├── trading-backtest/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/martinfou/trading/backtest/
│       │   ├── BacktestEngine.java [MODIFY] (Intègre AssetValuationModel, rollover, double commission)
│       │   └── margin/ [NEW]
│       │       └── MarginTracker.java [NEW] (Vérification et appels de marge avec buffer de +5%)
│       └── test/java/com/martinfou/trading/backtest/
│           ├── BacktestEngineTest.java [MODIFY]
│           └── margin/ [NEW]
│               └── MarginTrackerTest.java [NEW]
├── trading-broker/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/martinfou/trading/broker/
│       │   ├── IbkrBroker.java [MODIFY] (Adapter Broker, routage d'ordres MARKET Futures)
│       │   └── ibkr/ [NEW]
│       │       ├── IbkrConnector.java [NEW] (Gestion thread/socket EReader)
│       │       ├── IbkrTransactionRegistry.java [NEW] (Reconciliation commission 500ms timeout)
│       │       └── IbkrWrapperImpl.java [NEW] (Implémentation de EWrapper)
│       └── test/java/com/martinfou/trading/broker/
│           └── ibkr/ [NEW]
│               ├── IbkrBrokerTest.java [NEW]
│               └── IbkrTransactionRegistryTest.java [NEW]
├── trading-runtime/
│   ├── pom.xml
│   └── src/
│       └── main/java/com/martinfou/trading/runtime/
│           ├── ControlPlaneMain.java
│           └── api/ [MODIFY]
│               ├── BrokerController.java [MODIFY] (Routes génériques /api/brokers/*)
│               └── BacktestController.java [MODIFY]
└── desktop/
    └── src/
        ├── components/
        │   └── BacktestForm.vue [MODIFY]
        └── views/
            └── LiveRoom.vue [MODIFY]
```

### Architectural Boundaries

**API Boundaries:**
- Les requêtes du client desktop Vue 3 passent exclusivement par le contrôleur REST de `trading-runtime` via des routes génériques découplées (`/api/brokers/{brokerId}/account-summary` et `/api/portfolio/margins`). Aucun couplage IHM-IBKR direct.

**Component Boundaries:**
- Les dépendances de l'API TWS d'Interactive Brokers (`EClientSocket`, `EWrapper`, `EReader`) sont strictement confinées dans le package `com.martinfou.trading.broker.ibkr` de `trading-broker`.
- Le modèle de données `FuturesContractConfig` et le calcul de valorisation `AssetValuationModel` résident dans `trading-core`. Cela empêche tout couplage cyclique entre `trading-backtest`, `trading-broker` et les autres modules.

**Data Boundaries:**
- Les spécifications statiques de marges et de levier sont stockées hors-classpath dans le répertoire mutable `data/runtime/futures-contracts.json`.
- La réconciliation de commissions en temps réel réside dans `IbkrTransactionRegistry` sous forme de structures mémoire thread-safe éphémères indexées par `execId`.

### Requirements to Structure Mapping

**Feature/Epic Mapping:**
- **REQ-FUT-INGEST (FR-1, FR-2) :** `DataLoader.java` et `ContinuousFuturesSeries.java` (`trading-core`) gèrent l'historique non lissé à T-10.
- **REQ-FUT-BACKTEST (FR-3, FR-4, FR-5) :** `BacktestEngine.java` et `MarginTracker.java` (`trading-backtest`) gèrent l'évaluation, la marge (buffer +5%), la liquidation et les coûts de rollover (double commission et double spread).
- **REQ-FUT-EXEC (FR-6, FR-7, FR-8) :** `IbkrBroker.java`, `IbkrTransactionRegistry.java` et `IbkrWrapperImpl.java` (`trading-broker`) réalisent le trading réel/paper et la corrélation asynchrone des commissions.
- **REQ-FUT-DASHBOARD (FR-9, FR-10) :** `BrokerController.java` (`trading-runtime`), `LiveRoom.vue` et `BacktestForm.vue` (`desktop`) exposent et affichent les marges de maintenance et indicateurs visuels de rollover.

### Integration Points

**Internal Communication & Data Flow:**
- Lors d'un rollover trimestriel dans le backtest, le moteur génère deux ordres physiques liés via un `rolloverGroupId` unique (UUID) marqué par le tag `reason = ROLLOVER`.
- Au moment du calcul des statistiques, le service de reporting consolide les ordres partageant le même `rolloverGroupId` pour les comptabiliser comme un unique trade logique, évitant ainsi de fausser le taux de réussite (Win Rate) ou le nombre total de trades.
- Dans l'interface utilisateur, le trade s'affiche de manière compacte sur une seule ligne (déployable par accordéon pour visualiser les détails physiques) et marqué d'une ligne verticale pointillée sur le graphique.

**External Integrations:**
- Connexion TCP Socket asynchrone vers l'instance locale ou distante d'IB Gateway / TWS via le port configuré.

### File Organization Patterns

**Test Organization:**
- Tests de non-régression Forex : exécutés via `GoldenBacktestTest` exigeant un delta strict de `0.0`.
- Tests du connecteur de courtier : mocks Mockito de `EClientSocket` pour tester les flux asynchrones sans ouvrir de socket TCP réel durant le build de la CI.

---

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
- L'ensemble des décisions (Rollover Option A, modèle de marge statique locale, corrélation asynchrone, double barrière et timeout de secours) est parfaitement compatible. L'utilisation du JSON évite la dépendance à une base de données de configuration dynamique complexe.

**Pattern Consistency:**
- Les patrons de nommage et d'organisation des fichiers (les packages Java, l'absence de contrôleur d'API spécifique à IBKR remplacé par un routage générique) soutiennent harmonieusement l'implémentation et minimisent l'exposition directe du couplage frontend/backend.

**Structure Alignment:**
- La topologie des répertoires est respectée. La couche broker asynchrone est isolée et découplée des couches de backtest et de runtime, préservant ainsi l'acyclicité Maven.

### Requirements Coverage Validation ✅

**Epic/Feature Coverage:**
- Tous les requirements (FR-1 à FR-10) et non-fonctionnels (SM-1, SM-2, SM-C1) sont explicitement couverts et mappés dans la structure cible.

**Functional Requirements Coverage:**
- La valorisation de domaine est centralisée sous `AssetValuationModel` dans `trading-core`, ce qui isole la logique Forex et assure la non-régression tout en offrant un support Futures complet.

**Non-Functional Requirements Coverage:**
- La réconciliation de commissions en temps réel résout le problème de concurrence asynchrone (SM-2) avec un timeout de sauvegarde à 500ms qui évite les fuites de threads.
- L'overhead CPU du backtest Forex reste inférieur à 5% (SM-C1) en évitant toute lecture dynamique ou appel réseau pendant la boucle principale.

### Implementation Readiness Validation ✅

**Decision Completeness:**
- Toutes les décisions clés sont consignées et validées.

**Structure Completeness:**
- L'arborescence cible est définie de façon précise, avec les emplacements de test unitaires et d'intégration identifiés.

**Pattern Completeness:**
- Des directives claires de Mocking (TCP socket local pour tester `EReader` en CI) et d'IHM (consolidation logique de rollover par `rolloverGroupId` et alertes de liquidité actives) sont formulées pour les agents de dev.

### Gap Analysis Results

- **Gaps Critiques :** Aucun.
- **Sécurité opérationnelle :** Validation stricte de schéma JSON ajoutée au démarrage pour rejeter tout JSON malformé et fail-fast de sécurité.
- **Stratégie de Test :** Mock de socket TCP local requis en CI pour le thread `EReader` d'IBKR. Création de `GoldenFuturesBacktestTest` distinct pour ne pas impacter le baseline Forex.

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

- **Overall Status:** READY FOR IMPLEMENTATION
- **Confidence Level:** high
- **Key Strengths:**
  - Découplage de la logique Forex (OANDA) via `AssetValuationModel` (delta strict à `0.0` garanti par les tests).
  - Validation fail-fast du schéma JSON de spécification de contrat à chaud au démarrage du système.
  - Gestion unifiée et propre des transitions de contrats Futures dans l'IHM via `rolloverGroupId` (trades logiques vs exécutions physiques).

### Implementation Handoff

**AI Agent Guidelines:**
- Conserver le code existant d'OANDA intact (zéro modification).
- Utiliser Mockito pour la logique d'adaptation et un serveur TCP simulé pour le flux réseau multithreadé de l'EReader d'IBKR en CI.
- Respecter le routage générique `/api/brokers/*` sans exposer de dépendances IBKR directes dans les controllers.

**First Implementation Priority:**
- Implémenter le chargement et la validation de schéma de `futures-contracts.json` via le `FuturesRegistry` dans `trading-core`.



