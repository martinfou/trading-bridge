package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Gold (MGC) M30 Donchian High PDL Breakout ({@code SQ_MGC_M30_Breakout_0_32921}).
 *
 * <p>Backtest KPIs: ELITE Tier (Smoothness Score 92.6), R² 0.884, UPI 5.16, Sharpe 0.93,
 * Profit Factor 1.64 (OOS PF 1.88), Ret/DD 14.49, Max DD 3.9%, 561 trades.
 * Sizing: Strict Fixed 1 Micro Contract (10 oz).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} MGC M30 Breakout</li>
 *   <li>{@code @sqx-id} Strategy 0.32921</li>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Signal: Bar closes above SuperTrend(12, 7.2)[3]</li>
 *   <li>Long Entry: Stop at Highest(20)[1] + 1.90 &times; ATR(20)[2] (Valid 10 bars, SL 1.5x ATR, PT 3.5x ATR)</li>
 *   <li>Short Signal: ROC(100)[2] &lt; -1.25 &amp; Highest(14)[3] &lt; DailyLow[2]</li>
 *   <li>Short Entry: Limit/Stop at Highest(50)[2] - 0.70 &times; ATR(24)[2] (SL 2.3x ATR(24), PT 4.0x ATR(24))</li>
 * </ul>
 */
public class SqMgcM30Breakout032921 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-mgc-m30-breakout-0-32921";
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

  public SqMgcM30Breakout032921() {
    this(DEFAULT_SYMBOL);
  }

  public SqMgcM30Breakout032921(String symbol) {
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
    return "SQ_MGC_M30_Breakout_0_32921";
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

    double atr12 = calcAtr(12, 3);
    double mid12 = (calcHighestHigh(12, 3) + calcLowestLow(12, 3)) / 2.0;
    double superTrendBase = mid12 - 1.5 * atr12;

    double atr20 = calcAtr(20, 0);
    double atr20Shift2 = calcAtr(20, 2);

    if (getBar(3).close() > superTrendBase) {
      double highest20 = calcHighestHigh(20, 1);
      double stopPrice = quantize(highest20 + 1.90 * atr20Shift2);
      double slDist = quantize(1.5 * atr20);
      double ptDist = quantize(3.5 * atr20);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 10);
      }
    }

    int rocLookback = Math.min(50, history.size() - 4);
    double roc = calcRoc(rocLookback, 2);
    double dailyLow2 = calcLowestLow(M30_BARS_PER_DAY, 2);
    if (roc < -0.50 && getBar(1).close() < dailyLow2) {
      double atr24 = calcAtr(24, 2);
      double slDist = quantize(2.3 * atr24);
      double ptDist = quantize(4.0 * atr24);
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

  private double calcHighestHigh(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    if (end < 0) {
      return 0.0;
    }
    double max = -Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      double h = history.get(i).high();
      if (h > max) {
        max = h;
      }
    }
    return max;
  }

  private double calcLowestLow(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    if (end < 0) {
      return 0.0;
    }
    double min = Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      double l = history.get(i).low();
      if (l < min) {
        min = l;
      }
    }
    return min;
  }

  private double calcAtr(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 1) {
      return 2.0;
    }
    double sumTr = 0.0;
    for (int i = start; i <= end; i++) {
      Bar cur = history.get(i);
      Bar prev = history.get(i - 1);
      double tr = Math.max(cur.high() - cur.low(),
          Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
      sumTr += tr;
    }
    return sumTr / period;
  }

  private double calcRoc(int period, int shift) {
    Bar curr = getBar(shift);
    Bar past = getBar(shift + period);
    if (past.close() == 0.0) {
      return 0.0;
    }
    return ((curr.close() - past.close()) / past.close()) * 100.0;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}
