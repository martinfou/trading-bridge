# Guide de Conversion — JForex → Java Pur

> Comment migrer tes stratégies StrategyQuant de JForex vers le Trading Bridge

---

## 1. Vue d'ensemble

### JForex (Dukascopy) → Trading Bridge (Java)

| Concept JForex | Trading Bridge | Exemple |
|----------------|----------------|---------|
| `IStrategy` | `Strategy` (interface) | `class MyStrat implements Strategy` |
| `onBar(Instrument, Period, IBar)` | `onBar(Bar bar)` | `strategy.onBar(bar)` |
| `onTick(Instrument, ITick)` | `onTick(bid, ask, volume)` | `strategy.onTick(1.08, 1.081, 100)` |
| `engine.getBars()` | `DataLoader.loadCSV(path, symbol)` | Déjà chargé avant le run |
| `IOrder` | `Order` | `new Order(EURUSD, BUY, MARKET, 0.01, 1.08)` |
| `order.getState()` | `order.status()` | `FILLED, PENDING, CANCELLED` |
| `IEngine` | `BacktestEngine` / `LiveEngine` | `new BacktestEngine(strat, bars, capital)` |
| `IUserInterface` | `BacktestResult.printSummary()` | Résultat console/HTML |

## 2. Conversion pas à pas

### Étape 1: Créer la classe de stratégie

**JForex:**
```java
import com.dukascopy.api.*;

public class MyStrategy implements IStrategy {
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    public void onStart(IContext context) {
        this.engine = context.getEngine();
        this.history = context.getHistory();
        this.indicators = context.getIndicators();
    }
}
```

**Trading Bridge:**
```java
import com.martinfou.trading.core.*;
import java.util.*;

public class MyStrategy implements Strategy {
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean inPosition = false;

    @Override
    public String name() { return "Ma Strategie"; }
}
```

### Étape 2: Charger les données

**JForex:**
```java
List<IBar> bars = history.getBars(Instrument.EURUSD, Period.ONE_HOUR, 
    OfferSide.BID, loadTime, 500);
```

**Trading Bridge:**
```java
List<Bar> bars = DataLoader.loadStrategyQuantCSV(
    Paths.get("EURUSD_H1_2024.csv"), "EURUSD");
```

### Étape 3: Indicateurs techniques

**JForex — SMA:**
```java
double[] sma20 = indicators.sma(instrument, period, 
    OfferSide.BID, IIndicators.AppliedPrice.CLOSE, 20, 0);
```

**Trading Bridge — SMA (manuel):**
```java
private double sma(List<Bar> bars, int period) {
    int size = bars.size();
    double sum = 0;
    for (int i = size - period; i < size; i++)
        sum += bars.get(i).close();
    return sum / period;
}
```

### Étape 4: Obtenir les ordres

**JForex — Ordre:**
```java
IOrder order = engine.submitOrder("order1", instrument, 
    IEngine.OrderCommand.BUY, 0.01, 0, 0, 1.07, 1.09);
// submitOrder(id, instrument, command, amount, price, slippage, stopLoss, takeProfit)
```

**Trading Bridge — Ordre:**
```java
pending.add(new Order("EURUSD", Order.Side.BUY, 
    Order.Type.MARKET, 0.01, bar.close())
    .withStopLoss(1.0700)
    .withTakeProfit(1.0900));
```

### Étape 5: Conditions d'entrée

**JForex:**
```java
if (sma20[0] > sma50[0] && sma20[1] <= sma50[1]) {
    engine.submitOrder("buy", instrument, 
        IEngine.OrderCommand.BUY, 0.01);
}
```

**Trading Bridge:**
```java
double currentSma20 = sma(history, 20);
double prevSma20 = smaPrev(history, 20);
double currentSma50 = sma(history, 50);
double prevSma50 = smaPrev(history, 50);

if (prevSma20 <= prevSma50 && currentSma20 > currentSma50 && !inPosition) {
    pending.add(new Order("EURUSD", Order.Side.BUY, 
        Order.Type.MARKET, 0.01, bar.close()));
    inPosition = true;
}
```

### Étape 6: Backtester

**JForex:** Pas de backtest intégré (besoin de JForex platform)

**Trading Bridge:**
```java
BacktestEngine engine = new BacktestEngine(strategy, bars, 10000);
BacktestResult result = engine.run();
result.printSummary();
```

## 3. Exemple complet

### Stratégie SMA Crossover complète

```java
package com.martinfou.trading.strategies;

import com.martinfou.trading.core.*;
import java.util.*;

public class SMACrossoverStrategy implements Strategy {
    private final int fastPeriod, slowPeriod;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean inPosition = false;

    public SMACrossoverStrategy(String symbol, int fast, int slow) {
        this.symbol = symbol;
        this.fastPeriod = fast;
        this.slowPeriod = slow;
    }

    @Override
    public String name() {
        return String.format("SMA_%d_%d_%s", fastPeriod, slowPeriod, symbol);
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < slowPeriod + 1) return;

        double fastSma = calculateSMA(fastPeriod);
        double slowSma = calculateSMA(slowPeriod);
        double prevFast = calculatePrevSMA(fastPeriod);
        double prevSlow = calculatePrevSMA(slowPeriod);

        // ENTRY: Golden cross
        if (prevFast <= prevSlow && fastSma > slowSma && !inPosition) {
            pending.add(new Order(symbol, Order.Side.BUY, 
                Order.Type.MARKET, 1000, bar.close()));
            inPosition = true;
        }
        // EXIT: Death cross
        else if (prevFast >= prevSlow && fastSma < slowSma && inPosition) {
            pending.add(new Order(symbol, Order.Side.SELL, 
                Order.Type.MARKET, 1000, bar.close()));
            inPosition = false;
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Trading Bridge: onTick not used in backtest
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inPosition = false;
    }

    private double calculateSMA(int period) {
        int size = history.size();
        double sum = 0;
        for (int i = size - period; i < size; i++)
            sum += history.get(i).close();
        return sum / period;
    }

    private double calculatePrevSMA(int period) {
        int size = history.size() - 1;
        double sum = 0;
        for (int i = size - period; i < size; i++)
            sum += history.get(i).close();
        return sum / period;
    }
}
```

## 4. Pièges courants

| JForex | Trading Bridge | Attention |
|--------|----------------|-----------|
| `Instrument.EURUSD` | `"EURUSD"` | String, pas d'enum |
| `IEngine.OrderCommand.BUY` | `Order.Side.BUY` | Side ≠ Type |
| `offerSide.BID` | Utilise `bar.close()` | Pas de bid/ask en backtest |
| `Period.ONE_HOUR` | Pas de concept de période | Les bars arrivent déjà formées |
| `indicators.sma()` | Calcul manuel ou utilitaire | À implémenter dans trading-core |
| `order.getAmount()` | `order.quantity()` | Renommé pour clarté |
| Gestion du temps | `bar.timestamp()` | LocalDateTime |

## 5. Validation de la conversion

Pour valider qu'une stratégie convertie produit les mêmes résultats :

1. **Backtest JForex** → Note les trades, P&L, drawdown
2. **Exporter les données CSV** depuis StrategyQuant
3. **Backtest Trading Bridge** avec les mêmes données
4. **Comparer** trades, entrées, sorties, P&L

La tolérance devrait être de **0.1%** sur le P&L total (arrondis de prix).
