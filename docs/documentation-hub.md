# Index Principal de la Documentation — Trading Bridge

**Type :** Monorepo multi-technologies à **4 parties** (`trading-bridge-java`, `trading-bridge-desktop`, `trading-bridge-dashboard`, `trading-bridge-scripts`)  
**Langages Principaux :** Java 21, TypeScript 5.7 (Vue 3 / Electron 33), PHP 8.3 (Laravel 13.8), Python 3 & Bash  
**Architecture :** Monolithe Modulaire Orienté Événements + Plan de Contrôle HTTP/WS (70 routes) + Poste de Travail Electron + Console SSR Laravel + Pipeline SQX & Docker Multi-Couloirs  
**Dernière mise à jour :** 2026-11-05

---

## 1. Point d'Entrée par Audience (Humains & Agents IA / LLMs)

> **[Guide Multi-Audiences (Humains & Agents LLM)](./guide-audiences-humains-et-llm.md)** — **Commencez ici !** Ce guide oriente chaque profil humain et chaque agent LLM vers ses règles, outils et documents de référence.

### 👤 Pour les Différents Profils Humains
* **Chercheur Quantitatif & Développeur de Stratégies (Quant)** :
  * [Parcours Quant (Guide Multi-Audiences §1)](./guide-audiences-humains-et-llm.md#1-humain--chercheur-quantitatif--développeur-de-stratégies-quant)
  * [Les 8 Garde-Fous Non-Négociables (`TRADING-GUARDRAILS.md`)](./TRADING-GUARDRAILS.md)
  * [Playbook des Stratégies Long-Terme & Leçons Simons 17e–65e (`lt-strategy-playbook.md`)](./lt-strategy-playbook.md)
  * [Spécification du Modèle de Coûts Horaires Empiriques (`real-cost-model-hourly-spec.md`)](./real-cost-model-hourly-spec.md)
  * [Guide Futures CME & Réalisme Backtest (`mes-cme-futures-realism-guide.md`)](./mes-cme-futures-realism-guide.md)
* **Trader, Opérateur de Desk & Ingénieur SRE (Desk Ops)** :
  * [Parcours Opérateur & SRE (Guide Multi-Audiences §2)](./guide-audiences-humains-et-llm.md#2-humain--trader-opérateur-de-desk--ingénieur-sre-desk-ops)
  * [Guide de Déploiement Multi-Couloirs (`deployment-guide.md`)](./deployment-guide.md)
  * [Journal de la Fenêtre Paper Active (`paper-window-log.md`)](./paper-window-log.md)
  * [Manifeste d'Opérations (`operations-manifest.md`)](./operations-manifest.md) & [Ingénierie de Fiabilité SRE (`reliability-engineering.md`)](./reliability-engineering.md)
  * [Check-list Pré-Vol (`pre-flight-checklist.md`)](./pre-flight-checklist.md) & [Matrice de Sévérité des Incidents (`incident-severity-matrix.md`)](./incident-severity-matrix.md)
* **Ingénieur Logiciel & Architecte Système (Software Engineer)** :
  * [Parcours Ingénieur Logiciel (Guide Multi-Audiences §3)](./guide-audiences-humains-et-llm.md#3-humain--ingénieur-logiciel--architecte-système-software-engineer)
  * [Guide de Développement Global (`development-guide.md`)](./development-guide.md) & [Analyse de l'Arborescence (`source-tree-analysis.md`)](./source-tree-analysis.md)
  * [Architecture d'Intégration Inter-Parties (`integration-architecture.md`)](./integration-architecture.md)
* **Décideur Produit, Exécutif & Auditeur (Product / Executive)** :
  * [Parcours Décideur & Auditeur (Guide Multi-Audiences §4)](./guide-audiences-humains-et-llm.md#4-humain--décideur-produit-exécutif--auditeur-product--executive)
  * [Présentation Générale (`project-overview.md`)](./project-overview.md) & [Vision Produit (`VISION.md`)](./VISION.md)
  * [Plan de Sprint & Feuille de Route (`sprint-plan.md`)](./sprint-plan.md) & [Audit du Portefeuille Déployé (`deployed-set-audit-2026-10-16.md`)](./deployed-set-audit-2026-10-16.md)

### 🤖 Pour les Agents IA & LLMs
* **Agent IA de Codage & d'Architecture (`Coding Agent`)** :
  * **[Fichier d'Ancrage des Règles Agents (`_bmad-output/project-context.md`)](../_bmad-output/project-context.md)** *(Lecture obligatoire avant tout code)*
  * [Invariants & Anti-Patrons de Codage (Guide Multi-Audiences §5)](./guide-audiences-humains-et-llm.md#5-llm--agent-ia-de-codage--darchitecture-coding-agent)
  * [Métadonnées Machine des Parties (`project-parts.json`)](./project-parts.json)
* **Agent IA de Recherche Quantitative & Backtest (`Quant Agent`)** :
  * [Invariants de Réalisme Quantitatif pour LLM (Guide Multi-Audiences §6)](./guide-audiences-humains-et-llm.md#6-llm--agent-ia-de-recherche-quantitative--stratégie-quant-agent)
  * [Contexte & Invariants LLM — Futures CME & Réalisme (`llm-context-futures-and-realism.md`)](./llm-context-futures-and-realism.md)
  * [Format XML StrategyQuant X (`sq-xml-format.md`)](./sq-xml-format.md) & [Contrat de Placement des Stratégies (`strategy-home.md`)](./strategy-home.md)
* **Agent IA d'Opérations, Déploiement & SRE (`Ops Agent`)** :
  * [Invariants Opérationnels & Sécurité Courtiers pour LLM (Guide Multi-Audiences §7)](./guide-audiences-humains-et-llm.md#7-llm--agent-ia-dopérations-déploiement--sre-ops-agent)
  * [Architecture des Scripts, SQX & Démons IBKR/OANDA (`architecture-trading-bridge-scripts.md`)](./architecture-trading-bridge-scripts.md)

---

## 2. Documentation Générée par Partie du Monorepo (Deep Scan — Nov 2026)

### Vue Globale & Intégration
* **[Présentation Générale (`project-overview.md`)](./project-overview.md)** — Résumé exécutif, structure en 4 parties et pile technologique.
* **[Analyse de l'Arborescence (`source-tree-analysis.md`)](./source-tree-analysis.md)** — Arborescence complète annotée des 11 modules Maven, de l'application Electron, du Dashboard Laravel et des 91 scripts.
* **[Architecture d'Intégration (`integration-architecture.md`)](./integration-architecture.md)** — Flux IPC, HTTP REST, WebSocket, socket TCP IBKR TWS, OANDA v20, CLI `sqcli` et webhooks Hermes Web.
* **[Guide de Développement Global (`development-guide.md`)](./development-guide.md)** — Prérequis, installation et commandes de build/test.
* **[Guide de Déploiement (`deployment-guide.md`)](./deployment-guide.md)** — Déploiement atomique `deploy-paper.sh`, portes pré-déploiement et isolation des 3 couloirs Docker (`paper`, `dev`, `futures`).
* **[Métadonnées des Parties (`project-parts.json`)](./project-parts.json)** — Descripteur JSON structuré des 4 parties et de leurs points d'intégration.

### Partie 1 : Cœur Java & Control Plane (`trading-bridge-java`)
* **[Architecture Technique Java (`architecture-trading-bridge-java.md`)](./architecture-trading-bridge-java.md)** — Graphe acyclique (DAG) des 11 modules Maven, garde-fous `OrderTripwire` / `SmallAccountMarginGuard`, réalisme et moteurs WFA/Génétique/IA.
* **[Contrats d'API HTTP & WebSocket (`api-contracts-trading-bridge-java.md`)](./api-contracts-trading-bridge-java.md)** — Catalogue exhaustif des **70 endpoints** Javalin (`ControlPlaneServer` & `BacktestController`).
* **[Modèles de Données SQLite (`data-models-trading-bridge-java.md`)](./data-models-trading-bridge-java.md)** — Schémas DDL des **9 tables SQLite WAL** (`backtest_runs`, `trades`, `trade_alignment`, `wfa_runs`, `run_records`, `events`, `deployments`, `instruments`, `research_inspirations`).
* **[Guide de Développement Java (`development-guide-trading-bridge-java.md`)](./development-guide-trading-bridge-java.md)** — Conventions Java 21, règles du DAG et ajout de stratégies/endpoints.

### Partie 2 : Poste de Travail Bureau (`trading-bridge-desktop`)
* **[Architecture Technique Desktop (`architecture-trading-bridge-desktop.md`)](./architecture-trading-bridge-desktop.md)** — Processus principal Electron (spawner JVM port `8080`), client MVVM Vue 3.5 et graphiques financiers `lightweight-charts`.
* **[Inventaire des Composants UI Desktop (`component-inventory-trading-bridge-desktop.md`)](./component-inventory-trading-bridge-desktop.md)** — Catalogue détaillé des **9 vues** (incluant `StrategyGeneratorView` et `WfaView`), des **15 composants** et des **6 composables** (`useCostPresets`, `useStrategyCatalog`).
* **[Guide de Développement Desktop (`development-guide-trading-bridge-desktop.md`)](./development-guide-trading-bridge-desktop.md)** — Workflow HMR Vite/Electron, typage TypeScript et packaging natif avec JRE `jlink`.

### Partie 3 : Console Web d'Administration (`trading-bridge-dashboard`)
* **[Architecture Technique Dashboard (`architecture-trading-bridge-dashboard.md`)](./architecture-trading-bridge-dashboard.md)** — Patron MVC SSR Laravel 13.8 et tolérance aux pannes hors-ligne.
* **[Contrats Web Dashboard (`api-contracts-trading-bridge-dashboard.md`)](./api-contracts-trading-bridge-dashboard.md)** — Routes `/control` et `/control/strategies/{id}/kill`, et distinction avec le portail externe `hermes-web`.
* **[Modèles de Données Dashboard (`data-models-trading-bridge-dashboard.md`)](./data-models-trading-bridge-dashboard.md)** — Utilisation locale de SQLite par Laravel.
* **[Inventaire des Composants Dashboard (`component-inventory-trading-bridge-dashboard.md`)](./component-inventory-trading-bridge-dashboard.md)** — Structure des 6 blocs de `control-room.blade.php` et `ControlPlaneClient`.
* **[Guide de Développement Dashboard (`development-guide-trading-bridge-dashboard.md`)](./development-guide-trading-bridge-dashboard.md)** — Installation Composer, configuration `.env` et tests `PHPUnit`.

### Partie 4 : Scripts, Automatisation SQX, Télémétrie & Infrastructure (`trading-bridge-scripts`)
* **[Architecture des Scripts & Infrastructure (`architecture-trading-bridge-scripts.md`)](./architecture-trading-bridge-scripts.md)** — Pipeline StrategyQuant X (`sqx_ctrl.py`, `sqx_telemetry_pusher.py`, `sqx_auto_pipeline.py`), démons IBKR (`ibkr_pnl_push.py`, `ibkr_snapshot_push.py`) et couloirs Docker.
* **[Formats Binaires, Configs & Journaux d'État (`data-models-trading-bridge-scripts.md`)](./data-models-trading-bridge-scripts.md)** — Structure de `config/live-config.json`, `ibkr-fills-journal.json`, `pnl_state.json`, `.bars` et `dailyEquity.bin` (`.sqx`).
* **[Guide de Développement Scripts & Recherche (`development-guide-trading-bridge-scripts.md`)](./development-guide-trading-bridge-scripts.md)** — Commandes CLI SQX, auto-tests des démons IBKR et exécution des harnais d'études quantitatives Simons (35e à 65e).

---

## 3. Documentation de Référence, Runbooks & Audits Existants

### Réalisme, Stratégies & Spécifications Métier
* **[TRADING-GUARDRAILS.md](./TRADING-GUARDRAILS.md)** — Les 8 règles d'or obligatoires avant tout développement ou déploiement.
* **[lt-strategy-playbook.md](./lt-strategy-playbook.md)** — Synthèse de 31 itérations de recherche long-terme sur 20 ans de données multi-actifs.
* **[real-cost-model-hourly-spec.md](./real-cost-model-hourly-spec.md)** — Spécification empirique des multiplicateurs horaires de spread NY et du triple swap.
* **[mes-cme-futures-realism-guide.md](./mes-cme-futures-realism-guide.md)** — Spécifications des contrats Micro Futures CME (`MES`, `MNQ`, `MGC`), calendrier Globex et rollovers.
* **[llm-context-futures-and-realism.md](./llm-context-futures-and-realism.md)** — Contexte condensé des invariants Futures/Réalisme pour agents IA.
* **[specs.md](./specs.md)** — Spécifications formelles de l'API `Strategy` et des modèles du domaine.
* **[strategy-home.md](./strategy-home.md)** & **[strategy-naming.md](./strategy-naming.md)** — Règles de placement, de file d'ordres et de nommage des stratégies.
* **[sq-xml-format.md](./sq-xml-format.md)** & **[sq-cli-bridge.md](./sq-cli-bridge.md)** — Grammaire XML StrategyQuant X et pont d'intégration CLI.
* **[conversion-guide.md](./conversion-guide.md)** — Guide de migration JForex vers Java pur.
* **[calendar-mood-strategy-guide.md](./calendar-mood-strategy-guide.md)** — Conception de stratégies basées sur le calendrier macroéconomique et le sentiment.
* **[data-inventory.md](./data-inventory.md)** — Inventaire des séries historiques locales disponibles.
* **[testing.md](./testing.md)** — Stratégie de tests, Golden Baseline et seuils de promotion.

### Opérations, Runbooks SRE & Suivi d'Exécution
* **[paper-window-log.md](./paper-window-log.md)** — Journal opérationnel continu des fenêtres de validation Paper (OANDA & IBKR CME Futures).
* **[operations-manifest.md](./operations-manifest.md)** — Procédures opérationnelles standard (SOP) et revues périodiques.
* **[reliability-engineering.md](./reliability-engineering.md)** — SLOs, runbooks SRE, reprise après sinistre et monitoring.
* **[deploy-runbook.md](./deploy-runbook.md)** & **[run-promotion-playbook.md](./run-promotion-playbook.md)** — Procédures de déploiement et de promotion de stratégies.
* **[prop-shop-runbook.md](./prop-shop-runbook.md)** & **[operator-dashboard-guide.md](./operator-dashboard-guide.md)** — Exploitation quotidienne du desk.
* **[pre-flight-checklist.md](./pre-flight-checklist.md)**, **[daily-weekly-review-process.md](./daily-weekly-review-process.md)** & **[incident-severity-matrix.md](./incident-severity-matrix.md)** — Checklists opérationnelles.
* **Runbooks d'Incidents Ciblés (`docs/runbooks/`)** :
  * [Déconnexion Courtier OANDA (`runbooks/broker-disconnected-oanda.md`)](./runbooks/broker-disconnected-oanda.md)
  * [Publication Télémétrie IBKR vers Hermes (`runbooks/ibkr-hermes-push.md`)](./runbooks/ibkr-hermes-push.md)

### Vision, Feuille de Route & Audits Récents
* **[VISION.md](./VISION.md)** & **[MISSION_CONTROL.md](./MISSION_CONTROL.md)** — Vision long-terme et jalons stratégiques.
* **[sprint-plan.md](./sprint-plan.md)** — Plan d'exécution des sprints (Octobre–Novembre 2026).
* **[deployed-set-audit-2026-10-16.md](./deployed-set-audit-2026-10-16.md)** — Audit de rentabilité sous coûts horaires réels ayant conduit à l'isolation du couloir `trading-dev`.
* **[restructuration-prod-research.md](./restructuration-prod-research.md)** & **[analyse-strategique.md](./analyse-strategique.md)** — Analyses architecturales et stratégiques.
* **[contributing.md](./contributing.md)** — Guide d'accueil des contributeurs.

---

## 4. Démarrage Rapide

```bash
# 1. Compiler et tester l'ensemble des 11 modules Java
./mvnw clean install

# 2. Lancer l'application de bureau Electron + Vue 3 (démarre automatiquement le Control Plane sur :8080)
./start-desktop.sh

# 3. Lancer la console Web Laravel (sur http://127.0.0.1:8000/control)
cd dashboard && composer run setup && composer run dev

# 4. Vérifier l'état du pipeline StrategyQuant X et de la porte pré-déploiement OANDA Paper
python3 scripts/sqx_ctrl.py status
./scripts/pre-deploy-gate.sh
```

---

_Documentation générée et validée via le workflow BMAD `bmad-document-project` (Deep Scan — 2026-11-05)._
