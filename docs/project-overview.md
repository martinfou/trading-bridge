# Présentation Générale du Projet — Trading Bridge

**Date de mise à jour :** 2026-11-05  
**Type de projet :** Plateforme Quantitative Multi-Actifs (Monorepo à 4 parties)  
**Langages principaux :** Java 21, TypeScript 5.7 (Vue 3 / Electron 33), PHP 8.3 (Laravel 13.8), Python 3 / Bash  
**Architecture :** Monolithe Modulaire Java Orienté Événements + Poste de Travail Natif Electron + Console Web SSR + Pipeline d'Automatisation SQX/Docker

---

## 1. Résumé Exécutif

**Trading Bridge** est une plateforme d'ingénierie financière et de trading algorithmique de bout en bout conçue pour découvrir, valider, superviser et exécuter des stratégies systématiques sur :
* **Les Contrats à Terme CME Micro/Mini (`FUTURES`)** : `MES` (S&P 500), `MNQ` (Nasdaq-100), `MGC` (Micro Or), `MCL` (Pétrole), `M2K` via **Interactive Brokers (IBKR TWS / IB Gateway)**.
* **Le Marché des Changes et Métaux Spot (`FOREX` / `COMMODITIES`)** : Paires majeures/croisées (`EUR_USD`, `GBP_JPY`, `USD_CAD`, etc.) et Or Spot (`XAU_USD`) via **OANDA REST/Stream v20**.
* **Les Actions Américaines (`EQUITY`)** : Paniers Small-Cap, Momentum et ETFs via IBKR et Yahoo Finance.

La plateforme résout le problème majeur du trading quantitatif — **l'écart entre les backtests théoriques et la réalité d'exécution en production** — en intégrant nativement :
1. Un **modèle de coûts horaires empiriques (`RealCostModel`)** et un **calculateur exact de swaps et triples rollovers (`SwapCalculator`)**.
2. Une **double usine de découverte de stratégies** : pont automatisé avec **StrategyQuant X (`sqcli`)** et **Studio Évolutionnaire Natif (`/generator`)** directement intégré au moteur Java.
3. Un **studio d'analyse Walk-Forward (`/wfa`)** et de simulation **Monte-Carlo** bloquant le surapprentissage avant toute mise en production.
4. Une **architecture d'exécution multi-couloirs isolée** (`trading-futures`, `trading-live`, `trading-dev`) dotée de garde-fous pré-trade (`OrderTripwire`, `SmallAccountMarginGuard`), de coupe-circuits par stratégie et d'un moteur de détection de dérive statistique (`DriftEngine`).

---

## 2. Structure en 4 Parties du Monorepo

| ID de Partie | Nom | Type | Emplacement | Résumé Technique |
| :--- | :--- | :--- | :--- | :--- |
| **`trading-bridge-java`** | Cœur Java & Plan de Contrôle | `backend` | `/` (11 modules Maven) | Java 21, Javalin 6.7 (`70 endpoints HTTP/WS`), SQLite WAL + HikariCP (`9 tables`), LangChain4j 0.33, Ta4j 0.18, JLine 3.26, OpenPDF, IBKR TWS Protobuf. |
| **`trading-bridge-desktop`** | Poste de Travail Bureau | `desktop` | `/desktop` | Electron 33, Vue 3.5, TypeScript 5.7, Vite 6, TradingView Lightweight Charts 5 (`9 vues`, `15 composants`, `6 composables`). |
| **`trading-bridge-dashboard`** | Console Web de Contrôle | `web` | `/dashboard` | PHP 8.3, Laravel 13.8, Blade SSR, Tailwind CSS 4 (Supervision temps réel `/control` + Kill Switch d'urgence). |
| **`trading-bridge-scripts`** | Automatisation SQX, Télémétrie & Infra | `data` / `infra` | `/scripts`, `/docker`, `/config` | 91 scripts Python/Bash + 20 harnais Java : pilotage `sqcli`, décodeur binaire `.sqx`, démons IBKR/OANDA vers Hermes Web, études Simons 35e–65e, déploiement atomique `deploy-paper.sh`. |

---

## 3. Fonctionnalités Clés de la Plateforme

* **Studio Évolutionnaire Natif (`/generator`)** : Algorithmes génétiques et recherche Monte-Carlo sur 24 blocs orthogonaux, séparation IS/OOS aveugle sur les courbes d'équité (`EquityChart.vue`), banque de candidats et génération de code Java en 1 clic.
* **Walk-Forward Analysis (`/wfa`)** : Optimisation glissante ou ancrée, calcul du Walk-Forward Efficiency (WFE), frise chronologique des plis (`WfaTimeline.vue`) et matrice de dérive des paramètres (`WfaParameterStability.vue`).
* **Gestionnaire de Données Multi-Actifs (`/data-manager`)** : Couverture de 8 unités de temps (`m1` à `w1`) et 4 fournisseurs (`yahoo`, `dukascopy`, `oanda`, `ibkr`) avec cache binaire ultra-rapide (`.bars`) et tiroir de gestion d'univers (`UniverseManagerDrawer.vue`).
* **Desk d'Exécution & Audit de Dérive (`/live-trading` & `/compare`)** : Multi-courtiers OANDA + IBKR, conversion multi-devises en direct, carnet de sentiment OANDA (`OrderBook.vue`), Kill Switch d'urgence et réconciliation transaction par transaction entre Live/Paper et Backtest.

---

## 4. Points d'Entrée par Profil (Humains & LLMs)

Consultez le **[Guide Multi-Audiences (Humains & Agents LLM)](./guide-audiences-humains-et-llm.md)** ou l'**[Index Principal de la Documentation (`index.md`)](./index.md)** pour accéder directement aux parcours dédiés :
* **Chercheurs Quantitatifs (Quants)** : Garde-fous, modèles de coûts horaires, études Simons 35e–65e, WFA et SQX.
* **Traders, Opérateurs de Desk & SRE** : Supervision des 3 couloirs Docker, déploiement atomique `deploy-paper.sh`, Kill Switch et runbooks.
* **Ingénieurs Logiciels & Architectes** : DAG des 11 modules Maven, contrats des 70 APIs, schémas des 9 tables SQLite et architecture Electron/Vue 3.
* **Décideurs Produit & Exécutifs** : Vision, gouvernance des risques à 5 niveaux et état du portefeuille actif.
* **Agents IA (LLMs de Codage, Quant & Ops)** : [`_bmad-output/project-context.md`](../_bmad-output/project-context.md) et invariants stricts sans hallucination.
