# Trading Bridge

> Pont de bout en bout entre l'idée (stratégie) et l'exécution (OANDA / IBKR).
> Projet personnel — [Martin Fournier](https://martinfournier.com)

[![CI](https://github.com/martinfou/trading-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/martinfou/trading-bridge/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-%23ED8B00?logo=openjdk)
![Maven](https://img.shields.io/badge/Maven-4.x-C71A36?logo=apache-maven)
[![OANDA](https://img.shields.io/badge/OANDA-v20-%233498DB)](https://developer.oanda.com/)

---

## 🧭 Architecture

```
trading-core/         Modèles de domaine (Bar, Order, Position, Trade)
trading-backtest/     BacktestEngine, RunContext, rapports PDF/HTML
trading-data/         HistoricalDataLoader, OANDA price client
trading-strategies/   55+ stratégies prop (créatives, génétiques)
trading-broker/       OANDA v20 + IBKR (paper/live)
trading-runtime/      Serveur HTTP, promote gates, event store
trading-examples/     CLI RunBacktest, golden tests
trading-genetics/     Optimisation génétique offline
trading-parser/       Parsing XML StrategyQuant → Java (🚧)
trading-tui/          Client terminal JLine3
trading-runtime/      Control plane, promote gates, kill switch
```

Le module `trading-core` est le socle sans dépendances internes. Tout est orienté autour de l'interface `Strategy` :
une classe Java, une liste de `Bar` en entrée, une file d'ordres `Order` en sortie.

---

## 🚀 Quick Start

### Prérequis

- **Java 21+** (Temurin recommandé)
- **Maven 3.9+**

### Build

```bash
mvn clean install
```

### Backtest d'une stratégie

```bash
# Lister les stratégies disponibles
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--list"

# Backtest sample (SmaCrossover)
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--sample"
```

### Déploiement OANDA (paper trading)

```bash
docker compose build trader && docker compose up -d trader
```

Variables d'environnement requises : `OANDA_API_KEY`, `OANDA_ACCOUNT_ID` dans `.env`.

---

## ⚙️ Backtest Engine — Fonctionnalités clés

| Fonctionnalité | Statut | Description |
|---|---|---|
| MARKET / LIMIT / STOP fills | ✅ | À l'ouverture de la barre, avec slippage configurable |
| Commission + slippage | ✅ | Fixe ($) ou % du notionnel |
| **Hedging (Edging)** | ✅ | Ordres opposés créent des hedge positions (comme OANDA `hedgingEnabled=true`) |
| **REDUCE_ONLY** | ✅ | `.closeOnly()` = ordre REDUCE_ONLY sans réouverture |
| SL / TP par position | ✅ | Indépendants pour chaque position, même sur le même symbole |
| Monte Carlo | ✅ | 1000 runs, block bootstrap |
| Walk-Forward | ✅ | IS/OOS 70/30 avec dégradation PF |
| Métriques avancées | ✅ | Sharpe, Sortino, Calmar, Profit Factor |
| Rapports PDF | ✅ | Style StrategyQuant (portrait A4, KPI tiles, equity chart) |
| Rapports HTML | ✅ | Due diligence export (sans CDN, consultable hors-ligne) |
| JSONL events | ✅ | Machine-readable pour Laravel dashboard |

### Hedging — Ce qui a changé (Juin 2026)

Le backtest engine reproduit maintenant le comportement **OANDA hedging-enabled** :

| Ordre | Comportement avant | Comportement maintenant |
|---|---|---|
| BUY puis SELL (sans `.closeOnly()`) | SELL fermait le BUY | SELL crée un **SHORT hedge** |
| BUY puis SELL `.closeOnly()` | inchangé | REDUCE_ONLY : réduit la position BUY |
| SL sur hedge | inchangé | Ne ferme que la position touchée |
| Same-side (scale-in) | inchangé | `addQuantity()` comme avant |

> ⚠️ Les 55 stratégies prop utilisent déjà `.closeOnly()` sur leurs `closePosition()`.
> Si tu écris une nouvelle stratégie, **chaîne toujours `.closeOnly()` sur tes ordres de sortie**
> pour garantir la parité backtest ↔ live OANDA.

---

## 📊 Paper Trading (Actif)

| Conteneur | Stratégie | Paire | Granularité |
|---|---|---|---|
| `trading-comp-momentum` | CompositeMomentumRanking | USD/JPY | H1 |
| `trading-month-week` | MonthWeekPhase | USD/JPY | H1 |
| `trading-nfp-week` | NfpWeekShortEURUSD | EUR/USD | H1 (NFP week) |

Monitoring : `docker compose ps` + `curl -s "https://api-fxpractice.oanda.com/v3/accounts/{id}/summary"`

---

## 🧪 Tests

```bash
# Tous les tests
mvn clean install

# Tests backtest seulement
mvn test -pl trading-backtest

# Tests avec scénarios déterministes + edging
mvn test -pl trading-backtest -Dtest="BacktestEngineContractTest,PlatformRobustnessTest"

# Golden backtest (nécessite data/ci/ — présent dans le repo)
mvn test -pl trading-examples -Dtest=GoldenBacktestTest
```

Le projet contient **118+ tests** couvrant :
- 20+ scénarios de fills (MARKET, LIMIT, STOP, SL, TP)
- 6 scénarios d'edging (coexistence long/short, SL indépendants, closeOnly no-op)
- Parité BACKTEST ↔ PAPER (mêmes résultats)
- Invariants comptables (totalPnl = sum(pnl) - costs)

---

## 🐛 Problèmes connus et correctifs

| # | Problème | Fix | Commit |
|---|---|---|---|
| 1 | Hedging : les ordres de sortie créaient des hedges au lieu de fermer | `Order.closeOnly()` + `REDUCE_ONLY` sur OANDA | `dde8812` |
| 2 | Crash recovery : état non persisté après restart Docker | Reflection-based save/restore (5 getters + restoreState) | Mai 2026 |
| 3 | SL/TP non chaînés sur OANDA | `.withStopLoss()` + `.withTakeProfit()` obligatoires | Mai 2026 |
| 4 | Equity curve plateau (flip positions) | 3-way branch dans processOrders() | Mai 2026 |
| 5 | Paires JPY : stop orders rejetés (précision 5 décimales) | `formatPrice()` par instrument | `#fix-jpy` |
| **6** | **Backtest ≠ Live : edging non modélisé** | **Hedging complet dans BacktestEngine** | **`aea009a`** ✅ |
| 7 | Sharpe négatif sur 20 ans | Prioriser PF + WR + DD comme filtres | Documentation |

---

## 📚 Documentation

| Document | Contenu | Langue |
|---|---|---|
| [`docs/README.md`](docs/README.md) | Architecture, modules, CLI, formats | FR |
| [`docs/testing.md`](docs/testing.md) | Golden backtest, tests, CI, promote gates | EN |
| [`docs/architecture.md`](docs/architecture.md) | Graphe de modules, dépendances | EN/FR |
| [`docs/specs.md`](docs/specs.md) | Data models, Strategy API, XML shape | EN |
| [`docs/conversion-guide.md`](docs/conversion-guide.md) | JForex → Java mapping | EN |
| [`docs/prop-shop-runbook.md`](docs/prop-shop-runbook.md) | Paper → LIVE promotion checklist | EN |
| [`docs/MISSION_CONTROL.md`](docs/MISSION_CONTROL.md) | Dashboard Laravel | FR |
| `AGENTS.md` | Instructions pour les AI coding agents | EN |

---

## 🔧 Stack technique

- **Java 21** — Records, sealed classes, pattern matching
- **Maven 4.x** — Multi-module, version `1.0.0-SNAPSHOT`
- **JUnit 5** — Tests paramétrés, invariants
- **Jackson 2.17** — JSON serialization
- **SLF4J 2.0 + Logback** — Logging structuré
- **Docker** — Déploiement OANDA (image `maven:3-eclipse-temurin-21`)
- **OANDA v20 REST API** — Broker primaire

---

📄 Usage personnel — Martin Fournier — 2026
