# Analyse de l'Arborescence du Projet — Trading Bridge

**Date de mise à jour :** 2026-11-05  
**Type de dépôt :** Monorepo multi-technologies à 4 parties (`Java 21`, `TypeScript/Vue 3/Electron`, `PHP 8.3/Laravel 13`, `Python 3/Bash/Docker`)

---

## 1. Vue d'Ensemble de la Structure Multi-Parties

Le dépôt **Trading Bridge** est organisé en **4 parties complémentaires** :
1. **`trading-bridge-java`** (`/`) : Moteur central Java 21 / Maven 4 composé de **11 modules** (domaine, données, stratégies, backtest, courtiers OANDA/IBKR, parseur SQX, algorithmes génétiques, intelligence LLM, Control Plane HTTP/WS, exemples CLI et TUI JLine3).
2. **`trading-bridge-desktop`** (`/desktop`) : Poste de travail natif Electron 33 + Vue 3.5 + TradingView Lightweight Charts 5.
3. **`trading-bridge-dashboard`** (`/dashboard`) : Console Web SSR légère en PHP 8.3 / Laravel 13.8 pour le suivi rapide et le Kill Switch d'urgence.
4. **`trading-bridge-scripts`** (`/scripts`, `/docker`, `/config`, `/deploy`, `run-*.sh`) : Écosystème d'automatisation StrategyQuant X (`sqcli`), démons de télémétrie IBKR/OANDA vers Hermes Web, sondes de recherche quantitative (série Simons 35e–65e) et infrastructure Docker multi-couloirs (`paper`, `dev`, `futures`).

---

## 2. Arborescence Annotée Complète

```text
trading-bridge/
├── pom.xml                                  # POM parent Maven (Java 21, 11 modules, versions centralisées)
├── Dockerfile                               # Build multi-stage (Maven 21 -> Eclipse Temurin 21 JRE + SQLite3)
├── docker-compose.yml                       # Couloirs Docker OANDA : trading-live (paper) & trading-dev (dev)
├── docker-compose.dev.yml                   # Couloir de développement isolé (projet tb-dev, ./data-dev)
├── config/
│   ├── live-config.json                     # Configuration maître des 24 stratégies Paper/Dev & budgets de risque
│   └── runs/                                # Spécifications JSON déclaratives des runs MGC M30
├── docker/
│   ├── docker-compose.futures.yml           # Couloir Docker IBKR CME Micro Futures (conteneur trading-futures)
│   ├── docker-compose.ibkr.yml              # Stack conteneurisée IB Gateway + IBC + Xvfb
│   ├── Dockerfile.ibgateway                 # Image Ubuntu 22.04 + IB Gateway 10.19 + IBC 3.18 + socat
│   └── start-gateway.sh                     # Script de démarrage supervisé d'IB Gateway
│
├── trading-core/                            # [Module 1] Primitives, Calendriers (FX/CME/NYSE), Marges, Tripwire, 23+ Indicateurs
├── trading-data/                            # [Module 2] Chargeurs CSV/.bars, Fetchers (OANDA/Dukascopy/Yahoo/IBKR), SqliteUniverseStore
├── trading-strategies/                      # [Module 3] Catalogue unifié (9 familles, 130+ stratégies) + LiveStrategyRunner autonome
├── trading-backtest/                        # [Module 4] BacktestEngine, RealCostModel, SwapCalculator, WFA, MonteCarlo, Stores SQLite
├── trading-broker/                          # [Module 5] Connecteurs OANDA v20 & IBKR TWS (Brackets, Reconciler, AutoRollover CME)
├── trading-parser/                          # [Module 6] Parseur XML StrategyQuant X, interpréteur AST, générateur Java & SqInboxProcessor
├── trading-genetics/                        # [Module 7] Moteur évolutionnaire natif (24 blocs, RobustnessScore, codegen Java)
├── trading-intelligence/                    # [Module 8] AgenticStrategistService (LangChain4j ReAct) & Weekly Strategy Builder
├── trading-runtime/                         # [Module 9] ControlPlaneServer (70 routes HTTP/WS), RunManager, DriftEngine, Watchdogs
├── trading-examples/                        # [Module 10] Harnais CLI (RunBacktest, WalkForwardCli, SmaCrossover, études Simons 35e-55e)
├── trading-tui/                             # [Module 11] Client terminal interactif JLine3 (16 commandes slash)
│
├── desktop/                                 # [Partie 2 : Application Bureau Electron 33 + Vue 3.5]
│   ├── package.json                         # Manifeste NPM & configuration electron-builder (mac/linux/win)
│   ├── electron/
│   │   ├── main.ts                          # Spawner JVM (port 8080), détection de conflits, splash screen
│   │   └── preload.ts                       # Pont IPC contextBridge (window.electronAPI)
│   ├── scripts/
│   │   └── build-jre.sh                     # Construction d'un JRE minimal embarqué via jdeps + jlink
│   └── src/
│       ├── main.ts                          # Initialisation Vue 3 + Vue Router (10 routes)
│       ├── App.vue                          # Shell principal, barre latérale 8 sections, barre de statut UTC
│       ├── types/control-plane.ts           # Contrats TypeScript alignés sur le Control Plane Java
│       ├── composables/                     # useControlPlane, useRunWebSocket, useCostPresets, useStrategyCatalog
│       ├── views/                           # 9 vues (Dashboard, StrategyGenerator, Wfa, DataManager, LiveTrading, Compare...)
│       └── components/                      # 15 composants (TradeChart, EquityChart IS/OOS, UniverseManagerDrawer...)
│
├── dashboard/                               # [Partie 3 : Console Web PHP 8.3 + Laravel 13.8]
│   ├── composer.json                        # Dépendances PHP 8.3 / Laravel 13.8
│   ├── config/trading.php                   # Configuration CONTROL_PLANE_URL (:8080) & rafraîchissement (5s)
│   ├── routes/web.php                       # Routes GET /control et POST /control/strategies/{id}/kill
│   ├── app/
│   │   ├── Http/Controllers/ControlRoomController.php
│   │   └── Services/ControlPlaneClient.php  # Client HTTP vers /api/health, /api/control/summary, /api/strategies/{id}/kill
│   └── resources/views/control-room.blade.php
│
├── scripts/                                 # [Partie 4 : Automatisation SQX, Télémétrie IBKR/OANDA, Recherche & Ops]
│   ├── sqx_ctrl.py                          # Contrôleur CLI StrategyQuant X (list, status, start, stop, top)
│   ├── extract_sqx_backtest.py              # Décodeur binaire dailyEquity.bin (.sqx) & métriques IS/OOS (R², UPI, Sharpe)
│   ├── sqx_telemetry_pusher.py              # Démon temps réel (4s) poussant la télémétrie SQX vers Hermes Web
│   ├── sqx_auto_pipeline.py                 # Pipeline SQX -> Parité Java -> live-config.json -> Hermes Web
│   ├── create_institutional_project_suite.py# Générateur programmatique de projets .cfx SQX (MES, MNQ, MGC, MCL)
│   ├── create_oanda_project_suite.py        # Générateur de projets .cfx SQX OANDA FX avec filtre de sortie 16:30 EST
│   ├── ibkr_pnl_push.py                     # Démon horaire d'import P&L IBKR vers Hermes Web (CommissionReport + USD->CAD)
│   ├── ibkr_snapshot_push.py                # Démon 5 min de photographie de compte/positions IBKR vers Hermes Web
│   ├── deploy-paper.sh                      # Déployeur atomique OANDA Paper avec verrou flock & réconciliation activeTrades
│   ├── pre-deploy-gate.sh                   # Porte pré-déploiement en 3 étapes (Tests Maven + Compose + Smoke OANDA)
│   ├── smoke-oanda-readonly.py              # Vérification lecture seule du compte OANDA Practice (-004, devise CAD)
│   ├── prune-db.sh                          # Purge et compactage quotidien (04:17) de data/runtime/events.db
│   ├── agy-review.sh                        # Revue de code adversariale automatisée via AGY CLI
│   └── fade-*.py, spread-*.py, gold-*.py    # Sondes empiriques de microstructure et saisonnalité (études 54e à 65e)
│
├── run-*.sh                                 # 20 scripts racines d'exécution des harnais de recherche Java (35e à 55e)
├── start-control-plane.sh                   # Lanceur rapide du serveur Control Plane Java (:8080)
├── start-desktop.sh                         # Lanceur rapide de l'application Electron Desktop
├── start-tui.sh                             # Lanceur rapide du client terminal JLine3
├── docs/                                    # Base de connaissances du projet (Architecture, Guides par Audience, Runbooks)
└── _bmad-output/
    ├── project-context.md                   # Fichier d'ancrage des règles et invariants pour les agents IA
    └── implementation-artifacts/            # Suivi des sprints (sprint-status.yaml) et spécifications de stories
```

---

## 3. Points d'Entrée Principaux par Partie

| Partie | Point d'Entrée | Commande ou Classe de Démarrage |
| :--- | :--- | :--- |
| **Java Control Plane** | `trading-runtime/.../ControlPlaneMain.java` | `./start-control-plane.sh` ou `java -jar trading-runtime/target/trading-runtime-1.0.0-SNAPSHOT-shaded.jar` |
| **Java Runner OANDA** | `trading-strategies/.../LiveStrategyRunner.java` | `scripts/docker-entrypoint.sh` (conteneurs `trading-live` et `trading-dev`) |
| **Java Runner IBKR Futures** | `trading-runtime/.../IbkrLiveRunner.java` | `scripts/docker-entrypoint-ibkr.sh` (conteneur `trading-futures`) |
| **Java TUI Client** | `trading-tui/.../TradingTuiMain.java` | `./start-tui.sh` |
| **Electron Desktop** | `desktop/electron/main.ts` & `desktop/src/main.ts` | `./start-desktop.sh` ou `cd desktop && npm run electron:dev` |
| **Laravel Dashboard** | `dashboard/public/index.php` (`routes/web.php`) | `cd dashboard && composer run dev` |
| **Pipeline SQX & Télémétrie** | `scripts/sqx_ctrl.py` & `scripts/sqx_telemetry_pusher.py` | `python3 scripts/sqx_ctrl.py status` |
