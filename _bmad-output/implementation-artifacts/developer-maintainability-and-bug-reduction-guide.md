# 💻 Developer Engineering Guide: Code Maintainability & Bug Reduction

**Author:** Amelia (Senior Software Engineer)  
**Date:** 2026-08-06  
**Git Branch:** `feature/architecture-maintainability-audit`  
**Focus:** Code-Level Refactoring, Test-Driven Quality, Defensive Programming, & Developer Ergonomics  

---

## 1. Executive Summary

As a Senior Software Engineer, my focus is translating high-level architecture into clean, testable, and robust code. While architectural design sets the system structure, day-to-day code quality determines how fast we can add features without introducing regression bugs.

This guide provides concrete, code-level practices to improve codebase maintainability and eliminate bug vectors across `trading-core`, `trading-runtime`, and `trading-strategies`:
1. **Test-First Discipline & Coverage**: Establishing unit test safety nets for indicator calculations, trade PnL math, and strategy state transitions.
2. **Defensive Programming & Zero-Division Safety**: Guarding math functions against `NaN`, infinite ratios, and null dereferences.
3. **Structured Logging & Exception Hierarchy**: Transitioning away from `System.out.println` and `e.printStackTrace()` to a typed exception hierarchy with SLF4J context markers.
4. **Refactoring Monolithic Methods**: Breaking down large execution methods into small, single-responsibility functions under 30 lines.
5. **Static Code Analysis & CI Checks**: Enforcing build-time checks (SpotBugs, Checkstyle) in Maven.

---

## 2. Core Code Quality & Bug Vectors Audit

### 2.1 Floating-Point Math & Division by Zero (`NaN` Guarding)
* **Problem**: In indicator and performance metric classes (`SharpeRatio`, `SortinoRatio`, `ProfitFactor`), dividing by standard deviation or zero loss can produce `Double.NaN` or `Double.POSITIVE_INFINITY`.
* **Bug Vector**: `NaN` propagates through calculations silently, leading to invalid trade signals or NaN prices in JSON responses.
* **Developer Fix**:
```java
public static double calculateProfitFactor(double grossProfit, double grossLoss) {
    if (Double.compare(grossLoss, 0.0) == 0) {
        return grossProfit > 0.0 ? 999.0 : 0.0; // Safe upper bound instead of Infinity
    }
    double factor = grossProfit / grossLoss;
    return Double.isNaN(factor) ? 0.0 : factor;
}
```

### 2.2 Null Dereference Prevention (`Optional<T>` & Mandatory Checks)
* **Problem**: Direct null returns in `DataLoader`, `RunManager`, and strategy resolution can trigger `NullPointerException` at runtime.
* **Developer Fix**:
```java
// Prefer Optional for query methods that may yield no result
public Optional<RunRecord> findRunRecordById(String runId) {
    Objects.requireNonNull(runId, "runId must not be null");
    return Optional.ofNullable(runStore.get(runId));
}
```

### 2.3 Eliminating `System.out.println` & `e.printStackTrace()`
* **Problem**: Raw stdout prints in background loops clog console output, mask actual error tracebacks, and lack timestamping or severity levels.
* **Developer Fix**:
```java
// Standardize on SLF4J Logger
private static final Logger log = LoggerFactory.getLogger(OandaTransactionStreamer.class);

public void onTransactionReceived(Transaction event) {
    try {
        processTransaction(event);
    } catch (BrokerException e) {
        log.error("Failed to process transaction [id={}]: {}", event.id(), e.getMessage(), e);
    }
}
```

---

## 3. Test-First Engineering & Regression Suite

### 3.1 Unit Testing Core Math & Indicators
Ensure 100% test coverage for core indicator logic in `trading-core`:
* `IndicatorsTest.java`: Verify SMA, EMA, RSI, ATR against known sample price arrays.
* `ForexPnLTest.java`: Test PIP calculations across quote currencies (`EUR/USD`, `USD/JPY`, `EUR/GBP`).

### 3.2 Mocking Streaming & External Dependencies
Use **Mockito** to mock broker streaming connections so strategy logic can be tested in isolation:
```java
@Test
void testStrategyTriggersOrderOnBreakout() {
    BrokerConnector mockBroker = mock(BrokerConnector.class);
    BreakoutStrategy strategy = new BreakoutStrategy(mockBroker);

    strategy.onBar(new Bar(Instant.now(), 1.1000, 1.1050, 1.0990, 1.1045, 1000));

    verify(mockBroker, times(1)).submitOrder(any(Order.class));
}
```

---

## 4. Single Responsibility & Refactoring Best Practices

### 4.1 Method Length & Cyclomatic Complexity Rule
* Keep all methods under **30 lines**.
* Keep nesting levels to a maximum of **2 indents** by using early exit guard clauses:

```java
// BEFORE: Nested indents
public void processOrder(Order order) {
    if (order != null) {
        if (order.getQuantity() > 0) {
            if (riskEngine.validate(order)) {
                broker.send(order);
            }
        }
    }
}

// AFTER: Guard Clauses & Clean Execution
public void processOrder(Order order) {
    Objects.requireNonNull(order, "order must not be null");
    if (order.getQuantity() <= 0) return;
    if (!riskEngine.validate(order)) return;

    broker.send(order);
}
```

### 4.2 Strategy Modularization
Store strategy parameter ranges in centralized immutable DTOs or JSON configs (`wfa-config.json`) rather than hardcoding static fields directly inside strategy classes.

---

## 5. Actionable Developer Checklist

- [ ] **Zero-Division Check**: Verify all indicator classes (`SharpeRatio`, `ProfitFactor`, `CalmarRatio`) guard against `0.0` denominators.
- [ ] **Null-Safety Audit**: Replace raw null returns in strategy catalogs and loaders with `Optional<T>`.
- [ ] **Logger Replacement**: Replace all `System.out.println` and `e.printStackTrace()` references with SLF4J loggers.
- [ ] **Unit Tests**: Ensure every strategy class has a corresponding JUnit test verifying entry/exit triggers.
- [ ] **SpotBugs & Checkstyle**: Add SpotBugs static analyzer to `pom.xml` build pipeline to catch latent bugs during `./mvnw compile`.
