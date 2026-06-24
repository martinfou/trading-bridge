---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-06-15/prd.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/project-context.md"
workflowType: 'architecture'
project_name: 'Trading Bridge'
user_name: 'Martin'
date: '2026-06-15'
lastStep: 8
status: 'complete'
completedAt: '2026-06-15'
---

# Architecture Decision Document — Walk-Forward Analysis and Optimization

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
- **Calcul & Algorithme (FR-1 à FR-4)** : Moteur de découpe chronologique IS/OOS glissant, Grid Search déterministe optimisant le ratio de Sharpe (départage par le nombre maximal de transactions) avec Virtual Threads Java 21. Algorithme de purge des frontières (data leakage) basé sur la durée historique maximale des positions, et reconstruction d'une courbe d'equity Out-of-Sample consolidée.
- **Interfaces & APIs (FR-5 & FR-6)** : Endpoints REST asynchrones (`POST /api/runs/walk-forward`, statut HTTP 202 et suivi de progression/métriques), et commande CLI dédiée pour exécutions scriptées.
- **Visualisation & Suivi (FR-7 à FR-10)** : Interface graphique montrant la timeline glissante colorée et un tableau de stabilité des paramètres. Annotation `@CalibrationPolicy` sur les classes de stratégie Java pour surveiller la fraîcheur (🔋, 🔔, ⚠️) sur l'IHM et la TUI.

**Non-Functional Requirements:**
- **Déterminisme (SM-1)** : Reproductibilité absolue des exécutions du WFA (100% identiques pour des inputs identiques).
- **Limitation CPU (SM-C1)** : Le Grid Search via Virtual Threads doit consommer au maximum 80% des ressources processeur configurées.
- **Performance IHM (SM-2)** : Affichage graphique de la timeline de WFA sous la barre des 500ms.

**Scale & Complexity:**
- Primary domain: Backend Java (Core, Backtest, Runtime, CLI) & Frontend (Electron + Vue 3)
- Complexity level: Medium-High (découpe temporelle, algorithme de purge des trades frontaliers, Grid Search multithreadé, persistance hybride et IHM interactive).
- Estimated architectural components: 5 (WfaEngine, REST controller/Oanda client, SQLite service, Electron Bridge/Renderer, TUI indicators).

### Technical Constraints & Dependencies
- **Java 21 (Virtual Threads)** : Doit utiliser des threads légers pour accélérer le Grid Search.
- **Persistance Hybride** : Métadonnées dans la base SQLite locale, rapports détaillés (plis, paramètres, trades) enregistrés dans un fichier local JSON `wfa-{id}.json` lu par l'IHM.
- **Architecture Modulaire** : Respect du graphe de dépendance acyclique (le WfaEngine doit être implémenté dans `trading-backtest` ou un sous-module, et `@CalibrationPolicy` dans `trading-core`).

### Cross-Cutting Concerns Identified
- **Resource Management** : Éviter les freezes ou ralentissements des instances de trading réelles concurrentes lors du calcul lourd de Grid Search.
- **Data Integrity & Storage** : Gestion propre des rapports JSON sur le disque (moteur de nettoyage ou conservation indéfinie ?).

## Starter Template Evaluation

### Primary Technology Domain
- **Backend** : Java 21 avec architecture multi-module Maven.
- **Frontend** : Application de bureau (Electron + Vue 3 + Vite + TypeScript).

### Starter Options Considered
- **Projet existant (Brownfield)** : Choix unique et évident. L'infrastructure est déjà entièrement posée, stable et validée par des tests.

### Selected Starter: Existing Trading Bridge Codebase

**Rationale for Selection:**
Le projet possède déjà une architecture monorepo Maven fonctionnelle pour le backend Java et un répertoire `desktop/` pour l'interface Electron/Vue 3. Les dépendances de base (Jackson, JUnit 5, SLF4J, Tailwind/CSS, Vue router) sont déjà configurées et validées.

**Initialization Command:**
```bash
# Pas de commande d'initialisation requise. Le développement s'effectue directement sur la base de code active.
```

**Architectural Decisions Provided by Starter:**

**Language & Runtime:**
- Java 21 (Virtual Threads supportés) pour le calcul intensif (Grid Search).
- TypeScript / ECMAScript pour l'IHM Electron.

**Styling Solution:**
- CSS natif pour le style personnalisé de l'application Desktop (comme spécifié par les instructions de style UI).

**Build Tooling:**
- Maven 4.x pour le backend.
- Vite + npm pour le frontend Electron.

**Testing Framework:**
- JUnit 5 pour les tests unitaires et d'intégration Java (ex: `WfaEngineTest`).

**Code Organization:**
- Architecture acyclique multi-module. La logique WFA sera implémentée en étendant les modules existants :
  - `trading-core` : Déclaration de l'annotation `@CalibrationPolicy`.
  - `trading-backtest` : Moteur de calcul `WfaEngine` et algorithme de purge.
  - `trading-runtime` : Contrôleur REST (`POST` / `GET`) pour le suivi des runs asynchrones.
  - `desktop` : Pages et composants Vue 3 pour la timeline et les alertes.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- **Persistance hybride (SQLite & Fichiers JSON)** : Choix de stocker les métadonnées de haut niveau dans SQLite pour les requêtes rapides du dashboard et d'exporter le détail des plis/trades sous forme de fichier JSON unifié pour l'affichage lourd.
- **Limitation de la concurrence du Grid Search** : Parallélisation du Grid Search via un `ExecutorService` de Virtual Threads Java 21, régulé par un sémaphore limité à un maximum de 80 % des processeurs disponibles ($0.8 \times \text{coeurs}$).

**Important Decisions (Shape Architecture):**
- **Règle de départage des paramètres** : En cas d'égalité sur le Sharpe In-Sample, sélection de l'ensemble de paramètres ayant généré le plus grand nombre de transactions sur la période In-Sample.
- **Placement de la logique de fraîcheur** : Définition des règles de recalibration via l'annotation `@CalibrationPolicy` dans `trading-core`, évaluée dynamiquement par le `ControlSummaryService` de `trading-runtime`.
- **Accès aux rapports volumineux par l'IHM** : Ajout d'un endpoint `GET /api/runs/walk-forward/{wfaRunId}/report` dans `trading-runtime` pour renvoyer le fichier JSON de rapport détaillé au frontend Electron, évitant de lier l'IHM à des chemins d'accès fichiers locaux bruts.

**Deferred Decisions (Post-MVP):**
- **WFA Génétique** : Reporté à la v2. L'interface `ParameterOptimizer` sera toutefois isolée pour permettre d'interchanger le Grid Search par le Genetic Engine ultérieurement.
- **Recalibration automatique à chaud** : Aucun changement dynamique de paramètres sur les runs actifs en direct sans validation manuelle de Martin.

### Data Architecture

- **SQLite Schema (Table `wfa_runs`)** :
  - `wfa_run_id` (TEXT, Primary Key) : Identifiant unique du run.
  - `strategy_id` (TEXT) : Identifiant de la stratégie.
  - `instrument` (TEXT) : Paire de devises (ex: EUR_USD).
  - `start_date` / `end_date` (TEXT) : Plage de test globale.
  - `oos_sharpe` / `oos_profit_factor` / `oos_trades_count` (REAL/INTEGER) : Métriques OOS consolidées.
  - `wfe` (REAL) : Efficacité globale moyenne.
  - `report_path` (TEXT) : Chemin d'accès absolu au fichier JSON sur le disque.
  - `created_at` (TEXT) : Timestamp de création.
- **JSON Report Schema (`wfa-{id}.json`)** :
  - Contient un tableau des plis (`folds`), chaque pli listant : dates IS/OOS, paramètres optimisés, métriques IS/OOS (Sharpe, WFE, trades count), et la liste complète des transactions OOS individuelles après purge.

### Authentication & Security

- Conforme au standard actuel : Aucune clé OANDA ou identifiant en clair dans la base de données ou le rapport. Accès HTTP local restreint (API localhost du Control Plane).

### API & Communication Patterns

- **REST Interface (`trading-runtime`)** :
  - `POST /api/runs/walk-forward` : Lancement asynchrone (retourne 202 avec `wfaRunId`).
  - `GET /api/runs/walk-forward/{wfaRunId}` : Retourne la progression (%) et le résumé SQLite.
  - `GET /api/runs/walk-forward/{wfaRunId}/report` : Retourne l'intégralité du fichier JSON détaillé.

### Frontend Architecture

- **State & Router (`desktop`)** :
  - Ajout de la route `/runs/walk-forward/:id` affichant la timeline interactive (les plis en bandes de couleurs vert/rouge selon le Sharpe OOS) et le tableau comparatif de stabilité des paramètres.
  - Récupération asynchrone du rapport via l'API REST `GET /api/runs/walk-forward/{wfaRunId}/report` et stockage temporaire dans le state Pinia de Vue 3.

### Infrastructure & Deployment

- **Concurrence & CPU** : Utilisation d'un sémaphore de limitation de charge pour garantir que l'optimisation n'affecte pas l'exécution en temps réel des stratégies actives.

## Implementation Patterns & Consistency Rules

### Naming Patterns

**Database Naming Conventions:**
- Nom de la table : `wfa_runs` (pluriel, snake_case).
- Colonnes : `wfa_run_id`, `strategy_id`, `instrument`, `start_date`, `end_date`, `oos_sharpe`, `oos_profit_factor`, `oos_trades_count`, `wfe`, `report_path`, `created_at` (snake_case).

**API Naming Conventions:**
- Base REST path : `/api/runs/walk-forward` (kebab-case, pluriel).
- Paramètres de route : `/api/runs/walk-forward/{wfaRunId}` (camelCase pour le jeton de route).
- Clés JSON : camelCase (ex: `wfaRunId`, `strategyId`, `oosSharpe`).

**Code Naming Conventions:**
- Classes Java : PascalCase (ex: `WfaEngine`, `WfaController`, `@CalibrationPolicy`).
- Variables & Méthodes Java : camelCase (ex: `wfaRunId`, `runWalkForward()`).
- Fichiers Vue 3 : PascalCase (ex: `WfaTimeline.vue`, `WfaParameterStability.vue`).

### Structure Patterns

**Project Organization:**
- Les tests unitaires et d'intégration Java doivent être co-localisés sous `src/test/java` du module correspondant en respectant l'arborescence des packages (ex: `com.martinfou.trading.backtest.wfa.WfaEngineTest`).

**File Structure & Paths:**
- Logique d'optimisation WFA : package `com.martinfou.trading.backtest.wfa` (dans le module `trading-backtest`).
- Contrôleur REST : package `com.martinfou.trading.runtime.controllers` (dans le module `trading-runtime`).
- Annotation `@CalibrationPolicy` : package `com.martinfou.trading.core.strategy` (dans le module `trading-core`).
- Dossier de rapports JSON : stockés sous `data/reports/wfa/` (à la racine du projet). Ce dossier sera exclu du suivi Git dans `.gitignore` pour éviter de surcharger le dépôt.

**Graphe de Dépendances Acyclique :**
- Le module `trading-backtest` ne dépend pas de `trading-strategies` ni de `trading-runtime`. Par conséquent, le `WfaEngine` (dans `trading-backtest`) ne doit pas référencer directement le `StrategyCatalog`. Il doit instancier les stratégies via des abstractions comme des fabriques ou des fournisseurs de type `Supplier<Strategy>` qui lui sont passés en argument par la couche appelante (`trading-runtime` ou le CLI de `trading-examples`).

### Format Patterns

**API Response Formats:**
- En cas de succès : Sérialisation directe des objets DTO (ou java records) en JSON.
- En cas d'erreur : Retourner `{ "error": "Message" }` avec le code HTTP adapté (400, 404, 422, 500).
- Format de date : ISO 8601 en UTC (`yyyy-MM-dd'T'HH:mm:ss'Z'`).

### Communication Patterns

- Le lancement d'un WFA (`POST /api/runs/walk-forward`) est asynchrone et retourne un HTTP 202. La progression (0 à 100) est exposée par `GET /api/runs/walk-forward/{id}`. Le frontend interroge cet endpoint par polling.

### Process Patterns

**Error Handling & Concurrency:**
- **Contrôle CPU** : La parallélisation par Virtual Threads dans le `WfaEngine` doit être régulée par un `Semaphore` dont la limite (max CPU) est lue à partir du fichier `config.toml` global (ex: paramètre `core.max_wfa_cpu_cores` ou par défaut 80% des coeurs logiques), afin de protéger l'exécution des stratégies actives.

### Enforcement Guidelines

**All AI Agents MUST:**
- Respecter le graphe de dépendance acyclique (pas de dépendances circulaires entre modules).
- Valider le déterminisme algorithmique du Grid Search (utiliser des collections ordonnées comme `LinkedHashMap` ou trier explicitement les plages).
- Écrire des tests automatiques JUnit validant le calcul et la purge des plis.

## Project Structure & Boundaries

### Complete Project Directory Structure

Nous allons introduire de nouveaux fichiers (marqués par `[NEW]`) et en modifier certains (marqués par `[MODIFY]`) dans l'arborescence existante :

```
trading-bridge/
├── trading-core/
│   └── src/main/java/com/martinfou/trading/core/strategy/
│       └── CalibrationPolicy.java  [NEW] (Annotation de fraîcheur)
├── trading-backtest/
│   └── src/main/java/com/martinfou/trading/backtest/
│       ├── wfa/
│       │   ├── WfaEngine.java      [NEW] (Moteur de découpe, Grid Search et purge)
│       │   ├── WfaFold.java        [NEW] (Représentation d'un pli IS/OOS)
│       │   ├── WfaConfig.java      [NEW] (Structure de configuration WFA)
│       │   └── ParameterRange.java [NEW] (Plages de paramètres à explorer)
│       └── persistence/
│           └── SqliteWfaRunStore.java [NEW] (Persistance du résumé dans SQLite, crée la table wfa_runs au démarrage)
│   └── src/test/java/com/martinfou/trading/backtest/wfa/
│       └── WfaEngineTest.java      [NEW] (Tests unitaires et d'intégration)
├── trading-runtime/
│   └── src/main/java/com/martinfou/trading/runtime/
│       ├── controllers/
│       │   └── WfaController.java  [NEW] (Contrôleur REST POST/GET pour le WFA)
│       ├── wfa/
│       │   └── WfaManager.java     [NEW] (Gestionnaire asynchrone et sémaphore CPU)
│       └── ControlSummaryService.java [MODIFY] (Calcul de fraîcheur basé sur @CalibrationPolicy)
├── trading-examples/
│   └── src/main/java/com/martinfou/trading/examples/
│       └── RunBacktest.java        [MODIFY] (Option --wfa ajoutée pour lancer via CLI)
├── data/
│   └── reports/
│       └── wfa/                    [NEW] (Dossier de stockage des fichiers JSON wfa-{id}.json)
└── desktop/
    └── src/
        ├── components/
        │   └── wfa/
        │       ├── WfaTimeline.vue [NEW] (Composant de la frise chronologique)
        │       └── WfaParameterStability.vue [NEW] (Tableau de stabilité des paramètres)
        ├── views/
        │   └── WfaView.vue         [NEW] (Page principale du rapport WFA)
        ├── router/
        │   └── index.ts            [MODIFY] (Route /runs/walk-forward/:id)
        └── types/
            └── wfa.ts              [NEW] (Types TypeScript pour le rapport WFA)
```

### Architectural Boundaries

**API Boundaries:**
- Le contrôleur `WfaController` expose l'API sur le port `8080` (Control Plane). Toutes les requêtes HTTP entrent par ce canal. L'IHM communique exclusivement via ce port.

**Component Boundaries:**
- **Moteur (Core/Backtest)** : Le `WfaEngine` encapsule l'algorithme purement mathématique. Il n'a aucun état persistant ni connexion réseau. Il prend en entrée des données brutes de barre et retourne un résultat consolidé.
- **Orchestration (Runtime)** : Le `WfaManager` gère l'état d'exécution, le pool de threads (Virtual Threads), la limite CPU, et l'écriture des fichiers JSON locaux dans le répertoire `data/reports/wfa/`.

**Data Boundaries:**
- **SQLite** : Stocke uniquement les données indexables (métadonnées rapides) dans la table `wfa_runs`.
- **JSON Flat Files** : Stocke la structure de données complexe du rapport final et des transactions OOS dans un fichier JSON pour éviter de surcharger les tables de la base de données.

### Requirements to Structure Mapping

- **Story 24.1 (Découpe & Grid Search)** : Implémenté dans `WfaEngine.java` et `WfaFold.java`.
- **Story 24.2 (Purge & Reconstruction)** : Implémenté dans `WfaEngine.java` (méthodes de purge et de fusion).
- **Story 24.3 (API REST & CLI)** : Implémenté dans `WfaController.java`, `WfaManager.java` et `RunBacktest.java`.
- **Story 24.4 (Timeline & Stabilité GUI)** : Implémenté dans `WfaView.vue`, `WfaTimeline.vue` et `WfaParameterStability.vue`.
- **Story 24.5 (Suivi de fraîcheur & Alertes)** : Implémenté dans `CalibrationPolicy.java` et `ControlSummaryService.java`, puis affiché sur le Dashboard de l'IHM et la TUI.

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
Toutes les décisions techniques cohabitent parfaitement. La limitation CPU à 80 % dans le WfaManager protège directement l'exécution concurrente des stratégies de production. Le choix du Grid Search résout le problème de non-déterminisme pour les tests unitaires.

**Pattern Consistency:**
Les règles de nommage (`wfa_runs`, `/api/runs/walk-forward`, DTOs en camelCase) sont uniformes et conformes aux conventions globales du projet.

**Structure Alignment:**
La structure physique proposée respecte le graphe de dépendance acyclique du monorepo (notamment l'isolation du WfaEngine vis-à-vis du `StrategyCatalog` via des `Supplier`). Le dossier de rapports locaux `data/reports/wfa/` est correctement ignoré dans Git.

### Requirements Coverage Validation ✅

**Epic/Feature Coverage:**
L'intégralité des 5 stories de l'Epic 24 est prise en charge par des fichiers Java ou Vue 3 dédiés.

**Functional Requirements Coverage:**
- FR-1 à FR-4 (Calcul, Purge) -> Couverts par `WfaEngine.java` et `WfaFold.java`.
- FR-5 & FR-6 (API, CLI) -> Couverts par `WfaController.java`, `WfaManager.java` et `RunBacktest.java`.
- FR-7 & FR-8 (IHM Timeline) -> Couverts par `WfaView.vue`, `WfaTimeline.vue` et `WfaParameterStability.vue`.
- FR-9 & FR-10 (Fraîcheur & Alertes) -> Couverts par `@CalibrationPolicy` et `ControlSummaryService.java`.

**Non-Functional Requirements Coverage:**
- Déterminisme (SM-1) -> Garanti par le Grid Search.
- Performance IHM (SM-2) -> Garanti par le chargement asynchrone du JSON local pré-généré.
- Protection CPU (SM-C1) -> Garanti par le Semaphore réglable dans la configuration.

### Implementation Readiness Validation ✅

**Decision Completeness:**
Toutes les décisions clés de persistence, d'API et de threading sont arrêtées et documentées.

**Structure Completeness:**
L'arborescence des fichiers à créer et modifier est spécifiée de manière exhaustive et précise.

**Pattern Completeness:**
Les règles de nommage de base de données, d'API et de code sont clairement explicitées pour guider les agents de développement.

### Gap Analysis Results
*Aucun gap identifié pour le MVP.*

### Validation Issues Addressed
*Aucune anomalie détectée.*

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

**Overall Status:** READY FOR IMPLEMENTATION
**Confidence Level:** high

**Key Strengths:**
- Déterminisme absolu de l'algorithme WFA.
- Résilience et protection des ressources CPU de production via sémaphore.
- Légèreté et performance de la persistance hybride (SQLite + fichiers JSON locaux).

**Areas for Future Enhancement:**
- Intégration du WFA génétique (v2).
- Recalibration automatique déclenchée à chaud.

### Implementation Handoff

**AI Agent Guidelines:**
- Follow all architectural decisions exactly as documented
- Use implementation patterns consistently across all components
- Respect project structure and boundaries
- Refer to this document for all architectural questions

**First Implementation Priority:**
Créer l'annotation `@CalibrationPolicy` dans `trading-core/src/main/java/com/martinfou/trading/core/strategy/CalibrationPolicy.java`.
