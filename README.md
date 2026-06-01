# Trading Bridge

> Pont entre backtesting et exécution live (OANDA Practice)
> Projet personnel — Martin Fournier

## Architecture

```
trading-core/           Domain models, Strategy interface, Indicators
trading-backtest/       BacktestEngine, RunContext, reports
trading-data/           HistoricalDataLoader, OANDA price client
trading-strategies/     45+ creative + imported strategies
trading-broker/         OANDA / IBKR broker connectors
trading-runtime/        HTTP control plane, event store
trading-examples/       CLI RunBacktest, golden tests
```

## Déploiement

```bash
docker compose up -d comp-momentum month-week nfp-week
```

## Problèmes connus et correctifs

### 🛑 1. HEDGING — Close orders créent des hedges au lieu de fermer (JUIN 2026)

**Problème :** Le compte OANDA a `hedgingEnabled: true`. Quand une stratégie appelle
`closePosition()`, elle envoie un ordre MARKET en sens opposé. Sans `positionFill:
REDUCE_ONLY`, OANDA interprète ça comme une **nouvelle position hedge** au lieu de
fermer l'existante. Résultat : des dizaines de trades long + short simultanés,
marge consommée à 99%, P&L qui s'annule.

**Fix ✅** (commit `dde8812`, June 1 2026) :
- `Order.java` : nouveau champ `closeOnly` + fluent `.closeOnly()`
- `BacktestEngine` : un ordre `closeOnly` ferme la position sans en créer de nouvelle
- `OandaExecutor` : support `positionFill: "REDUCE_ONLY"` via overload
- `LiveStrategyRunner` : utilisation de `REDUCE_ONLY` pour les close orders
- **45 stratégies** : `.closeOnly()` chaîné sur chaque `closePosition()`

### 🛑 2. CRASH RECOVERY — État non persisté après restart Docker (MAI 2026)

**Problème :** `LiveStrategyRunner.resumeState()` restaure les trades OANDA ouverts,
mais les flags `inTrade`, `tradesToday`, `lastTradeDay` des stratégies n'étaient pas
sauvegardés. Après un restart Docker, chaque stratégie pense n'avoir **aucun trade
ouvert** et génère une entrée à chaque nouvelle barre → **43 trades en 7 heures,
99.6% de marge utilisée**.

**Fix ✅** (May 29-30 2026) :
- Toutes les stratégies doivent implémenter 5 getters (`getTradesToday()`,
  `getLastTradeDay()`, `isInTrade()`, `getTradeDirection()`, `getCooldownBars()`)
  + `restoreState()`.
- `LiveStrategyRunner.saveStateNow()` appelle ces getters via reflection.
- Si une stratégie ne les a pas, le save/restore est silencieusement ignoré.

### 🛑 3. SL/TP NON CHAÎNÉS — Ordres sans stops sur OANDA (MAI 2026)

**Problème :** Les stratégies calculaient `entryStop` et `entryTarget` mais créaient
l'Order sans `.withStopLoss()`. `executeTrade()` voyait `stopLoss() == 0` et
n'attachait aucun SL/TP côté OANDA → trades ouverts indéfiniment.

**Fix ✅** (May 29 2026) :
```java
pending.add(new Order(..., price)
    .withStopLoss(entryStop).withTakeProfit(entryTarget));
```
Toutes les nouvelles stratégies (Juin 2026) incluent cette pratique.

### 🛑 4. EQUITY CURVE PLATEAU — Flip positions dans le backtest (MAI 2026)

**Problème :** `closePosition()` ajoute un MARKET order opposé.
`BacktestEngine.processOrders()` interprétait ça comme une nouvelle entrée.
~50% des trades backtestés étaient des positions flip indésirables qui
annulaient le P&L réel.

**Fix ✅** (May 30 2026) :
Trois branches dans `processOrders()` : **même sens** → ajouter à la position ;
**sens opposé** → fermer seulement (sans ré-ouvrir) ; **pas de position** → ouvrir.

### 🛑 5. JPAIRES JPY — Précision des stop orders (🔴 NON RÉSOLU)

**Problème :** `Order.setStopLossOnFill()` utilise `String.format("%.5f", price)`.
Les paires JPY (USD/JPY, GBP/JPY, EUR/JPY) ont une précision à 3 décimales max.
OANDA rejette les stops avec 5 décimales → **0 trades JPY exécutés** depuis le
déploiement.

**Fix 🔴 Non appliqué :**
```java
if (instrument.endsWith("JPY")) 
    // utiliser scale(3) au lieu de %.5f
```
Voir `LiveStrategyRunner` et `OandaExecutor` pour les endroits à corriger.

### ⚠️ 6. SHARPE NÉGATIF SUR 20 ANS — Anomalie connue

**Problème :** Sur des datasets H1 de 20+ ans (~693k barres), le Sharpe peut être
négatif même avec PF > 3 et WR > 67%. C'est un artefact du calcul de Sharpe sur
des périodes trop longues.

**Workaround :** Utiliser PF + WR + DD comme filtres principaux,
pas Sharpe seul. Si PF ≥ 2.5, WR ≥ 60%, DD < 15% sur ≥3 assets, la
stratégie est qualifiée via le `primaryFilterPf` override.

### ⚠️ 7. MAPPING STRATÉGIE → PAIRE — Hardcodé dans le runner

**Problème :** `LiveStrategyRunner.toOandaSymbol()` détermine la paire par
recherche de sous-chaîne dans le nom de la stratégie. Pas de configuration
externe. Ajouter une stratégie = modifier le code Java.

**Fix envisagé :** Table de mapping dans `live-config.json` (Docker volume
`/app/config/`).

### ⚠️ 8. BACKTEST QUALIFICATION — 0/24 au seuil strict

**Résultats (Juin 2026) :** 3 nouvelles stratégies × 8 paires = 0/24 qualifié
au seuil Prop Shop (Sharpe ≥ 1.5 OOS).

**Meilleur résultat :** `CompositeMomentumRanking` sur **USD/JPY**
- IS : Sharpe=0.35, PF=1.80, DD=10.24%, 795 trades
- OOS : **Sharpe=1.00, PF=2.24, DD=8.60%, 313 trades**
- PF amélioré de 24% en OOS (rare — signe de robustesse)

Déployé en paper trading le 1er Juin 2026.

## Stratégies actives (paper trading)

| Conteneur | Stratégie | Paire | Granularité |
|-----------|-----------|-------|:-----------:|
| `trading-comp-momentum` | CompositeMomentumRanking | USD/JPY | H1 |
| `trading-month-week` | MonthWeekPhase | USD/JPY | H1 |
| `trading-nfp-week` | NfpWeekShortEURUSD | EUR/USD | H1 (NFP week) |

## Build

```bash
docker compose build [service] && docker compose up -d [service]
```
