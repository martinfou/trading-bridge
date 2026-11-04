package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Gold (MGC) M30 Donchian ATR Volatility Breakout ({@code SQ_MGC_M30_Breakout_0_11248}).
 *
 * <p>Backtest KPIs: ELITE Tier (Score 91.5), R² 0.927, UPI 5.97, Sharpe 0.87,
 * Profit Factor 1.46 (OOS PF 1.53), Ret/DD 6.68, Max DD 15.0%, Net Profit $10,025.20
 * (IS $5,093.15 / OOS $4,932.05), 1,049 trades (270 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (10 oz).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} MGC M30 Breakout</li>
 *   <li>{@code @sqx-id} Strategy 0.11248</li>
 *   <li>Timeframe: 30-Minute Bars (M30)</li>
 *   <li>Long Signal: Highest(50)[1] &gt; Lowest(14)[3] across 10 bars</li>
 *   <li>Long Entry: Stop at Highest(14)[1] + 2.30 &times; ATR(180)[1] (Valid 3 bars, SL 2.2x ATR(14), PT 4.1x ATR(14))</li>
 *   <li>Short Signal: DailyHigh[2] &gt; DailyHigh[3] &amp;&amp; DailyLow[3] &le; Open[3]</li>
 *   <li>Short Entry: Stop at DailyLow[2] + 0.30 &times; ATR(50)[3] (Valid 9 bars, SL 2.0x ATR(19), PT 4.2x ATR(14))</li>
 * </ul>
 */
public class SqMgcM30Breakout011248 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MGC";
  private static final String STRATEGY_ID = "sq-mgc-m30-breakout-0-11248";
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
  private int maxBarsInTrade = 28;

  private Order.Side armedSide = null;
  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMgcM30Breakout011248() {
    this(DEFAULT_SYMBOL);
  }

  public SqMgcM30Breakout011248(String symbol) {
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
    return "SQ_MGC_M30_Breakout_0_11248";
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
        executeEntry(Order.Side.BUY, quantize(fill), armedSlDist, armedPtDist, 28);
        clearArmedOrder();
        return;
      } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
        double fill = Math.min(bar.open(), armedStopPrice);
        executeEntry(Order.Side.SELL, quantize(fill), armedSlDist, armedPtDist, 6);
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
    int longAtrPeriod = Math.min(180, history.size() - 3);
    double atr180Shift1 = calcAtr(longAtrPeriod, 1);

    // Long Signal: Highest(50)[1] > Lowest(14)[3] with upward expansion
    double highest50Shift1 = calcHighestHigh(50, 1);
    double lowest14Shift3 = calcLowestLow(14, 3);
    if (highest50Shift1 > lowest14Shift3) {
      double highest14Shift1 = calcHighestHigh(14, 1);
      double stopPrice = quantize(highest14Shift1 + 2.30 * atr180Shift1);
      double slDist = quantize(2.2 * atr14);
      double ptDist = quantize(4.1 * atr14);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist, 28);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 3);
      }
    }

    // Short Signal: DailyHigh[2] >= DailyHigh[3] && DailyLow[3] <= Open[3]
    double dailyHigh2 = calcHighestHigh(M30_BARS_PER_DAY, 2);
    double dailyHigh3 = calcHighestHigh(M30_BARS_PER_DAY, 3);
    double dailyLow3 = calcLowestLow(M30_BARS_PER_DAY, 3);
    if (dailyHigh2 >= dailyHigh3 && dailyLow3 <= getBar(3).open()) {
      double dailyLow2 = calcLowestLow(M30_BARS_PER_DAY, 2);
      double atr50Shift3 = calcAtr(50, 3);
      double stopPrice = quantize(dailyLow2 + 0.30 * atr50Shift3);
      double slDist = quantize(2.0 * atr19);
      double ptDist = quantize(4.2 * atr14);
      if (bar.close() <= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist, 6);
        clearArmedOrder();
      } else if (stopPrice > 0) {
        armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, 9);
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

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}

