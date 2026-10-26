package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * StrategyQuant Gold Breakout M30 — Strategy 1.10.110.
 *
 * <p>Transferred from StrategyQuant project {@code GOLD BREAKOUT M30 - Dukascopy}.
 * Sizing: Strict Fixed 1 Micro Contract (10 oz). Martingale MM purged per risk mandate.</p>
 *
 * <ul>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Entry: Low[3] &lt; DailyHigh[2] &amp; Breakout above Highest(Close, 400)[1]</li>
 *   <li>Long SL: 1.7 &times; ATR(20) | Long PT: 4.3 &times; ATR(30)</li>
 *   <li>Short Entry: Ichimoku Senkou Span Cross &amp; Breakdown below Lowest(Close, 390)[1]</li>
 *   <li>Short SL: 1.3 &times; ATR(20) | Short PT: 2.4 &times; ATR(25)</li>
 * </ul>
 */
@SuppressWarnings("checkstyle:TypeName")
public class Strategy_Gold_Breakout_M30 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-gold-m30";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro Gold contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 405; // Needs 400 bars for Highest/Lowest channel

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inPosition = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;
  private int barsInTrade = 0;

  public Strategy_Gold_Breakout_M30() {
    this(DEFAULT_SYMBOL);
  }

  public Strategy_Gold_Breakout_M30(String symbol) {
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
    return "SQ_Gold_Breakout_M30_1_10_110";
  }

  @Override
  public void onTick(double bid, double ask, long volume) {
    // Bar-based breakout strategy
  }

  @Override
  public void onBar(Bar bar) {
    history.add(bar);
    if (history.size() < MIN_WARMUP_BARS) {
      return;
    }

    // Manage existing open position
    if (inPosition) {
      barsInTrade++;
      checkExitConditions(bar);
      return;
    }

    // Evaluate Entry signals on completed bar
    double atr20 = calcAtr(20);
    double atr25 = calcAtr(25);
    double atr30 = calcAtr(30);

    double highest400 = calcHighestClose(400, 1);
    double lowest390 = calcLowestClose(390, 1);

    // Long Condition: Low[3] < DailyHigh[2] and price breaks out above Highest(400)
    double dailyHigh2 = calcHighestHigh(48, 2); // 48 M30 bars = 24 hours daily high
    Bar bar3 = getBar(3);
    boolean longFilter = bar3.low() < dailyHigh2;

    if (longFilter && bar.close() > highest400 && highest400 > 0) {
      double slDist = quantize(1.7 * atr20);
      double ptDist = quantize(4.3 * atr30);
      double sl = quantize(bar.close() - slDist);
      double tp = quantize(bar.close() + ptDist);

      Order buyOrder = new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close())
          .withStrategyId(STRATEGY_ID)
          .withStopLoss(sl)
          .withTakeProfit(tp);

      pendingOrders.add(buyOrder);
      openPosition(Order.Side.BUY, bar.close(), sl, tp);
      return;
    }

    // Short Condition: Ichimoku Senkou Span bearish cross and breakdown below Lowest(390)
    boolean ichimokuBearishCross = calcIchimokuSenkouBearishCross(2);
    if (ichimokuBearishCross && bar.close() < lowest390 && lowest390 > 0) {
      double slDist = quantize(1.3 * atr20);
      double ptDist = quantize(2.4 * atr25);
      double sl = quantize(bar.close() + slDist);
      double tp = quantize(bar.close() - ptDist);

      Order sellOrder = new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close())
          .withStrategyId(STRATEGY_ID)
          .withStopLoss(sl)
          .withTakeProfit(tp);

      pendingOrders.add(sellOrder);
      openPosition(Order.Side.SELL, bar.close(), sl, tp);
    }
  }

  private void checkExitConditions(Bar bar) {
    if (positionSide == Order.Side.BUY) {
      if (bar.low() <= stopLoss) {
        closePosition("StopLoss hit at " + stopLoss);
      } else if (bar.high() >= takeProfit) {
        closePosition("TakeProfit hit at " + takeProfit);
      }
    } else if (positionSide == Order.Side.SELL) {
      if (bar.high() >= stopLoss) {
        closePosition("StopLoss hit at " + stopLoss);
      } else if (bar.low() <= takeProfit) {
        closePosition("TakeProfit hit at " + takeProfit);
      }
    }
  }

  private void openPosition(Order.Side side, double price, double sl, double tp) {
    this.inPosition = true;
    this.positionSide = side;
    this.entryPrice = price;
    this.stopLoss = sl;
    this.takeProfit = tp;
    this.barsInTrade = 0;
  }

  private void closePosition(String reason) {
    this.inPosition = false;
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
    inPosition = false;
    positionSide = null;
    entryPrice = 0.0;
    stopLoss = 0.0;
    takeProfit = 0.0;
    barsInTrade = 0;
  }

  // --- Technical Indicator Calculations ---

  private Bar getBar(int shift) {
    int idx = history.size() - 1 - shift;
    return history.get(Math.max(0, idx));
  }

  private double calcHighestClose(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 0) {
      return 0.0;
    }
    double max = -Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      double c = history.get(i).close();
      if (c > max) {
        max = c;
      }
    }
    return max;
  }

  private double calcLowestClose(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 0) {
      return 0.0;
    }
    double min = Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      double c = history.get(i).close();
      if (c < min) {
        min = c;
      }
    }
    return min;
  }

  private double calcHighestHigh(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 0) {
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

  private double calcAtr(int period) {
    int end = history.size() - 1;
    int start = end - period + 1;
    if (start < 1) {
      return 2.0; // Fallback typical gold ATR
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

  private boolean calcIchimokuSenkouBearishCross(int shift) {
    // Tenkan (9), Kijun (26), Senkou (159)
    double spanAPrev = calcSenkouSpanA(shift + 1);
    double spanBPrev = calcSenkouSpanB(shift + 1);
    double spanACurr = calcSenkouSpanA(shift);
    double spanBCurr = calcSenkouSpanB(shift);

    return spanAPrev >= spanBPrev && spanACurr < spanBCurr;
  }

  private double calcSenkouSpanA(int shift) {
    double tenkan = calcMidpoint(9, shift);
    double kijun = calcMidpoint(26, shift);
    return (tenkan + kijun) / 2.0;
  }

  private double calcSenkouSpanB(int shift) {
    return calcMidpoint(159, shift);
  }

  private double calcMidpoint(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = end - period + 1;
    if (start < 0) {
      return history.get(Math.max(0, end)).close();
    }
    double highest = -Double.MAX_VALUE;
    double lowest = Double.MAX_VALUE;
    for (int i = start; i <= end; i++) {
      Bar b = history.get(i);
      if (b.high() > highest) {
        highest = b.high();
      }
      if (b.low() < lowest) {
        lowest = b.low();
      }
    }
    return (highest + lowest) / 2.0;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}
