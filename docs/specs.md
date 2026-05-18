# Spécification Technique — Trading Bridge

> Version: 1.0.0 — 17 mai 2026
> Statut: Bmad Sprint 1 ✅

---

## 1. Architecture

### 1.1 Diagramme de séquence — Backtest

```
BacktestEngine          Strategy                DataLoader           Historique
     │                      │                       │                    │
     │     load(path)       │                       │                    │
     │──────────────────────┼───────────────────────┤──── CSV ───────────│
     │                      │                       │                    │
     │   List<Bar> bars     │                       │                    │
     │◄─────────────────────┼───────────────────────┤                    │
     │                      │                       │                    │
     │  run()               │                       │                    │
     │  for each bar ────┐  │                       │                    │
     │  ┌────────────────┘  │                       │                    │
     │  │ onBar(bar)        │                       │                    │
     │  │───────────────────┤                       │                    │
     │  │                   │   calcul indicateurs  │                    │
     │  │                   │   ────┐               │                    │
     │  │                   │   ┌───┘               │                    │
     │  │                   │   │ vérifier règles   │                    │
     │  │                   │   │ ────┐             │                    │
     │  │                   │   │ ┌───┘             │                    │
     │  │  getPendingOrders │   │ │                 │                    │
     │  │◄──────────────────┤   │ │                 │                    │
     │  │  processOrders()  │   │ │                 │                    │
     │  │  ────┐            │   │ │                 │                    │
     │  │  ┌───┘            │   │ │                 │                    │
     │  │  │ fill/not       │   │ │                 │                    │
     │  │  │ update equity  │   │ │                 │                    │
     │  │  │ track drawdown │   │ │                 │                    │
     │  └──│────────────────┘   │ │                 │                    │
     │      v                   │ │                 │                    │
     └──────┴───────────────────┴─┴─────────────────┴────────────────────┘
```

### 1.2 Flux exécution live

```
                   ┌──────────┐
                   │ Strategy │
                   │ (Java)   │
                   └────┬─────┘
                        │ onBar() / onTick()
                        v
              ┌─────────────────┐
              │  TradingEngine  │
              │  (ordonnanceur)  │
              └────────┬────────┘
                       │ ordres
          ┌────────────┼────────────┐
          v            v            v
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │ OANDA   │ │   IBKR   │ │ Backtest │
   │ Broker  │ │  Broker  │ │ Engine   │
   └──────────┘ └──────────┘ └──────────┘
```

## 2. Modèles de données

### 2.1 Bar (OHLCV)

| Champ | Type | Description |
|-------|------|-------------|
| symbol | String | Paire de devises (ex: EURUSD) |
| timestamp | LocalDateTime | Date/heure de la bougie |
| open | double | Prix d'ouverture |
| high | double | Plus haut |
| low | double | Plus bas |
| close | double | Prix de clôture |
| volume | long | Volume échangé |

### 2.2 Order

| Champ | Type | Description |
|-------|------|-------------|
| id | String (UUID) | Identifiant unique |
| symbol | String | Instrument |
| side | BUY / SELL | Sens |
| type | MARKET / LIMIT / STOP | Type d'ordre |
| quantity | double | Taille de la position |
| price | double | Prix limite/stop |
| stopLoss | double | Stop loss (optionnel) |
| takeProfit | double | Take profit (optionnel) |
| status | PENDING / FILLED / CANCELLED | Statut |

### 2.3 Position

| Champ | Type | Description |
|-------|------|-------------|
| symbol | String | Instrument |
| side | BUY / SELL | Sens |
| quantity | double | Taille |
| entryPrice | double | Prix d'entrée moyen |
| currentPnl(price) | double | P&L flottant |
| pnlPercent(price) | double | P&L en % |

### 2.4 BacktestResult

| Champ | Type | Description |
|-------|------|-------------|
| initialCapital | double | Capital de départ |
| finalEquity | double | Capital final |
| totalPnl | double | Profit/perte total |
| totalReturnPct | double | Rendement % |
| totalTrades | int | Nombre de trades |
| winRatePct | double | Taux de réussite % |
| maxDrawdownPct | double | Drawdown maximum % |
| equityCurve | List<Double> | Courbe d'équité |
| trades | List<Trade> | Tous les trades |

### 2.5 Conventions temporelles (fuseaux horaires)

> Statut : adopté — mai 2026  
> Objectif : une seule référence temporelle en interne, reproductible entre backtest, live et calendrier économique.

#### Principe

| Couche | Fuseau / type | Règle |
|--------|---------------|--------|
| **Système (canonique)** | **UTC** (`ZoneOffset.UTC`, `Instant`) | Tous les timestamps stockés, comparés et journalisés en UTC |
| **API OANDA** | UTC | Les réponses v20 sont en UTC ; convertir à l’import, ne pas tronquer en `LocalDateTime` naïf |
| **CSV / StrategyQuant** | Documenté à l’import | Déclarer le fuseau source dans le loader ; convertir → UTC avant `Bar` |
| **Calendrier économique** | UTC en stockage | À l’ingestion : convertir l’heure de publication (souvent locale pays ou US Eastern) → `Instant` UTC ; conserver `ZoneId` source si besoin d’affichage |
| **Affichage humain** | `America/Toronto` | Logs UI, alertes, console opérateur — conversion depuis UTC uniquement |
| **Interdit** | `LocalDateTime.now()` pour le trading | Utiliser `Instant.now(Clock)` avec horloge UTC injectée |

#### Format d’échange

- Fichiers, logs, API internes : ISO-8601 avec offset ou suffixe `Z` (ex. `2026-05-20T14:30:00Z`).
- Legacy / transition : `LocalDateTime` existant est traité comme **UTC implicite** jusqu’à migration vers `Instant` sur `Bar`, `Order`, `Trade`.

#### Migration progressive

1. Nouveau code : `Instant` aux frontières (OANDA, CSV, calendrier).
2. Refactor domaine : `Bar.timestamp()` → `Instant` (breaking — une epic dédiée).
3. `EconomicCalendar` : remplacer les constantes « heure murale » par des `Instant` UTC documentés.

#### Références

- Détail agent IA : `_bmad-output/project-context.md` (section Time)
- Conversion JForex : `docs/conversion-guide.md` (bar.timestamp)

## 3. Interface Strategy

```java
public interface Strategy {
    String name();
    void onBar(Bar bar);           // Appelé à chaque bougie
    void onTick(double bid,        // Appelé à chaque tick (live)
                double ask,
                long volume);
    List<Order> getPendingOrders(); // Ordres à exécuter
    void reset();                   // Reset pour backtest
}
```

## 4. Convertisseur XML → Java

### 4.1 Structure du XML StrategyQuant

Le format XML de StrategyQuant contient :

```xml
<StrategyQuant>
  <Strategy>
    <Name>MyStrategy</Name>
    <Symbol>EURUSD</Symbol>
    <Timeframe>H1</Timeframe>
    <Indicators>
      <Indicator type="SMA" period="20" field="Close"/>
      <Indicator type="RSI" period="14"/>
    </Indicators>
    <EntryRules>
      <Rule type="CROSSOVER">
        <Condition indicator1="SMA_20" operator="CROSSES_ABOVE" indicator2="SMA_50"/>
      </Rule>
    </EntryRules>
    <ExitRules>
      <Rule type="STOP_LOSS" value="50"/> <!-- pips -->
      <Rule type="TAKE_PROFIT" value="100"/>
    </ExitRules>
    <PositionSizing>
      <Method type="FIXED" value="0.01"/> <!-- lots -->
    </PositionSizing>
  </Strategy>
</StrategyQuant>
```

### 4.2 Règles de conversion

| XML StrategyQuant | Java cible |
|-------------------|------------|
| `<Indicator type="SMA" period="20"/>` | `SMA(20, Close)` → méthode utilitaire |
| `<Condition operator="CROSSES_ABOVE"/>` | `if (prevFast <= prevSlow && fast > slow)` |
| `<EntryRule type="ENTRY_LONG"/>` | `pending.add(new Order(BUY, MARKET, qty, price))` |
| `<ExitRule type="STOP_LOSS"/>` | `order.withStopLoss(entryPrice - stopPips)` |
| `<PositionSizing fixed="0.01"/>` | `double qty = calculateLotSize(0.01, capital)` |

### 4.3 Indicateurs supportés (Sprint 2)

- [x] SMA — Simple Moving Average
- [ ] EMA — Exponential Moving Average
- [ ] RSI — Relative Strength Index
- [ ] MACD — Moving Average Convergence Divergence
- [ ] Bollinger Bands
- [ ] ATR — Average True Range
- [ ] Stochastic
- [ ] ADX — Average Directional Index

## 5. Connecteurs Brokers

### 5.1 Interface Broker

```java
public interface Broker {
    void connect();
    void disconnect();
    boolean isConnected();
    MarketData subscribe(String symbol);
    OrderResult placeOrder(Order order);
    OrderResult cancelOrder(String orderId);
    List<Position> getPositions();
    double getAccountBalance();
}
```

### 5.2 OANDA v20

- API REST: `https://api-fxtrade.oanda.com/v3/`
- Support: forex uniquement
- Documentation: https://developer.oanda.com/

### 5.3 Interactive Brokers

- SDK: Java native (TWS API)
- Support: forex, actions, futures, options
- Documentation: https://www.interactivebrokers.com/api/

## 6. Métriques de performance

| Métrique | Calcul |
|----------|--------|
| Rendement total | `(finalEquity - initialCapital) / initialCapital * 100` |
| Win Rate | `gagnants / total * 100` |
| Max Drawdown | `max(peak - trough) / peak * 100` |
| Profit Factor | `gainsTotaux / pertesTotales` |
| Sharpe Ratio | `(rendementMoyen - tauxSansRisque) / ecartType` |
| Calmar Ratio | `rendementAnnuel / maxDrawdown` |
| Avg Trade | `pnlTotal / nbTrades` |

## 7. Fichiers de configuration

### 7.1 config.yaml (à venir)

```yaml
backtest:
  initial_capital: 10000
  commission: 0.00007  # 0.7 pip
  slippage: 0.00001    # 0.1 pip

broker:
  primary: oanda
  oanda:
    api_key: ${OANDA_API_KEY}
    account_id: ${OANDA_ACCOUNT}
    environment: practice  # practice | live
  ibkr:
    host: localhost
    port: 7497
    client_id: 1

strategy:
  path: ./strategies/MyStrategy.xml
  symbol: EURUSD
  timeframe: H1
  position_size: 0.01
```

## 8. Dépendances

| Librairie | Version | Usage |
|-----------|---------|-------|
| Java | 21+ | Runtime |
| Maven | 3.9+ | Build |
| JUnit 5 | 5.11 | Tests |
| Jackson | 2.17 | JSON/YAML |
| SLF4J | 2.0 | Logging |
| OANDA v20 Java | 3.0+ | Broker |
| IB API | 10.25+ | Broker |
