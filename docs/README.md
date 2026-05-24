# Trading Bridge

> Pont entre StrategyQuant (JForex) et OANDA / Interactive Brokers
> Projet Bmad — Martin Fournier

## 📋 Vue d'ensemble

Trading Bridge convertit les stratégies de trading générées par **StrategyQuant** (format JForex/XML) en **Java pur**, avec un moteur de **backtesting intégré** et des **connecteurs brokers** pour l'exécution live.

```mermaid
flowchart LR
    SQ[StrategyQuant] -->|XML/JForex| Parser
    Parser -->|Java Objects| Backtest[Moteur Backtest]
    Parser -->|Java Objects| Live[Execution Live]
    Live --> OANDA[OANDA v20]
    Live --> IBKR[Interactive Brokers]
    Data[Données Historiques SQ] --> Backtest
```

## 🏗️ Architecture

```
trading-bridge/
├── trading-core/          # Domain models + interfaces
│   ├── Bar.java           # Bougie OHLCV
│   ├── Order.java         # Ordre (market/limit/stop)
│   ├── Position.java      # Position ouverte
│   ├── Trade.java         # Trade fermé
│   ├── Strategy.java      # Interface stratégie
│   ├── StrategyConfig.java # Config parsing XML
│   └── DataLoader.java    # Import CSV/StrategyQuant
│
├── trading-parser/        # StrategyQuant XML → Java
│   ├── SqXmlParser.java   # Parseur XML principal
│   ├── Indicators.java    # Indicateurs techniques
│   └── Conditions.java    # Règles d'entrée/sortie
│
├── trading-backtest/      # Moteur de simulation
│   ├── BacktestEngine.java # Simulation OHLCV
│   ├── BacktestResult.java # Statistiques
│   └── ReportGenerator.java # Rapport HTML/CSV
│
├── trading-broker/        # Connecteurs bourse
│   ├── Broker.java        # Interface commune
│   ├── OandaBroker.java   # OANDA v20 REST API
│   ├── IbkrBroker.java    # Interactive Brokers API
│   └── MarketData.java    # Flux tick/bars temps réel
│
└── trading-examples/      # Stratégies de démonstration
    ├── SmaCrossover.java  # SMA Crossover
    └── RunBacktest.java   # Lanceur backtest
```

## 🧩 Modules

| Module | Description | Statut |
|--------|-------------|--------|
| core | Modèles, interfaces, utilitaires | ✅ Compile |
| backtest | Moteur de backtesting OHLCV | ✅ Compile |
| parser | Parseur XML StrategyQuant | 🚧 Sprint 2 |
| broker | OANDA + IBKR connectors | 🚧 Sprint 3 |
| examples | Stratégies d'exemple | ✅ Compile |

## 🎯 Bmad Sprints

### Sprint 1 — Fondation ✅
- [x] Structure monorepo Maven
- [x] Modèles de données (Bar, Order, Position, Trade)
- [x] Interface Strategy
- [x] BacktestEngine basique (market orders, SMA)
- [x] DataLoader CSV + StrategyQuant

### Sprint 2 — Parser XML
- [ ] Analyser le format XML StrategyQuant
- [ ] Extraire indicateurs (SMA, RSI, Bollinger, etc.)
- [ ] Extraire conditions d'entrée/sortie
- [ ] Générer code Java à partir du XML
- [ ] Valider avec une stratégie réelle

### Sprint 3 — Backtest avancé
- [ ] Support ordres LIMIT/STOP
- [ ] Comission et slippage
- [ ] Multi-timeframe
- [ ] Gestion de risque (taille position, stop loss)
- [ ] Rapport HTML avec graphiques
- [ ] Trades avec sorties (take profit, stop loss)

### Sprint 4 — Brokers
- [ ] Implémenter interface Broker
- [ ] OANDA v20 REST API (compte démo)
- [ ] Interactive Brokers API
- [ ] Market data en temps réel
- [ ] Exécution des ordres

### Sprint 5 — Production
- [ ] Monitoring et alertes
- [ ] Persistance des trades (SQLite)
- [ ] Dashboard web (Spring Boot)
- [ ] Tests unitaires et d'intégration

## 🔧 Configuration

### Build
```bash
mvn clean install
```

### Lister les stratégies disponibles
```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--list"
```

### Lancer backtest sample (SmaCrossover)
```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--sample"
```

### Lancer backtest avec données historiques
```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"

mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="Strategy_2_14_147_Adapted GBP_JPY 2012"
```

### Lancer backtest avec un fichier
```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout data/historical/bars/EUR_USD_H1_2012.bars"
```

> **Alias dépréciés :** `RunPropBacktest` et `RunSqBacktest` délèguent à `RunBacktest`.
> `RunPropBacktest --all` reste disponible pour exécuter toute la suite prop.

### Paper mode (stub)

Rejoue les barres historiques en mode `PAPER` — mêmes fills que le backtest, sans appel broker. Valide le pipeline avant le paper live (Epic 4).

```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012 --paper"

# JSONL avec mode PAPER dans les événements
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012 --paper --json"
```

## 📝 Format des données

### StrategyQuant CSV
```
Date,Time,Open,High,Low,Close,Volume
2024.01.01,00:00,1.08000,1.08100,1.07950,1.08050,1000
```

### Toute source OHLCV standard
```
DateTime,Open,High,Low,Close,Volume
2024-01-01T00:00:00,1.08000,1.08100,1.07950,1.08050,1000
```

## 📄 Licence
Usage personnel — Martin Fournier — 2026
