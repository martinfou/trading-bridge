# LLM System Context & Machine Rules: CME Futures Realism, IBKR Integration & Multi-Asset Execution

> **Target Audience**: Autonomous AI Agents, LLM Code Generators, Subagents, and automated QA systems.  
> **Module Scope**: `trading-core`, `trading-backtest`, `trading-data`, `trading-strategies`, `desktop/`  
> **Authority**: Absolute system invariants for Java 21 & Vue 3 multi-asset engine.

---

## 1. Domain Invariants & Golden Rules (CRITICAL)

When modifying, generating, or refactoring code in this repository, you **MUST** strictly adhere to the following invariants:

### Invariant 1: Discrete Tick Quantization
* **Rule**: Electronic futures prices (MES, MNQ, M2K, MYM, ES, NQ) **CANNOT** exist at fractional sub-tick decimals (e.g. `5012.33` is invalid on MES).
* **MES Minimum Tick**: `0.25` index points.
* **MES Multiplier & Tick Value**: Multiplier = `$5.00` per point; Tick Value = `0.25 * $5.00 = $1.25`.
* **Quantization Formula**: `quantizedPrice = Math.round(price / minTick) * minTick` (or `Math.round(price * 4.0) / 4.0` for 0.25).
* **Enforcement**: Always pass prices through `FuturesRegistry.quantizePrice(symbol, price)` before order emission or fill simulation.

### Invariant 2: Conservative Limit Execution (`FillMode`)
* **Enum**: `com.martinfou.trading.backtest.FillMode` (`TOUCH`, `TRADE_THROUGH`).
* **Requirement**: For strategy qualification, parameter validation, and Walk-Forward Analysis (WFA), default or enforce `FillMode.TRADE_THROUGH`.
* **Trade-Through Logic**:
  - `BUY LIMIT`: Filled if and only if `bar.low() < order.price()`.
  - `SELL LIMIT`: Filled if and only if `bar.high() > order.price()`.
  - `TOUCH` mode (`bar.low() <= order.price()`) is strictly reserved for legacy optimistic testing.

### Invariant 3: CME Globex Session Hours & Halts
* **Calendar Engine**: `com.martinfou.trading.core.CmeGlobexMarketCalendar`.
* **Timezone Reference**: `America/New_York` (US Eastern Time with automatic EST/EDT transitions).
* **Weekly Cycle**:
  - Open: Sunday 18:00 ET (23:00 UTC during EDT, 22:00 UTC during EST).
  - Close: Friday 17:00 ET.
  - Daily Maintenance Halt: Monday–Thursday 17:00–18:00 ET (Bars during this window must return `isTradingBar(bar) == false`).
  - Daily Pause: 16:15–16:30 ET.
  - Saturday: Completely Closed.
* **Session Classification**:
  - `RTH` (Regular Trading Hours): Monday–Friday `09:30 - 16:00 ET`.
  - `ETH` (Extended Trading Hours): `18:00 - 09:30 ET` and `16:00 - 17:00 ET`.

### Invariant 4: Regulatory & Margin Classification (PDT Exemption)
* **FINRA Rule 4210 (PDT)** applies **ONLY** to US Equities (`AssetClass.EQUITIES`).
* In `MarginTracker.java`, when `AssetValuationRegistry.resolve(symbol).assetClass() == AssetClass.FUTURES` or `AssetClass.FOREX`, bypass the 3-day-trades-per-5-days restriction regardless of whether account equity is below $25,000.

### Invariant 5: IBKR Timezone Normalization
* In `IbkrHistoricalDataLoader.java`, raw IBKR CSV timestamps (e.g. `20240315 09:30:00`) must be parsed with `ZoneId.of("America/New_York")` and converted to `java.time.Instant` in UTC (`Instant.atZone(ZoneId.of("UTC"))`).
* Never use local system timezone `ZoneId.systemDefault()` or naive string truncation.

### Invariant 6: UI Date Formatting Standard
* All timestamps displayed across the Vue 3 frontend (`TradeTable.vue`, `BacktestHistoryView.vue`, `CompareView.vue`, `ResultsView.vue`, `WfaView.vue`) must use the standard format: `yyyy-MM-dd HH:mm`.

---

## 2. Key Type Contracts & API Signatures

```java
// com.martinfou.trading.core.FuturesContract
public record FuturesContract(
    String symbol,
    String description,
    String exchange,
    String currency,
    double multiplier,
    double minTick,
    double tickValue,
    double initialMargin,
    double maintenanceMargin
) {
    public double quantizePrice(double price);
    public boolean isValidTick(double price);
}

// com.martinfou.trading.core.FuturesRegistry
public final class FuturesRegistry {
    public static Optional<FuturesContract> find(String symbol);
    public static FuturesContract get(String symbol);
    public static double quantizePrice(String symbol, double price);
    public static boolean isValidTick(String symbol, double price);
}

// com.martinfou.trading.core.CmeGlobexMarketCalendar
public final class CmeGlobexMarketCalendar {
    public static final ZoneId EASTERN_ZONE = ZoneId.of("America/New_York");
    public enum SessionType { RTH, ETH, CLOSED }
    public static boolean isTradingTime(Instant instant);
    public static boolean isTradingBar(Bar bar);
    public static SessionType classifySession(Instant instant);
    public static boolean isRth(Instant instant);
    public static boolean isEth(Instant instant);
}

// com.martinfou.trading.backtest.FillMode
public enum FillMode { TOUCH, TRADE_THROUGH }

// com.martinfou.trading.strategies.AtrFuturesPositionSizer
public final class AtrFuturesPositionSizer {
    public record SizingResult(
        int contracts,
        double stopDistancePoints,
        double riskAmountUsd,
        double requiredInitialMarginUsd,
        boolean marginFeasible
    ) {}
    public static double calculateStopDistance(String symbol, double atrValue, double atrMultiple);
    public static SizingResult calculatePositionSize(String symbol, double accountEquity, double riskPercentage, double stopDistancePoints);
}
```

---

## 3. Anti-Patterns & Common LLM Pitfalls

| Anti-Pattern | Why It Breaks The System | Required Correction |
| :--- | :--- | :--- |
| `order.withPrice(5012.33)` | Off-tick prices cause margin rejections and valuation mismatches on CME. | Use `FuturesRegistry.quantizePrice("MES", price)` -> `5012.25`. |
| `new BacktestEngine().withFillMode(FillMode.TOUCH)` in WFA / validation | Inflates win rate by assuming 100% queue priority at limit price touch. | Enforce `FillMode.TRADE_THROUGH`. |
| `Instant.parse(rawDate + "Z")` on IBKR files | Causes a 4 to 5 hour phase shift depending on EST/EDT daylight saving. | Parse with `LocalDateTime` + `ZoneId.of("America/New_York")` -> `.toInstant()`. |
| `contracts = (int)(risk / stop)` without integer floor | Sub-unit contract sizing (e.g. 0.35 MES) fails CME clearing. | Always integer contracts `int contracts = (int)Math.floor(...)` (min 1 if margin permits). |
| Applying PDT checks unconditionally in `MarginTracker` | Blocks valid sub-$25k futures micro accounts from trading daily strategies. | Check `assetClass == AssetClass.FUTURES` and bypass PDT restriction. |
| Hardcoding `7496` without fallback hints | Fails on IB Gateway instances (`4001` live, `4002` paper) vs TWS (`7496` live, `7497` paper). | Provide user hints for both TWS and IB Gateway port mappings. |

---

## 4. Subagent & Coding Directives

When authoring new trading strategies, data parsers, or UI views:
1. **Strategy Creation**: If designing a CME futures strategy, query `FuturesRegistry` for point valuations, use `AtrFuturesPositionSizer` for stop distance quantization, and set `requiresIntegerContracts = true`.
2. **Backtesting**: Ensure test fixtures invoke `BacktestEngine.withFillMode(FillMode.TRADE_THROUGH)` and specify realistic broker commissions ($0.62 per MES contract) and slippage ($0.25 = 1 tick).
3. **UI Components**: When binding date columns, always format using the app-standard `yyyy-MM-dd HH:mm` pattern.
