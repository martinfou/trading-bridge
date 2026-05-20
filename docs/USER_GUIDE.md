# Trading Bridge — User Guide

Stratégies générées, backtest, validation, et promotion vers le live.
Tout passe par la même interface `DataProxy` — **backtest et live utilisent le même pipeline**.

---

## 📦 Architecture

```
                     ┌──────────────────────────────┐
                     │       DataProxy (interface)    │
                     │  List<Bar> getCandles(...)     │
                     └──────────┬───────────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                   │
   LocalDataProxy     OandaDataProxy                │
   (.bars files)      (OANDA REST API)              │
              │                 │                    │
              │                 │                    │
              ▼                 ▼                    │
   ┌────────────────────────────────────┐           │
   │        BacktestEngine              │◄──────────┘
   │  strategy.onBar(bar)              │
   │  strategy.getPendingOrders()      │
   │  checkSL/TP, update equity        │
   └────────────────────────────────────┘
              │
              ▼
   ┌────────────────────────────────────┐
   │        RunBacktest                 │
   │  --proxy local  → .bars (backtest)│
   │  --proxy oanda  → OANDA (live)    │
   │  --strategy     → classe .java    │
   │  --json --html  → export          │
   └────────────────────────────────────┘
```

### Modules

| Module | Rôle | Dépendances |
|---|---|---|
| `trading-core` | Modèles (Bar, Order, Strategy, Position), DataLoader, TimeConventions | — |
| `trading-data` | DataProxy, LocalDataProxy, OandaDataProxy, OandaPriceClient, BarStore | core |
| `trading-backtest` | BacktestEngine, BacktestResult, MonteCarlo, WalkForward, rapports | core |
| `trading-genetics` | BatchStrategyRunner, StrategyBuilder, StrategyCodeGen, RankingDashboard | backtest, core |
| `trading-strategies` | Stratégies prêtes (NewsTrading, AutoTrader, converties JForex) | core |
| `trading-examples` | RunBacktest, SmaCrossoverStrategy, démos | backtest, data, core |
| `trading-parser` | Parseur XML StrategyQuant/JForex → Java, JForexConverter | core |

---

## 🎯 1. Générer des Stratégies

Deux modes : **quantité fixe** ou **itératif jusqu'à critères**.

### 1.1 Quantité fixe

```bash
./scripts/batch-gen.sh --count 500 --types all --bars 250 --capital 100000
```

Génère 500 stratégies, quick-test sur 250 bars, rank, valide top 10%, exporte top 20.

```bash
# Types spécifiques
./scripts/batch-gen.sh --count 200 --types trend      # trend only
./scripts/batch-gen.sh --count 200 --types meanrev     # mean reversion
./scripts/batch-gen.sh --count 200 --types breakout    # breakout
./scripts/batch-gen.sh --count 200 --types momentum     # momentum

# Avec données réelles (CSV) au lieu de synthétique
./scripts/batch-gen.sh --count 100 --bars 500 --data data/historical/EUR_USD_H1.csv
```

### 1.2 Mode sélection (itératif jusqu'à critères)

```bash
# Trouver 10 stratégies avec Sharpe ≥ 1.5, max 5000 tentatives
./scripts/batch-gen.sh --target 10 --min-sharpe 1.5 --max-attempts 5000

# Critères multiples
./scripts/batch-gen.sh \
    --target 5 \
    --min-sharpe 1.0 \
    --min-pf 1.8 \
    --max-dd 25 \
    --min-win-rate 42

# Avec données réelles
./scripts/batch-gen.sh \
    --target 10 \
    --min-sharpe 1.2 \
    --min-pf 1.5 \
    --data data/historical/EUR_USD_H1.csv
```

### 1.3 Output

```
batch-results/
├── ranking.html              ← Interactive Chart.js dashboard
├── ranking.json              ← Données brutes
├── summary.txt               ← Résumé textuel
└── strategies/               ← Top 20 : classes Java compilables
    ├── Ranked1_EMA_Trend.java
    ├── Ranked2_MACD_Momentum.java
    └── ...
```

---

## 📊 2. Backtester une Stratégie

### 2.1 Mode proxy local (recommandé — même pipeline que le live)

```bash
# Via .bars binaires (50× plus rapide que CSV)
RunBacktest --proxy local \
    --strategy com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted \
    --json --html \
    GBP/JPY H1 5000 1000
```

Ce que fait cette commande :

1. `LocalDataProxy.getCandles("GBP_JPY", "H1", 5000)` → lit `data/historical/bars/GBP_JPY_H1.bars`
2. Charge la stratégie par son nom de classe
3. `BacktestEngine.run()` → itère barre par barre
4. Détection auto du taux JPY → `withQuoteToUsdRate(95.0)`
5. Export JSON + HTML dans `_bmad-output/implementation-artifacts/backtest-reports/`

**Architecture :** exactement le même appel `DataProxy.getCandles()` que le live runner. La seule différence est l'implémentation derrière l'interface.

### 2.2 Mode proxy OANDA (données fraîches depuis l'API)

```bash
# Fetch direct depuis OANDA — mêmes données que le live
export OANDA_API_KEY="..."
export OANDA_ACCOUNT_ID="101-002-4729622-008"

RunBacktest --proxy oanda \
    --strategy com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted \
    --json \
    GBP/JPY H1 5000 1000
```

Utile pour vérifier qu'une stratégie fonctionne sur les données OANDA les plus récentes avant de la promouvoir en live.

### 2.3 Mode legacy (CSV direct)

```bash
# Direct CSV — pas de proxy, pas recommandé pour prod
RunBacktest --json data/EUR_USD_H1.csv EURUSD 10000
```

Un warning s'affiche : utilise `--proxy local` à la place.

### Options communes

| Flag | Description |
|---|---|
| `--strategy <class>` | Classe Java fully-qualified (ex: `com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted`) |
| `--json` | Export JSON pour le dashboard |
| `--html` | Export HTML avec graphiques |
| `--capital <amount>` | Capital initial (défaut: 10000) |
| `--quote-rate <rate>` | Force le taux de conversion quote→USD (ex: 95 pour JPY) |
| `--data-dir <path>` | Répertoire des `.bars` (défaut: `data/historical/bars`) |
| `--granularity <tf>` | Timeframe: M1, M5, M15, M30, H1, H4, D, W |
| `--output-dir <dir>` | Répertoire d'export des rapports |

---

## 💾 3. Données : CSV → .bars binaire

Le format `.bars` est 50× plus rapide que le CSV (memory-mapped, zero-copy).

### Convertir un dossier de CSV

```bash
BarStore --convert data/historical/ data/historical/bars/
```

### Télécharger depuis OANDA (Python)

```bash
python3 scripts/oanda-downloader.py --symbol EUR_USD --year 2025 --count 5000
```

Produit `data/historical/EUR_USD_H1.csv` — convertir ensuite en `.bars`.

### Format CSV supporté (auto-détecté)

**Dukascopy :**
```
timestamp,open,high,low,close[,volume]
1717200000000,1.08200,1.08350,1.08150,1.08275,1000
```

**StrategyQuant :**
```
Date,Time,Open,High,Low,Close,Volume
2025.01.01,00:00,1.08000,1.08100,1.07950,1.08050,1000
```

### Arborescence des données

```
data/historical/
├── EUR_USD_H1.csv              ← CSV brut
├── AUD_USD_H1.csv
├── GBP_JPY_H1.csv
└── bars/                        ← .bars binaires (convertis)
    ├── EUR_USD_H1.bars
    ├── AUD_USD_H1.bars
    └── GBP_JPY_H1.bars
```

---

## 🔬 4. Validation Avancée

### Backtest simple (RunBacktest)

```bash
RunBacktest --proxy local GBP/JPY H1 5000 1000
```

Output :
```
Proxy: local ← data/historical/bars
Data: GBP_JPY H1 — 3312 bars available, requesting 5000
Received 3312 candles via proxy
Range: 2025-01-01T00:00:00Z  →  2026-05-19T23:00:00Z
Strategy: SMA_20_50

=== Backtest Result ===
Trades:        42
Win Rate:      57.1%
Net Profit:    $6956.42
Sharpe:        1.48
Profit Fac:    1.92
Max DD:        12.3%
```

### Stratégies converties (JForex → Java)

Les stratégies provenant de JForex/StrategyQuant sont dans `trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/`.

```bash
# Backtester une stratégie convertie
RunBacktest --proxy local \
    --strategy com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted \
    --json --html \
    GBP/JPY H1 5000 1000
```

Elles implémentent la même interface `Strategy` que les stratégies natives — tout passe par le même `BacktestEngine`.

---

## 🔄 5. Scripts Disponibles

```bash
scripts/
├── batch-gen.sh           # Génération de stratégies (batch)
├── bar-store.sh           # Conversion CSV → .bars (si créé)
├── test-all.sh            # Suite de tests
│   ├── smoke              # Compilation + tests rapides
│   ├── full               # Tous les tests
│   ├── genetic            # Genetic Engine
│   ├── monte-carlo        # Monte Carlo simulation
│   └── walk-forward       # Walk-Forward optimization
├── oanda-downloader.py    # Téléchargement données OANDA
├── run-live.sh            # Live trading (OANDA practice)
├── setup-live-service.sh  # Installation systemd
├── cron-promote.sh        # Promotion automatique (paper → live)
├── deploy.sh              # Déploiement multi-machine
├── dashboard-bridge.sh    # Bridge vers trading-dashboard Laravel
├── export-strategy.sh     # Export stratégie vers format déployable
├── convert-jforex.sh      # Conversion JForex/XML → Java
├── backtest-jforex.sh     # Legacy (obsolète)
├── download-data.sh       # Téléchargement données
├── run-strategy.sh        # Exécution stratégie avec PID
└── README.md              # Documentation déploiement
```

---

## 🧪 6. Tests

```bash
# Smoke test (rapide)
./scripts/test-all.sh smoke

# Tests complets
./scripts/test-all.sh full

# Module spécifique
./scripts/test-all.sh genetic        # Genetic Engine
./scripts/test-all.sh monte-carlo    # Monte Carlo
./scripts/test-all.sh walk-forward   # Walk-Forward
./scripts/test-all.sh report         # Rapports HTML

# Maven unit tests
mvn test
mvn test -pl trading-backtest -Dtest=BacktestEngineTest
```

---

## ⚙️ 7. Configuration

### Variables d'environnement

```bash
# OANDA API (pour proxy oanda et live trading)
export OANDA_API_KEY="..."          # Depuis trading-dashboard/.env
export OANDA_ACCOUNT_ID="101-002-4729622-008"
```

Source rapide :

```bash
source <(grep -E '^OANDA_' ~/projects/trading-dashboard/.env)
```

### Build

```bash
mvn clean install -DskipTests   # Build complet
mvn compile                     # Compilation rapide
mvn compile -pl trading-core    # Module spécifique
```

---

## 🛣️ 8. Workflow Complet

```
                      batch-gen.sh
                          │
                    ┌─────▼──────┐
                    │ Generate N  │
                    │ strategies  │
                    └─────┬──────┘
                          │
                    ┌─────▼──────┐
                    │ Quick       │
                    │ Screen      │
                    └─────┬──────┘
                          │
                    ┌─────▼──────┐
                    │ Rank +     │
                    │ Validate   │
                    └─────┬──────┘
                          │
                    ┌─────▼──────┐
                    │ Export Top  │
                    │ 20 Java    │
                    └─────┬──────┘
                          │
              ┌───────────┼───────────┐
              ▼                       ▼
   RunBacktest --proxy local   RunBacktest --proxy oanda
   (backtest sur .bars)         (backtest sur données live)
              │                       │
              └───────────┬───────────┘
                          ▼
                Comparaison des métriques
                          │
                    ┌─────▼──────┐
                    │ Résultats   │
                    │ cohérents ? │
                    └─────┬──────┘
                          │ oui
                    ┌─────▼──────┐
                    │ promote →  │
                    │ paper      │
                    └─────┬──────┘
                          │
                    ┌─────▼──────┐
                    │ cron-promote│
                    │ → live     │
                    └────────────┘
```

## 📘 Résumé des commandes

```bash
# 1. Générer des stratégies
./scripts/batch-gen.sh --count 500 --data data/historical/EUR_USD_H1.csv

# 2. Convertir les données en .bars (50× plus rapide)
java -cp ... com.martinfou.trading.data.BarStore --convert data/historical/ data/historical/bars/

# 3. Backtester via proxy local (même pipeline que le live)
RunBacktest --proxy local --strategy com...Top1_EMA_Trend \
    --json --html EUR/USD H1 5000 10000

# 4. Backtester via proxy OANDA (données fraîches)
RunBacktest --proxy oanda --strategy com...Top1_EMA_Trend \
    --json EUR/USD H1 5000 10000

# 5. Lancer en live
./scripts/run-live.sh API_KEY ACCOUNT_ID Top1_EMA_Trend H1 60

# 6. Promotion automatique
./scripts/deploy.sh promote Top1_EMA_Trend paper
```
