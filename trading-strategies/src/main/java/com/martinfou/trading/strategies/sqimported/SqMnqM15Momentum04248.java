package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Nasdaq (MNQ) M15 Momentum ({@code SQ_MNQ_M15_Momentum_0_4248}).
 *
 * <p>Backtest KPIs (2013–2026, 2023–2026 Blind OOS): Net Profit $4,421.43 (IS $2,144.97 / OOS $2,276.46),
 * Profit Factor 1.36 (IS 1.32 / OOS 1.40), Ret/DD 7.55, Max DD $585.94 (5.9%), 516 trades (163 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} Institutional Suite / MNQ M15 Momentum</li>
 *   <li>{@code @sqx-id} Strategy 0.4248</li>
 *   <li>Timeframe: 15-Minute Bars (M15)</li>
 *   <li>Long Signal: High[1] &gt; SuperTrend(24, 4.7)[3]</li>
 *   <li>Long Entry: Stop at LowD[1] - 0.50 &times; ATR(30)[3] (Valid 8 bars)</li>
 *   <li>Long Risk: SL = 3.0 &times; ATR(14) | PT = 2.2 &times; ATR(24) | MoveSL2BE at 330 pts (+1.5 &times; ATR(314))</li>
 *   <li>Short Signal: ROC(21)[2] crosses above 22.8 &amp;&amp; Lowest(50)[3] &lt; High[3]</li>
 *   <li>Short Entry: Stop at Lowest(14)[1] - 0.90 &times; ATR(30)[2] (Valid 5 bars, ExitAfterBars = 20)</li>
 *   <li>Short Risk: SL = 3.5 &times; ATR(14) | PT = 4.9 &times; ATR(14)</li>
 * </ul>
 */
public class SqMnqM15Momentum04248 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MNQ";
  private static final String STRATEGY_ID = "sq-mnq-m15-momentum-0-4248";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro Nasdaq contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 100;
  private static final int M15_BARS_PER_DAY = 96;

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inTrade = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;
  private int barsInTrade = 0;
  private boolean breakEvenMoved = false;

  private Order.Side armedSide = null;
  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMnqM15Momentum04248() {
    this(DEFAULT_SYMBOL);
  }

  public SqMnqM15Momentum04248(String symbol) {
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
    return "SQ_MNQ_M15_Momentum_0_4248";
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
      barsInTrade++;
      manageOpenPosition(bar);
      return;
    }

    if (armedSide != null && armedBarsRemaining > 0) {
      if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
        executeEntry(Order.Side.BUY, quantize(bar.close()), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
        executeEntry(Order.Side.SELL, quantize(bar.close()), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    // Long Signal: High[1] > SuperTrend(24, 4.7)[3]
    double stLong = calcSuperTrend(24, 4.7, 3);
    boolean longSignal = getBar(1).high() > stLong;

    if (longSignal) {
      double lowD1 = calcLowestLow(M15_BARS_PER_DAY, 1);
      double atr30Shift3 = calcAtr(30, 3);
      double stopPrice = quantize(lowD1 - 0.50 * atr30Shift3);
      double slDist = quantize(3.0 * calcAtr(14, 0));
      double ptDist = quantize(2.2 * calcAtr(24, 0));

      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 8);
      }
    }

    // Short Signal: ROC(21)[2] crosses above 22.8 && Lowest(50)[3] < High[3]
    double rocPrev = calcRoc(21, 3);
    double rocCurr = calcRoc(21, 2);
    boolean rocCrossAbove = rocPrev <= 22.8 && rocCurr > 22.8;
    boolean shortStructure = calcLowestLow(50, 3) < getBar(3).high();

    if (rocCrossAbove && shortStructure) {
      double lowest14Shift1 = calcLowestLow(14, 1);
      double atr30Shift2 = calcAtr(30, 2);
      double stopPrice = quantize(lowest14Shift1 - 0.90 * atr30Shift2);
      double slDist = quantize(3.5 * calcAtr(14, 0));
      double ptDist = quantize(4.9 * calcAtr(14, 0));

      if (bar.close() <= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
        clearArmedOrder();
      } else if (stopPrice > 0) {
        armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, 5);
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
    this.barsInTrade = 0;
    this.breakEvenMoved = false;
  }

  private void manageOpenPosition(Bar bar) {
    if (positionSide == Order.Side.BUY) {
      if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
        closePosition();
        return;
      }
      if (!breakEvenMoved && (bar.high() - entryPrice) >= 330.0) {
        breakEvenMoved = true;
      }
      if (breakEvenMoved) {
        double addOffset = 1.5 * calcAtr(Math.min(314, history.size() - 2), 0);
        double beSl = quantize(entryPrice + addOffset);
        if (bar.low() <= beSl) {
          Order exitOrder = new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close())
              .withStrategyId(STRATEGY_ID)
              .asCloseOnly();
          pendingOrders.add(exitOrder);
          closePosition();
        }
      }
    } else if (positionSide == Order.Side.SELL) {
      if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
        closePosition();
        return;
      }
      if (barsInTrade >= 20) {
        Order exitOrder = new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close())
            .withStrategyId(STRATEGY_ID)
            .asCloseOnly();
        pendingOrders.add(exitOrder);
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
    this.breakEvenMoved = false;
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
    int effectivePeriod = Math.max(1, Math.min(period, end));
    int start = end - effectivePeriod + 1;
    if (start < 1) {
      return 15.0;
    }
    double sumTr = 0.0;
    for (int i = start; i <= end; i++) {
      Bar cur = history.get(i);
      Bar prev = history.get(i - 1);
      double tr = Math.max(cur.high() - cur.low(),
          Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
      sumTr += tr;
    }
    return sumTr / effectivePeriod;
  }

  private double calcSuperTrend(int atrPeriod, double multiplier, int shift) {
    Bar b = getBar(shift);
    double mid = (b.high() + b.low()) / 2.0;
    double atr = calcAtr(atrPeriod, shift);
    return mid - multiplier * atr;
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

