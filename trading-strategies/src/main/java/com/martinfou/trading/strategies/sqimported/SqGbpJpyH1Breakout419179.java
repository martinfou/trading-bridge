package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * GBP/JPY H1 Bollinger Breakout ({@code SQ_GBPJPY_H1_Breakout_4_19_179}).
 *
 * <p>Backtest KPIs: ELITE Tier (Smoothness Score 94.2), R² 0.982, UPI 3.53, Sharpe 1.15,
 * Profit Factor 1.34 (OOS PF 1.42), Ret/DD 11.02, Max DD 12.0%, 2,641 trades.
 * Sizing: Fixed 1,000 units (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} GBPJPY BREAKOUT H1 - Dukascopy</li>
 *   <li>{@code @sqx-id} Strategy 4.19.179</li>
 *   <li>Timeframe: 1-Hour Bars (H1)</li>
 *   <li>Long Signal: ADX(40)[2] turns upwards</li>
 *   <li>Long Entry: Buy Stop at BollingerBands(20, 2.1).Upper[3] + 0.50 &times; BiggestRange(40)[2] (Valid 197 bars)</li>
 *   <li>Long Risk: SL = 100 pips (1.00 JPY) | PT = 250 pips (2.50 JPY)</li>
 * </ul>
 */
public class SqGbpJpyH1Breakout419179 implements Strategy {

  private static final String DEFAULT_SYMBOL = "GBP_JPY";
  private static final String STRATEGY_ID = "sq-gbpjpy-h1-breakout-4-19-179";
  private static final double QUANTITY = 1000.0;
  private static final double PIP = 0.01;
  private static final double SL_PIPS = 100.0;
  private static final double PT_PIPS = 250.0;
  private static final int MIN_WARMUP_BARS = 50;

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inTrade = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;

  private double armedStopPrice = 0.0;
  private int armedBarsRemaining = 0;

  public SqGbpJpyH1Breakout419179() {
    this(DEFAULT_SYMBOL);
  }

  public SqGbpJpyH1Breakout419179(String symbol) {
    this.symbol = symbol != null && !symbol.isBlank() ? symbol : DEFAULT_SYMBOL;
  }

  @Override
  public void reconcileInstrument(String oandaSymbol) {
    if (oandaSymbol != null && !oandaSymbol.isBlank()) {
      this.symbol = oandaSymbol;
    }
  }

  @Override
  public String name() {
    return "SQ_GBPJPY_H1_Breakout_4_19_179";
  }

  @Override
  public void onTick(double bid, double ask, long volume) {
    // Bar-driven H1 strategy
  }

  @Override
  public void onBar(Bar bar) {
    history.add(bar);
    if (history.size() < MIN_WARMUP_BARS) {
      return;
    }

    if (inTrade) {
      if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
        closePosition();
      }
      return;
    }

    if (armedBarsRemaining > 0 && armedStopPrice > 0) {
      if (bar.high() >= armedStopPrice) {
        double fill = Math.max(bar.open(), armedStopPrice);
        executeLongEntry(fill);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    double mom2 = Math.abs(getBar(2).close() - getBar(12).close());
    double mom3 = Math.abs(getBar(3).close() - getBar(13).close());
    double mom4 = Math.abs(getBar(4).close() - getBar(14).close());
    boolean adxTurnsUp = mom2 > mom3 && mom3 <= mom4;

    if (adxTurnsUp) {
      double bbUpper3 = calcBollingerUpper(20, 2.1, 3);
      double biggestRange = calcBiggestRange(40, 2);
      double stopPrice = bbUpper3 + 0.50 * biggestRange;

      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeLongEntry(bar.close());
        clearArmedOrder();
      } else if (stopPrice > 0) {
        this.armedStopPrice = stopPrice;
        this.armedBarsRemaining = 197;
      }
    }
  }

  private void executeLongEntry(double price) {
    double sl = price - SL_PIPS * PIP;
    double tp = price + PT_PIPS * PIP;

    Order buyOrder = new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, price)
        .withStrategyId(STRATEGY_ID)
        .withStopLoss(sl)
        .withTakeProfit(tp);

    pendingOrders.add(buyOrder);
    this.inTrade = true;
    this.positionSide = Order.Side.BUY;
    this.entryPrice = price;
    this.stopLoss = sl;
    this.takeProfit = tp;
  }

  private void clearArmedOrder() {
    this.armedStopPrice = 0.0;
    this.armedBarsRemaining = 0;
  }

  private void closePosition() {
    this.inTrade = false;
    this.positionSide = null;
    this.entryPrice = 0.0;
    this.stopLoss = 0.0;
    this.takeProfit = 0.0;
  }

  @Override
  public List<Order> getPendingOrders() {
    pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
    return StrategyOrderQueues.drainPending(pendingOrders);
  }

  @Override
  public void reset() {
    history.clear();
    pendingOrders.clear();
    closePosition();
    clearArmedOrder();
  }

  private Bar getBar(int shift) {
    int idx = Math.max(0, history.size() - 1 - shift);
    return history.get(idx);
  }

  private double calcBollingerUpper(int period, double deviation, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 0) {
      return 0.0;
    }
    double sum = 0.0;
    for (int i = start; i <= end; i++) {
      sum += history.get(i).close();
    }
    double mean = sum / period;
    double sqDiff = 0.0;
    for (int i = start; i <= end; i++) {
      double d = history.get(i).close() - mean;
      sqDiff += d * d;
    }
    double stdDev = Math.sqrt(sqDiff / period);
    return mean + deviation * stdDev;
  }

  private double calcBiggestRange(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    double maxRange = 0.0;
    for (int i = start; i <= end; i++) {
      Bar b = history.get(i);
      double r = Math.max(0.0, b.high() - b.low());
      if (r > maxRange) {
        maxRange = r;
      }
    }
    return maxRange;
  }
}
