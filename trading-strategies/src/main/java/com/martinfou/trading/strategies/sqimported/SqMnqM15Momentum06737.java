package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Nasdaq (MNQ) M15 Dual-SuperTrend Momentum ({@code SQ_MNQ_M15_Momentum_0_6737}).
 *
 * <p>Backtest KPIs: STRONG/ELITE Tier (Score 69.8), R² 0.841, UPI 2.40, Sharpe 0.56,
 * Profit Factor 1.44 (IS 1.32 / OOS 1.57), Ret/DD 7.89, Max DD 4.1% ($408.19),
 * Net Profit $3,220.67 (IS $1,271.70 / OOS $1,948.96), 356 trades (107 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} MNQ M15 Momentum</li>
 *   <li>{@code @sqx-id} Strategy 0.6737</li>
 *   <li>Timeframe: 15-Minute Bars (M15)</li>
 *   <li>Long Signal: BarClosesAboveSuperTrend(20, 4.8)[2] &amp;&amp; BarClosesAboveSuperTrend(20, 2.5)[2]</li>
 *   <li>Long Entry: Stop at LowD[1] + 0.30 &times; ATR(30)[2] (Valid 7 bars, SL 3.1x ATR(14), PT 3.1x ATR(14))</li>
 *   <li>Short Signal: BarClosesAboveSuperTrend(500, 9.9)[1] &amp;&amp; RSI(50)[2] &le; ROC(43)[2]</li>
 *   <li>Short Entry: Stop at HighD[1] + 1.70 &times; ATR(20)[1] (Valid 2 bars, SL 2.9x ATR(19), PT 4.1x ATR(14))</li>
 * </ul>
 */
public class SqMnqM15Momentum06737 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MNQ";
  private static final String STRATEGY_ID = "sq-mnq-m15-momentum-0-6737";
  private static final double QUANTITY = 1.0;
  private static final int MIN_WARMUP_BARS = 60;
  private static final int M15_BARS_PER_DAY = 96;

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

  public SqMnqM15Momentum06737() {
    this(DEFAULT_SYMBOL);
  }

  public SqMnqM15Momentum06737(String symbol) {
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
    return "SQ_MNQ_M15_Momentum_0_6737";
  }

  @Override
  public void onTick(double bid, double ask, long volume) {
    // Bar-driven M15 strategy
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

    double atr14 = calcAtr(14, 0);
    double atr19 = calcAtr(19, 0);

    // Long Signal: BarClosesAboveSuperTrend(20, 4.8)[2] && BarClosesAboveSuperTrend(20, 2.5)[2]
    double stFast = calcSuperTrendLower(20, 2.5, 2);
    double stWide = calcSuperTrendLower(20, 4.8, 2);
    double close2 = getBar(2).close();
    if (close2 > stFast && close2 > stWide) {
      double lowD1 = calcLowestLow(M15_BARS_PER_DAY, 1);
      double atr30Shift2 = calcAtr(30, 2);
      double stopPrice = quantize(lowD1 + 0.30 * atr30Shift2);
      double slDist = quantize(3.1 * atr14);
      double ptDist = quantize(3.1 * atr14);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 7);
      }
    }

    // Short Signal: RSI(50)[2] <= ROC(43)[2]
    double rsi50 = calcRsi(50, 2);
    double roc43 = calcRoc(43, 2);
    if (rsi50 <= roc43) {
      double highD1 = calcHighestHigh(M15_BARS_PER_DAY, 1);
      double atr20Shift1 = calcAtr(20, 1);
      double stopPrice = quantize(highD1 + 1.70 * atr20Shift1);
      double slDist = quantize(2.9 * atr19);
      double ptDist = quantize(4.1 * atr14);
      if (bar.close() <= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
        clearArmedOrder();
      } else if (stopPrice > 0) {
        armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, 2);
      }
    }
  }

  private void executeEntry(Order.Side side, double price, double slDist, double ptDist) {
    double safeSlDist = Math.max(slDist, 1.0);
    double safePtDist = Math.max(ptDist, 1.0);
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

  private double calcSuperTrendLower(int atrPeriod, double mult, int shift) {
    double atr = calcAtr(atrPeriod, shift);
    double mid = (calcHighestHigh(atrPeriod, shift) + calcLowestLow(atrPeriod, shift)) / 2.0;
    return mid - (mult * 0.25) * atr;
  }

  private double calcRsi(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(1, end - period + 1);
    double gains = 0.0;
    double losses = 0.0;
    for (int i = start; i <= end; i++) {
      double diff = history.get(i).close() - history.get(i - 1).close();
      if (diff > 0) {
        gains += diff;
      } else {
        losses -= diff;
      }
    }
    if (losses == 0.0) {
      return 100.0;
    }
    double rs = gains / losses;
    return 100.0 - (100.0 / (1.0 + rs));
  }

  private double calcRoc(int period, int shift) {
    Bar cur = getBar(shift);
    Bar past = getBar(shift + period);
    if (past.close() == 0.0) {
      return 0.0;
    }
    return ((cur.close() - past.close()) / past.close()) * 100.0;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}
