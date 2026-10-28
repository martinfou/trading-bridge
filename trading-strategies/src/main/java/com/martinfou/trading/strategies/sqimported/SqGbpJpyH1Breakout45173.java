package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * GBP/JPY H1 PDH Breakout ({@code SQ_GBPJPY_H1_Breakout_4_5_173}).
 *
 * <p>Backtest KPIs: ELITE Tier (Smoothness Score 95.1), R² 0.964, UPI 3.10, Sharpe 0.91,
 * Profit Factor 1.27 (OOS PF 1.62), Ret/DD 11.90, Max DD 9.5%, 2,471 trades.
 * Sizing: Fixed 1,000 units (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} GBPJPY BREAKOUT H1 - Dukascopy</li>
 *   <li>{@code @sqx-id} Strategy 4.5.173</li>
 *   <li>Timeframe: 1-Hour Bars (H1)</li>
 *   <li>Long Signal: Bar opens below BollingerBands(10, 1.9).Lower[1]</li>
 *   <li>Long Entry: Buy Stop at DailyHigh(24)[1] + 1.20 &times; BiggestRange(5)[3] (Valid 186 bars)</li>
 *   <li>Long Risk: SL = 165 pips (1.65 JPY) | PT = 330 pips (3.30 JPY)</li>
 * </ul>
 */
public class SqGbpJpyH1Breakout45173 implements Strategy {

  private static final String DEFAULT_SYMBOL = "GBP_JPY";
  private static final String STRATEGY_ID = "sq-gbpjpy-h1-breakout-4-5-173";
  private static final double QUANTITY = 1000.0;
  private static final double PIP = 0.01;
  private static final double SL_PIPS = 165.0;
  private static final double PT_PIPS = 330.0;
  private static final int MIN_WARMUP_BARS = 30;
  private static final int H1_BARS_PER_DAY = 24;

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

  public SqGbpJpyH1Breakout45173() {
    this(DEFAULT_SYMBOL);
  }

  public SqGbpJpyH1Breakout45173(String symbol) {
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
    return "SQ_GBPJPY_H1_Breakout_4_5_173";
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

    double bbLower1 = calcBollingerLower(10, 1.9, 1);
    if (bar.open() < bbLower1 && bbLower1 > 0) {
      double dailyHigh1 = calcHighestHigh(H1_BARS_PER_DAY, 1);
      double biggestRange5Shift3 = calcBiggestRange(5, 3);
      double stopPrice = dailyHigh1 + 1.20 * biggestRange5Shift3;

      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeLongEntry(bar.close());
        clearArmedOrder();
      } else if (stopPrice > 0) {
        this.armedStopPrice = stopPrice;
        this.armedBarsRemaining = 186;
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

  private double calcBollingerLower(int period, double deviation, int shift) {
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
    return mean - deviation * stdDev;
  }

  private double calcHighestHigh(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    double max = -Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      double h = history.get(i).high();
      if (h > max) {
        max = h;
      }
    }
    return max;
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
