stepsCompleted: [1, 2]
inputDocuments:
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-06-15/prd.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/architecture-walk-forward-optimization.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/project-context.md"
---

# Trading Bridge - Epic 24 (Walk-Forward Analysis & Optimization) Breakdown

## Overview

This document provides the complete epic and story breakdown for Epic 24 in Trading Bridge, decomposing the requirements from the PRD and Architecture decisions into implementable stories.

## Requirements Inventory

### Functional Requirements

- **FR-1**: Le système doit diviser la plage de données historiques globale en N plis glissants (IS/OOS) selon la configuration. Le découpage ne doit laisser aucun espace vide entre les plis.
- **FR-2**: Pour chaque pli, le système doit exécuter un Grid Search sur les paramètres configurés sur la période In-Sample afin de maximiser le Sharpe Ratio. En cas d'égalité, l'ensemble avec le plus grand nombre de transactions sur l'IS est sélectionné. Le calcul utilise un ThreadPool classique limité à 80% des processeurs.
- **FR-3**: Lors de la transition entre l'IS et l'OOS, le système doit purger tout trade initié durant la période IS qui se termine sur la période OOS, ou initié trop près de la frontière.
- **FR-4**: Le système doit fusionner chronologiquement les trades générés sur toutes les périodes OOS pour former la courbe de performance OOS unifiée et calculer les métriques globales.
- **FR-5**: Le serveur de `trading-runtime` doit exposer des endpoints REST asynchrones pour lancer un WFA (`POST /api/runs/walk-forward`), récupérer sa progression (`GET /api/runs/walk-forward/{id}`), et charger le rapport complet (`GET /api/runs/walk-forward/{id}/report`).
- **FR-6**: Le module `trading-examples` doit proposer une option CLI (ex: `mvn exec:java -pl trading-examples ... --wfa`) pour exécuter un WFA directement.
- **FR-7**: L'IHM Desktop doit afficher une frise horizontale représentant les plis, colorée en gris (IS), vert (OOS Sharpe > 0) ou rouge (OOS Sharpe < 0).
- **FR-8**: L'IHM Desktop doit afficher un tableau comparatif listant pour chaque pli les valeurs optimales sélectionnées pour chaque paramètre.
- **FR-9**: Les règles de calibration doivent être déclarées sous forme d'annotations `@CalibrationPolicy` directement sur la classe Java de la stratégie (ex: `maxAgeDays`, `maxBarsCount`, `maxTradesCount`).
- **FR-10**: Le tableau de bord IHM Desktop et la TUI doivent afficher des indicateurs visuels de fraîcheur de calibration (🔋, 🔔, ⚠️) évalués en temps réel par le Control Plane.

### NonFunctional Requirements

- **SM-1 (Déterminisme)** : Reproductibilité absolue des exécutions du WFA (100% de résultats identiques pour les mêmes inputs).
- **SM-2 (Performance IHM)** : Chargement et rendu de la timeline interactive en moins de 500ms après récupération du rapport.
- **SM-C1 (Limitation CPU)** : L'utilisation du ThreadPool pour le Grid Search ne doit pas consommer plus de 80% des ressources processeur (configurable via `config.toml`).

### Additional Requirements

- **Persistance Hybride** : Enregistrement du résumé de l'exécution dans SQLite (table `wfa_runs`) et écriture du rapport lourd JSON (`wfa-{id}.json`) dans le dossier `data/reports/wfa/`.
- **Exclusion Git** : Le sous-dossier de rapports locaux `data/reports/wfa/` doit être ajouté au fichier `.gitignore` du projet.
- **Graphe acyclique Maven** : Le `WfaEngine` (dans `trading-backtest`) ne doit pas dépendre de `trading-strategies`. L'instanciation des stratégies dans le moteur d'optimisation doit se faire par injection de dépendance (via des interfaces ou des `Supplier<Strategy>`) fournis par la couche appelante (`trading-runtime` ou `trading-examples`).

### UX Design Requirements

*(Aucun document UX Design distinct requis ; les exigences visuelles de la Timeline FR-7 et des indicateurs de fraîcheur FR-10 font partie intégrante des exigences fonctionnelles).*

### FR Coverage Map

- **FR-1 (Découpage IS/OOS)** : Épic 24, Story 24.1 (Moteur complet)
- **FR-2 (Grid Search Sharpe & ThreadPool)** : Épic 24, Story 24.1 (Moteur complet)
- **FR-3 (Purge des frontières de plis)** : Épic 24, Story 24.1 (Moteur complet)
- **FR-4 (Courbe OOS unifiée)** : Épic 24, Story 24.1 (Moteur complet)
- **FR-5 (REST API WFA)** : Épic 28, Story 28.1 (REST API & Persistance)
- **FR-6 (CLI Launcher)** : Épic 24, Story 24.2 (CLI Launcher)
- **FR-7 (IHM Timeline interactive)** : Épic 28, Story 28.2 (Interface Graphique)
- **FR-8 (IHM Tableau des paramètres)** : Épic 28, Story 28.2 (Interface Graphique)
- **FR-9 (Annotation @CalibrationPolicy)** : Épic 24, Story 24.1 (Moteur complet)
- **FR-10 (Freshness Alerts GUI/TUI)** : Épic 28, Story 28.3 (Alertes & TUI)
- **SM-1 (Déterminisme)** : Épic 24, Story 24.1 (Grid Search)
- **SM-2 (Performance IHM)** : Épic 28, Story 28.2 (Rendu JSON asynchrone)
- **SM-C1 (Limitation CPU)** : Épic 24, Story 24.1 (ThreadPool avec cœurs * 0.8)
- **Persistance Hybride** : Épic 28, Story 28.1 (SQLite + JSON)
- **Exclusion Git** : Épic 24, Story 24.2 (Création du dossier de rapports & Gitignore)
- **Graphe acyclique Maven** : Épic 24, Story 24.1 (Injection de dépendances)

## Epic List

### Épic 24 : WFA Engine & CLI (Calculs Historiques & Validation CLI)
*L'objectif de cette Épic est de concevoir le moteur d'optimisation Walk-Forward (découpage temporel, Grid Search parallèle, purge des frontières de plis pour éviter le look-ahead bias, reconstruction de la courbe OOS unifiée) et d'exposer ce moteur via une interface en ligne de commande (CLI).*

*   **Story 24.1 : WFA Core Engine & Calibration Metadata**
    *   **Description** : Implémenter l'annotation `@CalibrationPolicy`, le découpage temporel des plis, le moteur de Grid Search parallèle avec ThreadPool, la purge des trades frontaliers et la reconstruction de la courbe OOS. Le test d'intégration unifié (`WfaEngineTest`) validera le moteur en chargeant un CSV réel et en produisant un rapport JSON de validation.
*   **Story 24.2 : CLI Launcher & Export JSON**
    *   **Description** : Créer l'option CLI `--wfa` dans le module `trading-examples` (`RunBacktest`) pour exécuter le WFA depuis la console et exporter proprement le rapport JSON complet dans `data/reports/wfa/wfa-{id}.json`. Mettre à jour `.gitignore` pour exclure ce répertoire.

### Épic 28 : WFA Runtime & UI (Intégration & Visualisation)
*L'objectif de cette Épic est d'intégrer le WFA au Control Plane pour permettre le pilotage asynchrone des optimisations, la persistance des exécutions, la visualisation sous forme de Timeline et Grille de stabilité, ainsi que le monitoring de la dérive (drift).*

*   **Story 28.1 : Endpoints REST API & Persistance**
    *   **Description** : Implémenter `WfaManager` dans `trading-runtime` pour l'orchestration asynchrone en tâche de fond. Créer la table SQLite `wfa_runs` et exposer les endpoints `POST /api/runs/walk-forward`, `GET /api/runs/walk-forward/{id}` et `GET /api/runs/walk-forward/{id}/report`.
*   **Story 28.2 : Interface Graphique Desktop (Timeline & Stabilité)**
    *   **Description** : Créer les composants Vue 3 `WfaTimeline.vue` (frise interactive IS/OOS verte/rouge/grise) et `WfaParameterStability.vue` (grille de stabilité) dans l'IHM Desktop.
*   **Story 28.3 : Suivi de la fraîcheur de calibration & Alertes (TUI/GUI)**
    *   **Description** : Intégrer les alertes de fraîcheur de calibration (🔋, 🔔, ⚠️) basées sur la `@CalibrationPolicy` des stratégies en temps réel dans le `ControlSummaryService` du Control Plane, et les afficher sur l'IHM et la TUI.

## Épic 24 : WFA Engine & CLI (Calculs Historiques & Validation CLI)

### Story 24.1 : WFA Core Engine & Calibration Metadata

En tant que trader,
Je veux que le système effectue le découpage temporel, le Grid Search parallèle avec ThreadPool, la purge des frontières et la reconstruction OOS de manière déterministe,
Afin de valider la robustesse historique de ma stratégie sans biais de look-ahead.

**Acceptance Criteria:**

**Given** une classe de stratégie Java annotée avec `@CalibrationPolicy` décrivant les paramètres optimisables et leurs plages
**When** le `WfaEngine` découpe l'historique en N plis glissants ou ancrés selon la configuration
**Then** l'optimisation Grid Search sur chaque pli In-Sample (IS) s'exécute en parallèle via un `ThreadPoolExecutor` classique limité à un maximum de 80 % des processeurs disponibles
**And** en cas d'égalité de Sharpe Ratio sur la période IS, la combinaison de paramètres avec le plus grand nombre de trades sur l'IS est sélectionnée
**And** les positions ouvertes à cheval sur la frontière IS/OOS (ou ouvertes trop près de la frontière) sont purgées lors de l'exécution sur le segment Out-of-Sample (OOS) associé
**And** la courbe OOS unifiée est reconstruite chronologiquement à partir des segments individuels pour calculer les métriques globales de performance
**And** un test d'intégration unifié `WfaEngineTest.java` s'exécute avec succès en chargeant un jeu de données réel et en écrivant un rapport `wfa-test.json`.

### Story 24.2 : CLI Launcher & Export JSON

En tant que trader,
Je veux lancer une exécution de WFA en ligne de commande et exporter le rapport complet en JSON,
Afin d'automatiser mes calibrations ou d'inspecter les résultats bruts.

**Acceptance Criteria:**

**Given** un fichier de données historiques CSV
**When** je lance la commande d'exécution CLI via le module `trading-examples` (ex: `mvn exec:java -pl trading-examples ... --wfa`) avec le symbole, les dates et la configuration des plis
**Then** le système exécute le moteur WFA et affiche un résumé console des résultats (Sharpe global, Sharpe moyen IS/OOS, ratio d'efficacité)
**And** le rapport complet au format JSON contenant le détail complet de chaque pli et de tous les trades OOS est enregistré dans `data/reports/wfa/wfa-{id}.json`
**And** le sous-répertoire `data/reports/wfa/` est correctement ignoré dans le fichier `.gitignore`.

## Épic 28 : WFA Runtime & UI (Intégration & Visualisation)

### Story 28.1 : Endpoints REST API & Persistance

En tant que trader,
Je veux démarrer une analyse WFA à distance via des API REST et persister les résultats en base de données SQLite et en fichier JSON,
Afin de pouvoir piloter mes optimisations depuis l'IHM et conserver un historique de mes exécutions.

**Acceptance Criteria:**

**Given** la base de données SQLite connectée au Control Plane
**When** j'envoie une requête `POST /api/runs/walk-forward` avec les paramètres d'optimisation (stratégie, symbole, fenêtre, plages de paramètres)
**Then** le système démarre l'exécution en arrière-plan via le `WfaManager` et retourne immédiatement un statut HTTP 202 (Accepted) avec l'UUID de la tâche
**And** les requêtes `GET /api/runs/walk-forward/{id}` permettent de suivre la progression du calcul (nombre de plis traités / total)
**And** une fois terminé, le résumé de l'exécution est enregistré dans la table SQLite `wfa_runs` (ID, stratégie, symbole, Sharpe IS/OOS, date de fin, statut) et le rapport complet JSON est sauvegardé dans `data/reports/wfa/wfa-{id}.json`
**And** le endpoint `GET /api/runs/walk-forward/{id}/report` retourne le contenu du rapport JSON complet.

### Story 28.2 : Interface Graphique Desktop (Timeline & Stabilité)

En tant que trader,
Je veux visualiser graphiquement les résultats du Walk-Forward sous forme de timeline interactive et de grille de stabilité des paramètres,
Afin de pouvoir juger visuellement de la robustesse de ma stratégie sur les différents plis.

**Acceptance Criteria:**

**Given** une exécution WFA terminée
**When** je consulte la vue détaillée du run WFA sur l'IHM Desktop
**Then** le système affiche une frise chronologique (`WfaTimeline.vue`) représentant les plis
**And** chaque pli OOS est coloré en vert si son Sharpe ratio est strictement positif, en rouge s'il est négatif ou nul, et la période IS correspondante est indiquée en gris au survol
**And** le système affiche un tableau comparatif (`WfaParameterStability.vue`) listant pour chaque pli les valeurs des paramètres sélectionnées, mettant en évidence leur dérive ou leur stabilité au cours du temps
**And** le chargement et le rendu des graphiques se fait en moins de 500 ms (SM-2) après la récupération du rapport JSON.

### Story 28.3 : Suivi de la fraîcheur de calibration & Alertes (TUI/GUI)

En tant que trader,
Je veux que le système surveille la fraîcheur de ma calibration en temps réel et m'alerte lorsqu'une recalibration est nécessaire,
Afin d'éviter de faire tourner des stratégies périmées ou en dérive de performance.

**Acceptance Criteria:**

**Given** une stratégie active en production (live/paper)
**When** le `ControlSummaryService` évalue l'état de fraîcheur par rapport aux limites définies par l'annotation `@CalibrationPolicy` (ex: âge maximum du dernier run WFA, nombre maximum de transactions ou de barres depuis la dernière calibration)
**Then** le système calcule l'état de dégradation et met à jour les indicateurs visuels (🔋 pour frais, 🔔 pour avertissement, ⚠️ pour critique/périmé)
**And** ces alertes s'affichent sur le tableau de bord principal de l'IHM Desktop et dans l'interface de terminal TUI (`TradingTuiMain`).

