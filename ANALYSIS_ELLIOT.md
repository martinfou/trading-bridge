# 🔍 Analyse Technique — Trading Bridge

> **Inspecteur** : Elliot 🎭  
> **Date** : 2026-07-23  
> **Projet** : `trading-bridge` — Pont de bout en bout entre l'idée (stratégie) et l'exécution (OANDA / IBKR)  
> **Stack** : Java 21, Maven 4.x, 11 modules, ~200+ sources, 118+ tests

---

## Résumé des forces

- ✅ **Multi-module bien découpé** : `core` → `backtest` → `broker` → `data` → `runtime`, graphe clair
- ✅ **Backtest engine robuste** : hedging OANDA, MARKET/LIMIT/STOP fills, Monte Carlo, Walk-Forward
- ✅ **Golden baselines** : régression protégée par métriques canoniques (trades, return %, DD)
- ✅ **Documentation riche** : `docs/` avec runbooks, architecture, testing, promote playbook
- ✅ **CI multi-OS** pour le desktop (Linux/macOS/Windows)
- ✅ **Java 21 records, sealed classes, pattern matching** bien utilisés

---

## 🔴 Problèmes identifiés

### 1. Code Quality — Design & Dettes

| # | Problème | Sévérité |
|---|----------|----------|
| PQ1 | `Strategy.getHistory()` et `syncPosition()` utilisent **la réflexion** (`getDeclaredField("history")`) pour accéder aux champs privés — cassant au rename, non typé, lent | 🔴 CRITICAL |
| PQ2 | `BacktestExecutionCost` record dupliqué avec les mêmes champs que les setters de `BacktestEngine` — deux sources de vérité pour les coûts | 🟠 HIGH |
| PQ3 | `Indicators` = classe utilitaire statique sans interface — pas mockable, pas testable en isolation | 🟡 MEDIUM |
| PQ4 | `Order` mutable avec fluent setters *in-place* (`withStopLoss()`, `fill()`) — mélange Builder et mutation | 🟡 MEDIUM |
| PQ5 | `BacktestResult` record + Builder de 20+ champs — le Builder est une copie quasi exacte du record | 🟢 LOW |
| PQ6 | `StrategyOrderQueues` = 2 lignes utiles — aurait dû être une `default` method sur `Strategy` | 🟢 LOW |
| PQ7 | `pipSize()` dupliquée dans `Indicators` et `LotSizing`/`calcRiskPosition` — DRY violé | 🟡 MEDIUM |
| PQ8 | Dépendance `ta4j-core` dans `trading-core` jamais utilisée — dead weight dans le classpath | 🟡 MEDIUM |

### 2. Architecture — Modularité & Couplage

| # | Problème | Sévérité |
|---|----------|----------|
| AR1 | `trading-backtest` dépend de `trading-strategies` — impossible de backtester sans compiler TOUTES les stratégies | 🔴 HIGH |
| AR2 | `trading-strategies` dépend de `trading-data` — une stratégie n'a pas besoin d'accès réseau | 🟡 MEDIUM |
| AR3 | Aucun framework DI — constructeurs à 5 paramètres dans `OandaBroker`, tout est `new SomeService()` | 🟠 HIGH |
| AR4 | `OandaBroker` = 460 lignes qui gèrent *connexion + keepalive + rate limiting + cache + metrics + closeOnly + emit events* — **SRP violé massivement** | 🔴 CRITICAL |
| AR5 | `FakeBroker` ne supporte PAS le hedging ni `closeOnly` — les tests broker ne couvrent pas les vrais scénarios | 🟠 HIGH |
| AR6 | `trading-runtime` = 90+ fichiers — trop gros, mélange HTTP (Javalin), risk engine, promote gates, event store, drift analysis | 🟠 HIGH |
| AR7 | Pas d'interface séparée pour `OandaRestClient`/`IbkrGatewayClient` — `trading-broker` dépend de `trading-data` concrètement | 🟡 MEDIUM |

### 3. Build & CI — Pipeline

| # | Problème | Sévérité |
|---|----------|----------|
| CI1 | **Dockerfile fragi**le : copie module par module sur le classpath (lignes 25-33) — dès qu'on ajoute un module, le Dockerfile casse | 🔴 HIGH |
| CI2 | Pas de Docker build/test dans la CI | 🟠 HIGH |
| CI3 | Aucun outil de qualité : pas de SpotBugs, PMD, Checkstyle, SonarQube, ni jacoco | 🟠 HIGH |
| CI4 | `slf4j-simple` utilisé dans 4 modules au lieu de Logback de manière cohérente | 🟡 MEDIUM |
| CI5 | Pas de `maven-enforcer-plugin` ni de verrouillage de versions de plugins | 🟡 MEDIUM |
| CI6 | Pas de Dependabot / Renovate pour les dépendances | 🟡 MEDIUM |
| CI7 | CI `mvn clean install` sans cache efficace (autre que setup-java cache) | 🟢 LOW |
| CI8 | Pas de vérification `javadoc` dans le build | 🟢 LOW |

### 4. Production & Monde Réel

| # | Problème | Sévérité |
|---|----------|----------|
| PR1 | **Crash recovery** basé sur la réflexion (noté dans README issue #2) → fragile, pas de schema versioning | 🔴 CRITICAL |
| PR2 | OANDA `closeTrade` utilise `Math.round(qtyToClose)` en String → perte de précision sur petites unités | 🟠 HIGH |
| PR3 | Aucune métrique / monitoring : pas de Micrometer, Prometheus, ou logs structurés JSON | 🟠 HIGH |
| PR4 | `config/` existe mais sans schema — on ne sait pas quel format attendre | 🟡 MEDIUM |
| PR5 | Pas de health check endpoint dans le serveur Javalin | 🟡 MEDIUM |
| PR6 | La procédure Paper → LIVE promotion est documentée mais pas automatisée dans la CI | 🟡 MEDIUM |
| PR7 | `trading-parser` marqué 🚧 — pas de statut clair, pas de tests | 🟡 MEDIUM |

### 5. Documentation & Connaissances

| # | Problème | Sévérité |
|---|----------|----------|
| DC1 | `Strategy.getHistory()` a un `@deprecated` sans **migration path** concret vers `getEngineHistory()` | 🟠 HIGH |
| DC2 | Aucun ADR (Architecture Decision Record) — pourquoi le hedging a été fait comme ça, pourquoi reflection, etc. | 🟡 MEDIUM |
| DC3 | Pas de génération `javadoc` — les devs doivent lire le code source pour l'API | 🟡 MEDIUM |
| DC4 | 55+ stratégies sans documentation individuelle — seulement des catalogues | 🟡 MEDIUM |
| DC5 | `config/` pas documenté | 🟢 LOW |

---

## 📋 Propositions d'Epics & Stories

---

### 🏗️ **Epic 1 — Dette Technique : Nettoyage du Domain Model**

**Objectif** : Éliminer la réflexion, la duplication de configuration, et les dead dependencies.

**Story 1.1** : Remplacer `Strategy.getHistory()` et `syncPosition()` par une API propre
- Créer un `StrategyContext` passé dans `onBar()` contenant l'historique géré par le moteur
- Supprimer les 4 appels à `Field.setAccessible(true)` dans l'interface
- Ajouter un mécanisme de position syncing via le contexte plutôt que la réflexion
- Migrer les 55+ stratégies et les stratégies SQ importées

**Story 1.2** : Fusionner `BacktestExecutionCost` dans `BacktestEngine`
- Supprimer les 5 champs dupliqués (`commissionFixed`, `slippagePct`, etc.) dans `BacktestEngine`
- Remplacer par un champ unique `BacktestExecutionCost cost`
- Garder les setters fluents en déléguant à `cost`

**Story 1.3** : Nettoyer les dépendances mortes
- Supprimer `ta4j-core` de `trading-core` (jamais utilisé)
- Supprimer `Indicators.pipSize()` dupliquée, centraliser dans `LotSizing`
- Supprimer `StrategyOrderQueues` en déplaçant `drainPending()` comme méthode default de `Strategy`

---

### 🧱 **Epic 2 — Architecture : Découplage des Modules**

**Objectif** : Casser les dépendances cycliques et introduire l'injection de dépendances.

**Story 2.1** : Inverser la dépendance `trading-backtest → trading-strategies`
- Créer un module `trading-backtest-spi` (ou une interface dans `trading-core`) avec `StrategyProvider`
- `BacktestEngine` accepte une `Strategy` par paramètre — pas de dépendance au module strategies
- Le couplage se fait à la construction via `StrategyProvider` résolu par le runtime

**Story 2.2** : Extraire les interfaces des clients HTTP
- Créer `BrokerClient` interface dans `trading-core` que `OandaRestClient` et `IbkrGatewayClient` implémentent
- `trading-broker` dépend de `trading-core` uniquement (plus de `trading-data`)
- `trading-data` garde les implémentations HTTP

**Story 2.3** : Introduire l'injection de dépendances (Guice ou Spring)
- Remplacer les `new SomeService()` et constructeurs surchargés par l'injection
- Découper `OandaBroker` en classes séparées :
  - `OandaConnectionManager` (connect/disconnect/keepalive)
  - `OandaRateLimiter` (rate limiting)
  - `OandaPositionManager` (getPositions, closeOnly logic)
  - `OandaBroker` orchestre les 3

---

### 🔄 **Epic 3 — Build & CI : Pipeline Industrialisé**

**Objectif** : Docker reproductible, qualité de code automatique, builds rapides.

**Story 3.1** : Dockerfile réécrit avec fat JAR Shade
- Remplacer la copie classe-par-classe par un `maven-shade-plugin` produisant un unique `control-plane.jar`
- Dockerfile devient : `COPY --from=build /app/trading-runtime/target/*-shaded.jar /app/control-plane.jar`
- Ajouter `.dockerignore` pour exclure `target/`, `node_modules/`, `.git/`, `data/`

**Story 3.2** : Qualité de code dans le build
- Ajouter `spotbugs-maven-plugin`, `maven-checkstyle-plugin`, `maven-pmd-plugin`
- Configurer `jacoco-maven-plugin` avec un seuil minimum (ex: 60% coverage)
- Ajouter `maven-enforcer-plugin` (Java 21, versions de plugins, pas de dépendances cycliques)
- Générer `javadoc` dans une étape CI séparée

**Story 3.3** : CI étendue
- Ajouter `Docker build` et `docker compose up trader` smoke test dans la CI
- Ajouter Dependabot pour les dépendances Maven et npm
- Ajouter cache Maven repository entre les runs (déjà partiel avec setup-java, mais améliorable)
- Paralleliser les étapes de test (backtest, runtime, broker, genetics en jobs indépendants)

---

### 📊 **Epic 4 — Monde Réel : Monitoring & Production Readiness**

**Objectif** : Passer du paper trading à un système digne de la production.

**Story 4.1** : Métriques et health checks
- Ajouter Micrometer + Prometheus exporter au serveur Javalin
- Endpoint `/health` (liveness) et `/ready` (readiness) :
  - Broker connecté ?
  - Keepalive OK ?
  - Event store accessible ?
  - Positions alignées ?
- Exporter les métriques : ordres soumis/rejetés, P&L flottant, drawdown, latence API

**Story 4.2** : Crash recovery robuste
- Remplacer la persistence par réflexion par une sérialisation JSON via Jackson (déjà présent)
- Versionner le schema de sauvegarde (`stateVersion: 1`)
- Ajouter des tests de reprise : crash au milieu d'un cycle, redémarrage, positions restaurées
- Ajouter un `StateMachine` pour les transitions (BOOTING → CONNECTED → RUNNING → DEGRADED → SHUTDOWN)

**Story 4.3** : Paper → LIVE promote gate automatisé
- Automatiser le runbook `docs/prop-shop-runbook.md` :
  - Validation backtest OOS + Monte Carlo
  - Drift analysis vs golden baseline
  - Paper trading survivability (X jours sans crash, DD < seuil)
  - Déploiement LIVE avec kill switch activé
- Ajouter un webhook Slack/email pour notify les promote events

---

### 📚 **Epic 5 — Documentation & Connaissances**

**Objectif** : Rendre le code auto-documenté et les décisions d'architecture traçables.

**Story 5.1** : ADR — Architecture Decision Records
- Créer `docs/adr/ADR-001-hedging-semantics.md` — pourquoi le hedging OANDA a été modélisé ainsi
- Créer `docs/adr/ADR-002-reflection-in-strategy.md` — pourquoi la réflexion a été utilisée (et pourquoi on la supprime)
- Créer `docs/adr/ADR-003-module-dependency-graph.md` — pourquoi les dépendances actuelles et le plan de découplage

**Story 5.2** : JavaDoc + Documentation API
- Activer `maven-javadoc-plugin` avec `failOnError=false` dans un premier temps
- Documenter les classes publiques clés : `Strategy`, `Order`, `BacktestEngine`, `Broker`
- Ajouter un badge "documented" dans le README

**Story 5.3** : Catalogue des stratégies enrichi
- Pour chaque stratégie dans `trading-strategies/src/main/java/...` :
  - Ajouter un header JavaDoc structuré avec `@Strategy(name=, pair=, timeframe=, inspiration=)`
  - Ajouter un lien vers le document d'inspiration dans `docs/`
  - Catégoriser par type (trend, mean-reversion, breakout, calendar, ML/AI)

---

## 📊 Priorisation

| Epic | Effort estimé | Impact | Risque si non fait |
|------|:---:|:---:|:---:|
| Epic 1 — Dette Domain Model | 2-3 jours | 🔴 | Bugs silencieux (reflection qui casse), maintenance impossible |
| Epic 2 — Découplage | 4-5 jours | 🟠 | Builds lents, impossible de tester les stratégies isolément |
| Epic 3 — Build & CI | 2-3 jours | 🟠 | Docker fragile, qualité non contrôlée, dépendances non suivies |
| Epic 4 — Production | 5-7 jours | 🔴 | Crash = perte de positions, pas de monitoring, pas d'alerting |
| Epic 5 — Documentation | 1-2 jours | 🟢 | Courbe d'apprentissage haute pour les nouveaux contributeurs |

---

*Rapport généré par Elliot 🎭 le 2026-07-23*
