package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Gold (MGC) M30 Breakout ({@code SQ_MGC_M30_Breakout_1_29_102}).
 *
 * <p>Backtest KPIs: Net Profit $9,457.44 (IS $7,805.86 / OOS $1,651.58 [+9.8%/yr]),
 * Profit Factor 1.38 (IS 1.35 / OOS 1.61), Ret/DD 10.94, Max DD $864.36 (8.6%), 1,052 trades (90 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (10 oz). Martingale MM purged per risk mandate.</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} GOLD BREAKOUT M30 - Dukascopy</li>
 *   <li>{@code @sqx-id} Strategy 1.29.102</li>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Signal: Open[2] &lt; DailyHigh[2] &amp; Breakout above Highest(420)[1] (Valid 10 bars)</li>
 *   <li>Long SL: 2.8 &times; ATR(20) | Long PT: 5.0 &times; ATR(30)</li>
 *   <li>Short Signal: ROC(20)[1] &lt; -0.28 &amp; Close[1] &lt; BB(362, 2.1).Upper[1] &amp; Breakdown below Lowest(685)[1]</li>
 *   <li>Short SL: 1.9 &times; ATR(20) | Short PT: 3.1 &times; ATR(20)</li>
 * </ul>
 */
public class SqMgcM30Breakout129102 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-mgc-m30-breakout-1-29-102";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro Gold contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 425;
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

  private Order.Side armedSide = null;
  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMgcM30Breakout129102() {
    this(DEFAULT_SYMBOL);
  }

  public SqMgcM30Breakout129102(String symbol) {
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
    return "SQ_MGC_M30_Breakout_1_29_102";
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

    double atr20 = calcAtr(20);
    double atr30 = calcAtr(30);

    double dailyHigh2 = calcHighestHigh(M30_BARS_PER_DAY, 2);
    double highest420 = calcHighestClose(420, 1);
    boolean longFilter = getBar(2).open() < dailyHigh2;

    if (longFilter && highest420 > 0) {
      double slDist = quantize(2.8 * atr20);
      double ptDist = quantize(5.0 * atr30);
      if (bar.close() > highest420) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else {
        armOrder(Order.Side.BUY, quantize(highest420), slDist, ptDist, 10);
      }
    }

    double roc20Shift1 = calcRoc(20, 1);
    double bbUpper362 = calcBollingerUpper(362, 2.1, 1);
    int shortLookback = Math.min(685, history.size() - 2);
    double lowestChannel = calcLowestClose(shortLookback, 1);

    if (roc20Shift1 < -0.28 && getBar(1).close() < bbUpper362 && lowestChannel > 0) {
      double slDist = quantize(1.9 * atr20);
      double ptDist = quantize(3.1 * atr20);
      if (bar.close() < lowestChannel) {
        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
        clearArmedOrder();
      } else {
        armOrder(Order.Side.SELL, quantize(lowestChannel), slDist, ptDist, 7);
      }
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
    this.barsInTrade = 0;
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

  private double calcHighestClose(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    if (end < 0) {
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
    int start = Math.max(0, end - period + 1);
    if (end < 0) {
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

  private double calcAtr(int period) {
    int end = history.size() - 1;
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

  private double calcBollingerUpper(int period, double mult, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period + 1);
    int n = end - start + 1;
    if (n <= 0) {
      return 0.0;
    }
    double sum = 0.0;
    for (int i = start; i <= end; i++) {
      sum += history.get(i).close();
    }
    double mean = sum / n;
    double varSum = 0.0;
    for (int i = start; i <= end; i++) {
      double d = history.get(i).close() - mean;
      varSum += d * d;
    }
    double std = Math.sqrt(varSum / n);
    return mean + mult * std;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}

