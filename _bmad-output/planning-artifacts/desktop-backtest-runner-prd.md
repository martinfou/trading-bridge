# PRD — Trading Bridge Desktop (Electron)

> **Projet** : Trading Bridge — Backtest Runner Desktop
> **Version PRD** : v1 — 2026-06-04
> **Track** : BMad Method
> **PO** : Martin Fournier

---

## 1. Problem Statement

Le dashboard Laravel existant (`dashboard/`) est limité à une control room minimaliste : statut des runs, kill switch. Il n'y a **aucune interface graphique** pour :

- Lancer un backtest depuis une UI (nécessite la CLI Maven)
- Visualiser les résultats (equity curve, trades, métriques)
- Comparer des runs
- Explorer un strategy catalog visuellement

Les backtests passent tous par le terminal (`mvn exec:java ...`). Les résultats sont des logs texte ou des PDF générés séparément.

## 2. Vision

Une application desktop Electron qui se connecte au **Java control plane** existant (REST + WebSocket) pour :

1. Lancer des backtests depuis une interface graphique
2. Voir les résultats en temps réel (streaming WebSocket)
3. Analyser les résultats avec des charts (equity, drawdown, monthly returns, trade distribution)
4. Parcourir le strategy catalog (55+ stratégies)

Le dashboard Laravel **reste** pour la control room / promote / kill.

## 3. User Stories

| # | En tant que... | Je veux... | Pour... |
|---|---|---|---|
| US-1 | Trader | Sélectionner une stratégie, une paire, une période et lancer un backtest | Éviter la CLI |
| US-2 | Trader | Voir l'equity curve se tracer en temps réel pendant le backtest | Valider visuellement le comportement |
| US-3 | Trader | Voir les métriques clés (Sharpe, PF, DD, trades, win rate) en un coup d'œil | Prendre une décision rapide |
| US-4 | Trader | Explorer la liste complète des trades avec filtres (date, sens, P&L) | Analyser en détail |
| US-5 | Trader | Voir le monthly returns heatmap / bar chart | Identifier les patterns saisonniers |
| US-6 | Trader | Changer les paramètres de coût (commission, slippage) avant de lancer | Ajuster le réalisme |
| US-7 | Trader | Exporter le résultat en PDF ou CSV | Partager ou archiver |
| US-8 | Trader | Lancer plusieurs backtests en séquence et les comparer | Qualifier les stratégies |
| US-9 | Trader | Voir le strategy catalog avec les déploiements actifs | Savoir ce qui tourne |
| US-10 | Trader | Synchroniser les données historiques (check du data directory) | Savoir si les données sont à jour |

## 4. Contraintes techniques

| Contrainte | Valeur |
|---|---|
| Runtime | Electron (Node.js + Chromium) |
| Langage | TypeScript (strict) |
| Frontend | Vue 3 + Vite |
| Charting | **TradingView Lightweight Charts** (`lightweight-charts`) |
| Communication | REST (Axios/Fetch) + WebSocket (native WS) |
| Cible API | Java control plane `http://localhost:8080` |
| Modules du control plane utilisés | `POST /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/{id}/events?afterSequence=N`, `WS /ws/runs/{id}`, `GET /api/strategies`, `GET /api/runs/{id}/export` |
| Build | `npm` / `yarn`, packaging via `electron-builder` |
| CI | GitHub Actions (lint, test, build) |

## 5. Non-goals (ce qu'on ne fait PAS dans v1)

- ✗ Éditer / créer des stratégies (catalog read-only)
- ✗ Promouvoir des stratégies (reste dans le dashboard Laravel)
- ✗ Kill switch depuis l'app (reste dans Laravel)
- ✗ Live trading temps réel (v2 envisagée)
- ✗ Backend Rust (Electron/TS uniquement)
- ✗ Mobile

## 6. Architecture pressentie

```
┌─────────────────────┐     REST + WS      ┌──────────────────┐
│  Electron App       │ ◄─────────────────► │  Java Control    │
│  (TypeScript/Vue 3) │                    │  Plane (port 8080)│
│                     │                    │                  │
│  ┌───────────────┐  │                    │  /api/runs       │
│  │ Main Process  │  │                    │  /api/strategies │
│  │ (Node.js)     │  │                    │  /ws/runs/{id}   │
│  └───────┬───────┘  │                    └──────────────────┘
│          │ IPC       │
│  ┌───────┴───────┐  │
│  │ Renderer      │  │
│  │ (Vue 3 SPA)   │  │
│  │ + lightweight- │  │
│  │   charts       │  │
│  └───────────────┘  │
└─────────────────────┘
```

## 7. Pages / vues (v1)

| Page | Contenu |
|---|---|
| **Dashboard** | Stratégie sélectionnée, formulaire de lancement (paires, période, capital, costs), bouton RUN, equity chart en temps réel |
| **Results** | KPI strip (Sharpe, PF, DD, trades, win rate), monthly returns, trade list filtrable, bouton export |
| **Strategies** | Grid/list des 55+ stratégies, filtre, search, status badge (déployé/non), expand pour détails |
| **Compare** | Side-by-side des runs récents avec métriques |

## 8. Questions ouvertes

1. **Build du backtest** : le control plane compile-t-il à la volée ou faut-il pré-compiler ? (vérifier RunManager.startRun — il utilise StrategyCatalog déjà compilé)
2. **Progress streaming** : combien d'événements par barre ? Suffisant pour un vrai équity curve en temps réel ?
3. **Multi-période** : UI pour sélectionner les barres historiques (année, plage personnalisée) ?

J'attends ton aval sur ce PRD avant de passer à l'architecture détaillée + Epics & Stories.
