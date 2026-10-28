package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro Nasdaq (MNQ) M15 Breakout ({@code SQ_MNQ_M15_Breakout_0_1499}).
 *
 * <p>Backtest KPIs (2013–2026, 2023–2026 Blind OOS): Net Profit $4,356.46 (IS $1,301.05 / OOS $3,055.40),
 * Profit Factor 1.28 (IS 1.16 / OOS 1.39), Ret/DD 5.49, Max DD $793.56 (7.9%), 1,075 trades (341 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} Institutional Suite / MNQ M15 Momentum</li>
 *   <li>{@code @sqx-id} Strategy 0.1499</li>
 *   <li>Timeframe: 15-Minute Bars (M15)</li>
 *   <li>Long Signal: Bar closes above SuperTrend(24, 6.9)[1]</li>
 *   <li>Long Entry: Stop at Highest(58)[1] + 2.30 &times; ATR(14)[2] (Valid 8 bars)</li>
 *   <li>Long Risk: SL = 3.3 &times; ATR(14) | PT = 3.6 &times; ATR(19) | TrailingStop = 1.3 &times; ATR(64)</li>
 *   <li>Short Signal: ROC(24)[1] crosses above 38.4</li>
 *   <li>Short Entry: Stop at EMA(14)[3] (Valid 2 bars)</li>
 *   <li>Short Risk: SL = 2.1 &times; ATR(19) | PT = 3.5 &times; ATR(24)</li>
 * </ul>
 */
public class SqMnqM15Breakout01499 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MNQ";
  private static final String STRATEGY_ID = "sq-mnq-m15-breakout-0-1499";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro Nasdaq contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 70;

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

  public SqMnqM15Breakout01499() {
    this(DEFAULT_SYMBOL);
  }

  public SqMnqM15Breakout01499(String symbol) {
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
    return "SQ_MNQ_M15_Breakout_0_1499";
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

    // Long Signal: Bar closes above SuperTrend(24, 6.9)[1]
    double stBand = calcSuperTrendUpper(24, 6.9, 1);
    if (getBar(1).close() > stBand && armedSide != Order.Side.BUY) {
      double highest58 = calcHighestHigh(58, 1);
      double atr14Shift2 = calcAtr(14, 2);
      double stopPrice = quantize(highest58 + 2.30 * atr14Shift2);
      double slDist = quantize(3.3 * calcAtr(14, 0));
      double ptDist = quantize(3.6 * calcAtr(19, 0));

      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
        clearArmedOrder();
        return;
      } else if (stopPrice > 0) {
        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 8);
      }
    }

    // Short Signal: ROC(24)[1] crosses above 38.4
    double rocPrev = calcRoc(24, 2);
    double rocCurr = calcRoc(24, 1);
    if (rocPrev <= 38.4 && rocCurr > 38.4) {
      double stopPrice = quantize(calcEma(14, 3));
      double slDist = quantize(2.1 * calcAtr(19, 0));
      double ptDist = quantize(3.5 * calcAtr(24, 0));

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
    this.barsInTrade = 0;
  }

  private void manageOpenPosition(Bar bar) {
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

  private double calcSuperTrendUpper(int atrPeriod, double multiplier, int shift) {
    Bar b = getBar(shift + 1);
    double mid = (b.high() + b.low()) / 2.0;
    return mid - multiplier * calcAtr(atrPeriod, shift + 1);
  }

  private double calcEma(int period, int shift) {
    int end = history.size() - 1 - shift;
    int start = Math.max(0, end - period * 2);
    if (end < 0) {
      return 0.0;
    }
    double k = 2.0 / (period + 1.0);
    double ema = history.get(start).close();
    for (int i = start + 1; i <= end; i++) {
      ema = history.get(i).close() * k + ema * (1.0 - k);
    }
    return ema;
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

