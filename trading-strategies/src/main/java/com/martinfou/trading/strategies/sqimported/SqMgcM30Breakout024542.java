package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Gold (MGC) M30 ADX PDH Breakout ({@code SQ_MGC_M30_Breakout_0_24542}).
 *
 * <p>Backtest KPIs: ELITE Tier (Smoothness Score 96.0), R² 0.949, UPI 6.41, Sharpe 1.06,
 * Profit Factor 1.56 (OOS PF 1.58), Ret/DD 6.54, Max DD 18.5%, 896 trades.
 * Sizing: Strict Fixed 1 Micro Contract (10 oz).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} MGC M30 Breakout</li>
 *   <li>{@code @sqx-id} Strategy 0.24542</li>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Signal: ADX(14)[1] &lt; 32.0 &amp; DailyHigh[3] &gt; DailyClose[3]</li>
 *   <li>Long Entry: Stop at Highest(30)[1] + 2.60 &times; ATR(20)[3] (Valid 6 bars, SL 1.1x ATR, PT 2.6x ATR)</li>
 *   <li>Short Signal: ROC(24)[2] &lt; -8.6 &amp; Lowest(40)[1] &lt; Close[2]</li>
 *   <li>Short Entry: Stop at Lowest(14)[3] (Valid 4 bars, SL 1.7x ATR(14), PT 2.8x ATR(14))</li>
 * </ul>
 */
public class SqMgcM30Breakout024542 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-mgc-m30-breakout-0-24542";
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
  private int barsInTrade = 0;
  private int maxBarsInTrade = 46;

  private Order.Side armedSide = null;
  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMgcM30Breakout024542() {
    this(DEFAULT_SYMBOL);
  }

  public SqMgcM30Breakout024542(String symbol) {
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
    return "SQ_MGC_M30_Breakout_0_24542";
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
      barsInTrade++;
      checkExitConditions(bar);
      return;
    }

    if (armedSide != null && armedBarsRemaining > 0) {
      if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
        double fill = Math.max(bar.open(), armedStopPrice);
        executeEntry(Order.Side.BUY, quantize(fill), armedSlDist, armedPtDist, 46);
        clearArmedOrder();
        return;
      } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
        double fill = Math.min(bar.open(), armedStopPrice);
        executeEntry(Order.Side.SELL, quantize(fill), armedSlDist, armedPtDist, 36);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    double atr20 = calcAtr(20, 0);
    double atr20Shift3 = calcAtr(20, 3);
    double atr14 = calcAtr(14, 0);

    double dailyHigh3 = calcHighestHigh(M30_BARS_PER_DAY, 3);
    double dailyClose3 = getBar(3).close();
    double adx14 = calcAdxProxy(14, 1);

    if (adx14 < 32.0 && dailyHigh3 > dailyClose3) {
      double highest30 = calcHighestHigh(30, 1);
      double stopPrice = quantize(highest30 + 2.60 * atr20Shift3);
      double slDist = quantize(1.1 * atr20);
      double ptDist = quantize(2.6 * atr20);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist, 46);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 6);
      }
    }

    double roc24Shift2 = calcRoc(24, 2);
    double lowest40 = calcLowestLow(40, 1);
    if (roc24Shift2 < -0.86 && lowest40 < getBar(2).close()) {
      double lowest14Shift3 = calcLowestLow(14, 3);
      double slDist = quantize(1.7 * atr14);
      double ptDist = quantize(2.8 * atr14);
      if (bar.close() <= lowest14Shift3 && lowest14Shift3 > 0) {
        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist, 36);
        clearArmedOrder();
      } else if (lowest14Shift3 > 0) {
        armOrder(Order.Side.SELL, quantize(lowest14Shift3), slDist, ptDist, 4);
      }
    }
  }

  private void executeEntry(Order.Side side, double price, double slDist, double ptDist, int maxBars) {
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
    this.barsInTrade = 0;
    this.maxBarsInTrade = maxBars;
  }

  private void checkExitConditions(Bar bar) {
    if (barsInTrade >= maxBarsInTrade) {
      closePosition();
      return;
    }
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
    this.barsInTrade = 0;
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

  private double calcAdxProxy(int period, int shift) {
    double atr = calcAtr(period, shift);
    if (atr <= 0.0) {
      return 25.0;
    }
    double netMove = Math.abs(getBar(shift).close() - getBar(shift + period).close());
    return Math.min(100.0, (netMove / (atr * Math.sqrt(period))) * 25.0);
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
