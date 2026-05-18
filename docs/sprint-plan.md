# Bmad Sprint Plan — Trading Bridge

> Planning agile pour le projet Trading Bridge
> Martin Fournier — Mai 2026

---

## Sprint 1 ✅ — Fondation (TERMINÉ)

**Objectif :** Structure du projet + backtest basique fonctionnel

| Tâche | Statut | Notes |
|-------|--------|-------|
| Structure monorepo Maven | ✅ | 5 modules |
| Modèles de données | ✅ | Bar, Order, Position, Trade |
| Interface Strategy | ✅ | onBar, onTick, getPendingOrders |
| BacktestEngine basique | ✅ | Market orders, SMA, equity curve |
| DataLoader CSV | ✅ | Standard + StrategyQuant |
| Exemple SMA Crossover | ✅ | 13 trades sur données random |
| Documentation | ✅ | README, specs, conversion guide |

**Livrables :**
- `pom.xml` — parent multi-module
- `trading-core/` — domain models
- `trading-backtest/` — moteur de simulation
- `trading-examples/` — stratégie exemple + lanceur
- `docs/` — documentation complète

---

## Sprint 2 🚧 — Parser XML StrategyQuant

**Objectif :** Convertir les stratégies StrategyQuant (XML + JForex) en Java

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Analyser le format XML de StrategyQuant | P0 | 🚧 |
| Créer SqXmlParser (JAXB/Jackson XML) | P0 | 🚧 |
| Modèle StrategyConfig (POJO miroir du XML) | P0 | 🚧 |
| Indicateurs: SMA, EMA, RSI | P0 | 🚧 |
| Indicateurs: MACD, Bollinger, ATR | P1 | 🚧 |
| Conditions d'entrée (crossover, niveau, etc.) | P0 | 🚧 |
| Conditions de sortie (SL, TP, trailing) | P0 | 🚧 |
| Position sizing (fixe, risque %, martingale) | P1 | 🚧 |
| Générateur de code Java à partir du XML | P0 | 🚧 |

**Définition de fait :**
- Une stratégie XML StrategyQuant est parsée et génère du Java compilable
- Le backtest avec la stratégie parsée donne des résultats cohérents

---

## Sprint 3 — Backtest Avancé

**Objectif :** Backtest réaliste avec佣金, slippage, rapports

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Support ordres LIMIT/STOP | P0 | 🚧 |
| Commission et slippage configurables | P0 | 🚧 |
| Trades avec sorties (TP, SL sur bar) | P0 | 🚧 |
| Multi-timeframe (plusieurs séries de bars) | P1 | 🚧 |
| Gestion de risque (taille position %, daily loss) | P1 | 🚧 |
| Rapport HTML avec graphiques (Chart.js) | P2 | 🚧 |
| Export CSV des trades | P1 | 🚧 |
| Métriques avancées (Sharpe, Profit Factor, Calmar) | P1 | 🚧 |

---

## Sprint 4 — Connecteurs Brokers

**Objectif :** Exécution live sur OANDA + Interactive Brokers

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Interface Broker commune | P0 | 🚧 |
| OANDA v20 REST API (compte démo) | P0 | 🚧 |
| Interactive Brokers API (TWS/IB Gateway) | P0 | 🚧 |
| Market data temps réel (tick + bar) | P0 | 🚧 |
| Exécution ordres MARKET | P0 | 🚧 |
| Exécution ordres LIMIT/STOP | P1 | 🚧 |
| Gestion des positions (sync) | P1 | 🚧 |
| Mode simulation live (paper trading) | P2 | 🚧 |

---

## Sprint 5 — Production

**Objectif :** Robustesse, monitoring, déploiement

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Persistance SQLite (trades, equity curve) | P1 | 🚧 |
| Logging structuré (log files, rotation) | P1 | 🚧 |
| Monitoring + alertes Telegram | P1 | 🚧 |
| Dashboard web léger (Spring Boot ou Vue) | P2 | 🚧 |
| Tests unitaires (JUnit 5, 80%+ coverage) | P1 | 🚧 |
| Gestion des erreurs brokers (reconnect) | P1 | 🚧 |
| Documentation utilisateur | P2 | 🚧 |

---

## Légende

| Symbole | Signification |
|:-------:|---------------|
| P0 | Bloquant — doit être fait |
| P1 | Important — devrait être fait |
| P2 | Nice to have — si le temps le permet |
| ✅ | Terminé |
| 🚧 | En cours / À faire |
