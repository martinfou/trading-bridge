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

**Objectif :** Backtest réaliste avec commissions, slippage, métriques

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

## Sprint 6 🎯 — Backtesting Avancé & Portfolio Analytics

**Objectif :** Simulations Monte Carlo, Walk-Forward Optimization, matrice de corrélation,
portfolio builder, rapports type StrategyQuant

> Sprint défini via BMAD_SPRINT.md à la racine du projet.
> Sous-agent Bmad en cours d'exécution.

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Métriques avancées (Sharpe, Sortino, Profit Factor, Calmar) | P0 | 🚧 |
| Commission + slippage dans BacktestEngine | P0 | 🚧 |
| MonteCarloSimulation (1000+ runs, distribution) | P1 | 🚧 |
| WalkForwardOptimizer (fenêtres IS/OOS glissantes) | P1 | 🚧 |
| CorrelationMatrix (P&L + drawdown) | P2 | 🚧 |
| PortfolioBuilder (mean-variance, efficient frontier) | P2 | 🚧 |
| Rapports HTML multi-onglets (Chart.js) | P2 | 🚧 |

---

## Sprint 7 🧬 — StrategyQuant Replication (Genetic Engine)

**Objectif :** Générer et tester des stratégies automatiquement comme StrategyQuant — moteur génétique, ranking, export Java

> Décision Bmad Advanced Elicitation — First Principles + Expert Panel + Pre-mortem
> Nouveau module: `trading-genetics/`

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| **StrategyTemplate** — squelette de stratégie avec slots pour indicateurs | P0 | ⏳ |
| **Indicator Gene Pool** — tous les indicateurs comme gènes mutables (SMA, EMA, RSI, MACD, BB, ATR, ADX, Stochastic, Ichimoku) | P0 | ⏳ |
| **Genetic Engine** — population initiale, fitness, crossover, mutation, élitisme, parallélisme Virtual Threads | P0 | ⏳ |
| **Chromosome encoding** — une stratégie = ADN (indicateurs + paramètres + conditions) | P0 | ⏳ |
| **Crossover + Mutation operators** — reproduction, mutation aléatoire des gènes | P0 | ⏳ |
| **Fitness function** — Sharpe, Profit Factor, Walk-Forward OOS, Robustness combiné | P1 | ⏳ |
| **Strategy Code Generator** — Chromosome → Java compilable (StringTemplate) | P1 | ⏳ |
| **Robustness Score** (0-100) — Walk-Forward OOS (40%) + Monte Carlo VaR (30%) + Sharpe stability (20%) + Parameter sensitivity (10%) | P1 | ⏳ |
| **Ranking Dashboard** — top 20/50/100 stratégies avec métriques clés | P1 | ⏳ |
| **Strategy Builder** — arbre de décision pour configurer la génération (type, timeframe, indicateurs) | P2 | ⏳ |
| **Multi-market test** — tester automatiquement sur plusieurs paires | P2 | ⏳ |
| **Parameter Sensitivity Analysis** — stabilité des paramètres | P2 | ⏳ |
| **Export un clic** — générer → compiler → backtester | P2 | ⏳ |

**Définition de fait :**
- `mvn compile` passe avec le module `trading-genetics`
- Genetic Engine génère des stratégies valides qui passent Walk-Forward OOS
- Les stratégies générées sont compilables (`javac`)
- Ranking dashboard montre top 20 avec Robustness Score
- Le système peut battre (ou égaler) la qualité des stratégies générées par StrategyQuant

---

## Sprint 8 📓 — Trade Journal & Psychologie

**Objectif :** Système de journal de bord intelligent, tracking émotionnel, review post-session

> Voir `docs/VISION.md` — Module 7: Trade Journal & Psychology

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Automated journaling (trade → log automatique) | P0 | ⏳ |
| Screenshot capture automatique entrée/sortie | P1 | ⏳ |
| Emotional state tracker (mood rating, tags) | P1 | ⏳ |
| Revenge trading detector (pattern detection) | P2 | ⏳ |
| Post-session review automatisée | P1 | ⏳ |
| Voice notes attachées aux trades + transcription | P2 | ⏳ |
| Performance breakdown (jour/heure/setup) | P1 | ⏳ |
| Consecutive loss tracker + cooling period | P1 | ⏳ |

---

## Sprint 9 🛡️ — Risk Management System

**Objectif :** Protection du capital en temps réel, circuit breakers, VaR, black swan

> Voir `docs/VISION.md` — Module 5: Risk Management

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Real-time max drawdown stop (daily/weekly/total) | P0 | ⏳ |
| VaR (Value at Risk) + CVaR monitoring | P0 | ⏳ |
| Correlation-aware position limits | P0 | ⏳ |
| Circuit breakers automatiques | P0 | ⏳ |
| Pre-trade checks (size, correlation, news event) | P0 | ⏳ |
| Daily loss limit + strategy auto-disable | P1 | ⏳ |
| Black swan stress testing (crash 2008, COVID) | P1 | ⏳ |
| Cooling period + capital reduction logic | P1 | ⏳ |
| Tail risk hedge simulation | P2 | ⏳ |

---

## Sprint 10 🖥️ — Trading Dashboard & Market Scanner

**Objectif :** Dashboard temps réel, scanner multi-marchés, alertes, companion mobile

> Voir `docs/VISION.md` — Module 8: Trading Dashboard

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Live dashboard (positions, P&L, equity curve) | P0 | ⏳ |
| Risk meters (drawdown, VaR, exposure) | P0 | ⏳ |
| Multi-instrument market scanner | P1 | ⏳ |
| Pattern/setup detection (scanner alerts) | P1 | ⏳ |
| Alert system (Telegram, audio, SMS) | P1 | ⏳ |
| Economic calendar countdown + blackout periods | P1 | ⏳ |
| Mobile companion (P&L condensé, notifications push) | P2 | ⏳ |
| Watchlist management | P1 | ⏳ |

---

## Sprint 11 🤖 — AI/ML Integration

**Objectif :** Market regime detection, pattern recognition, feature engineering, ML models

> Voir `docs/VISION.md` — Module 10: AI & Machine Learning

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Market regime classifier (Random Forest) | P0 | ⏳ |
| Feature engineering pipeline | P0 | ⏳ |
| Candlestick pattern detector (doji, engulfing, etc.) | P1 | ⏳ |
| Chart pattern detector (H&S, double top/bottom) | P1 | ⏳ |
| Support/resistance detection auto | P1 | ⏳ |
| ML model lifecycle (train → validate → deploy) | P1 | ⏳ |
| Feature importance analysis | P2 | ⏳ |
| Reinforcement learning for execution | P2 | ⏳ |
| ONNX Runtime / TensorFlow Java integration | P1 | ⏳ |

---

## Sprint 12 📰 — News & Sentiment Pipeline

**Objectif :** Parsing de news, sentiment NLP, market impact scoring, calendrier économique

> Voir `docs/VISION.md` — Module 1.4: News & Sentiment

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Economic calendar multi-source (ForexFactory, Investing.com) | P0 | ⏳ |
| RSS/News feed parser (Bloomberg, Reuters) | P1 | ⏳ |
| NLP sentiment scoring (news → bull/bear score) | P1 | ⏳ |
| Key phrase extraction + keyword matching | P1 | ⏳ |
| Market impact estimation (prix avant/après) | P2 | ⏳ |
| Trading blackout periods (automatique avant news) | P1 | ⏳ |
| Inter-market correlation feed (SPY, DXY, VIX) | P2 | ⏳ |

---

## Sprint 13 🌍 — Enterprise & International Quality

**Objectif :** Qualité production internationale — tests, doc, sécurité, performance

> Voir `docs/VISION.md` — Modules 5, 9, et Philosophie Design

| Tâche | Priorité | Statut |
|-------|:--------:|:------:|
| Test coverage > 90% (JUnit 5 paramétrés) | P0 | ⏳ |
| Documentation complète (FR + EN) | P0 | ⏳ |
| Security audit + API key encryption (AES-256) | P0 | ⏳ |
| CI/CD pipeline (GitHub Actions → build → test → deploy) | P1 | ⏳ |
| Performance benchmarks + optimisation | P1 | ⏳ |
| Stress testing (flash crash, data gap, broker disconnect) | P1 | ⏳ |
| Disaster recovery plan + backup strategy | P1 | ⏳ |
| Docker containerization + compose | P1 | ⏳ |
| Structured logging + Prometheus/Grafana monitoring | P1 | ⏳ |
| Compliance reporting (capital gains) | P2 | ⏳ |
| Audit log de toutes les opérations | P1 | ⏳ |

---

## Légende

| Symbole | Signification |
|:-------:|---------------|
| P0 | Bloquant — doit être fait |
| P1 | Important — devrait être fait |
| P2 | Nice to have — si le temps le permet |
| ✅ | Terminé |
| 🚧 | En cours / À faire |
| ⏳ | Planifié (pas commencé) |
