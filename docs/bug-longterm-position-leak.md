# Documented Bug: Position Leak & Exit Side Routing

This document outlines the critical logic bugs identified in the `longterm` strategy folder and the unified code generator template.

---

## 1. The Position Leak Bug (Under-Hedging)

### Description
All strategies in the `longterm` folder calculated dynamic position sizes for entries using ATR-based risk management (`Indicators.calcRiskPosition(...)`). However, their exit methods had the exit order quantity hardcoded to a fixed **`1000` units** (e.g., `pending.add(new Order(..., 1000, ...).closeOnly())`).

### Mechanics & Impact
1.  **Entry**: The strategy enters a position with a calculated size (e.g., **25,000 units**).
2.  **Exit**: The strategy triggers an exit signal and sends a `closeOnly` order for **1,000 units**.
3.  **Leaked Units**: The broker/backtester processes the `closeOnly` order and closes only `Math.min(1000, 25000) = 1000` units. The remaining **24,000 units (96% of the position) remain open**.
4.  **No Monitoring**: The strategy immediately marks `inTrade = false`, stopping all stop loss and take profit checks on the remaining 24,000 units.
5.  **Accumulation**: On the next entry signal, it scales in with another 25,000 units. The position grows indefinitely.
6.  **Backtest Illusion**: At the end of the backtest, all remaining open positions are liquidated at the final close price. If the market has trended, this produces a massive, artificial profit (e.g., +813% on `LtRSI3Momentum`) with an artificially low drawdown (e.g., 0.47%). 
7.  **Live Risk**: In live trading, this would quickly lead to a margin call or total account wipeout on trend reversals.

---

## 2. The Exit Side Routing Bug

### Description
In the code generator template (`LtTemplateCodeGenerator.java`), the exit side was hardcoded to `inTrade ? Order.Side.SELL : Order.Side.BUY`. Since the exit method was only called when `inTrade` was true, the exit order side was **always `Order.Side.SELL`**.

### Mechanics & Impact
*   **Long Trade**: Starts with `BUY`, exits with `SELL` (Correct).
*   **Short Trade**: Starts with `SELL`, exits with `SELL` (Bug).
    *   Exiting a short trade requires a `BUY` order. Sending a `SELL` order would either open a new short position (hedging) or be rejected/dropped as a close-only mismatch.

---

## 🛠️ Resolution Implemented

1.  **Track Position Units**:
    *   Added a `positionUnits` variable (`double` or `long`) to all strategy files and the template generator.
    *   Stored the exact quantity filled on entry and passed it as the quantity for the exit order.
2.  **Direction-Aware Exits**:
    *   Added a `direction` / `tradeDirection` variable (`Order.Side`) to track entry direction.
    *   Exits now correctly route opposite order sides: `direction == Side.BUY ? Side.SELL : Side.BUY`.
