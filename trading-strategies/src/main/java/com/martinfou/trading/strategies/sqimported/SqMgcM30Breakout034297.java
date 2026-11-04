package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Gold (MGC) M30 HighD SuperTrend Breakout ({@code SQ_MGC_M30_Breakout_0_34297}).
 *
 * <p>Backtest KPIs: ELITE Tier (Score 90.6), R² 0.949, UPI 6.13, Sharpe 0.89,
 * Profit Factor 1.43 (OOS PF 1.31), Ret/DD 9.07, Max DD 13.6%, Net Profit $12,373.15
 * (IS $7,075.15 / OOS $5,298.00), 1,038 trades (320 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (10 oz).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} MGC M30 Breakout</li>
 *   <li>{@code @sqx-id} Strategy 0.34297</li>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Signal: DailyHigh[2] &gt; Close[2]</li>
 *   <li>Long Entry: Stop at Highest(163)[3] (Valid 10 bars, SL 1.8x ATR(24), PT 2.9x ATR(19))</li>
 *   <li>Short Signal: DailyHigh[3] &ge; DailyHigh[1] for 6 bars &amp;&amp; BarClosesBelowSuperTrend(12, 1.3)[2]</li>
 *   <li>Short Entry: Market at close (SL 3.2x ATR(19), PT 4.8x ATR(24), MoveSL2BE at 50.0 pts)</li>
 * </ul>
 */
public class SqMgcM30Breakout034297 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-mgc-m30-breakout-0-34297";
  private static final double QUANTITY = 1.0;
  private static final int MIN_WARMUP_BARS = 60;
  private static final int M30_BARS_PER_DAY = 48;

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inTrade = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;

  private Order.Side armedSide = null;
  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMgcM30Breakout034297() {
    this(DEFAULT_SYMBOL);
  }

  public SqMgcM30Breakout034297(String symbol) {
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
    return "SQ_MGC_M30_Breakout_0_34297";
  }

  @Override
  public void onTick(double bid, double ask, long volume) {
    // Bar-based M30 breakout strategy
  }

  @Override
  public void onBar(Bar bar) {
    history.add(bar);
    if (history.size() < MIN_WARMUP_BARS) {
      return;
    }

    if (inTrade) {
      checkExitConditions(bar);
      return;
    }

    if (armedSide != null && armedBarsRemaining > 0) {
      if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
        double fill = Math.max(bar.open(), armedStopPrice);
        executeEntry(Order.Side.BUY, quantize(fill), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
        double fill = Math.min(bar.open(), armedStopPrice);
        executeEntry(Order.Side.SELL, quantize(fill), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    double atr19 = calcAtr(19, 0);
    double atr24 = calcAtr(24, 0);

    // Long Signal: DailyHigh[2] > Close[2]
    double dailyHigh2 = calcHighestHigh(M30_BARS_PER_DAY, 2);
    if (dailyHigh2 > getBar(2).close()) {
      int lookback = Math.min(163, history.size() - 4);
      double highest163Shift3 = calcHighestHigh(lookback, 3);
      double stopPrice = quantize(highest163Shift3);
      double slDist = quantize(1.8 * atr24);
      double ptDist = quantize(2.9 * atr19);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 10);
      }
    }

    // Short Signal: DailyHigh[3] >= DailyHigh[1] && BarClosesBelowSuperTrend(12, 1.3)[2]
    double dailyHigh3 = calcHighestHigh(M30_BARS_PER_DAY, 3);
    double dailyHigh1 = calcHighestHigh(M30_BARS_PER_DAY, 1);
    double stUpper = calcSuperTrendUpper(12, 1.3, 2);
    if (dailyHigh3 >= dailyHigh1 && getBar(2).close() < stUpper) {
      double slDist = quantize(3.2 * atr19);
      double ptDist = quantize(4.8 * atr24);
      executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
      clearArmedOrder();
    }
  }

  private void executeEntry(Order.Side side, double price, double slDist, double ptDist) {
    double safeSlDist = Math.max(slDist, 0.50);
    double safePtDist = Math.max(ptDist, 0.50);
    double sl = side == Order.Side.BUY ? quantize(price - safeSlDist) : quantize(price + safeSlDist);
    double tp = side == Order.Side.BUY ? quantize(price + safePtDist) : quantize(price - safePtDist);

    Order order = new Order(symbol, side, Order.Type.MARKET, QUANTITY, price)
        .withStrategyId(STRATEGY_ID)
        .withStopLoss(sl)
        .withTakeProfit(tp);

    pendingOrders.add(order);
    this.inTrade = true;
    this.positionSide = side;
    this.entryPrice = price;
    this.stopLoss = sl;
    this.takeProfit = tp;
  }

  private void checkExitConditions(Bar bar) {
    if (positionSide == Order.Side.BUY) {
      if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
        closePosition();
      }
    } else if (positionSide == Order.Side.SELL) {
      if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
        closePosition();
      }
    }
  }

  private void armOrder(Order.Side side, double stopPrice, double slDist, double ptDist, int barsValid) {
    this.armedSide = side;
    this.armedStopPrice = stopPrice;
    this.armedSlDist = slDist;
    this.armedPtDist = ptDist;
    this.armedBarsRemaining = barsValid;
  }

  private void clearArmedOrder() {
    this.armedSide = null;
    this.armedStopPrice = 0.0;
    this.armedSlDist = 0.0;
    this.armedPtDist = 0.0;
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

  private double calcAtr(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(1, end - period + 1);
    if (end < start) {
      return 1.0;
    }
    double sum = 0.0;
    int count = 0;
    for (int i = start; i <= end; i++) {
      Bar cur = history.get(i);
      Bar prev = history.get(i - 1);
      double tr = Math.max(
          cur.high() - cur.low(),
          Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
      sum += tr;
      count++;
    }
    return count > 0 ? sum / count : 1.0;
  }

  private double calcHighestHigh(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    double highest = 0.0;
    for (int i = start; i <= end && i < history.size(); i++) {
      highest = Math.max(highest, history.get(i).high());
    }
    return highest;
  }

  private double calcLowestLow(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    double lowest = Double.MAX_VALUE;
    for (int i = start; i <= end && i < history.size(); i++) {
      lowest = Math.min(lowest, history.get(i).low());
    }
    return lowest == Double.MAX_VALUE ? 0.0 : lowest;
  }

  private double calcSuperTrendUpper(int atrPeriod, double mult, int shift) {
    double atr = calcAtr(atrPeriod, shift);
    double mid = (calcHighestHigh(atrPeriod, shift) + calcLowestLow(atrPeriod, shift)) / 2.0;
    return mid - mult * atr;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}
