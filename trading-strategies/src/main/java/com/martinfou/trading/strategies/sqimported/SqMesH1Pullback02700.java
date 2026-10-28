package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro S&amp;P 500 (MES) H1 Limit Pullback ({@code SQ_MES_H1_Pullback_0_2700}).
 *
 * <p>Backtest KPIs (2013–2026, 2023–2026 Blind OOS): Net Profit $2,001.44 (IS $427.60 / OOS $1,573.84),
 * Profit Factor 1.20 (IS 1.07 / OOS 1.40), Ret/DD 5.20, Max DD $385.18 (3.9%), 1,027 trades (323 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} Institutional Suite / MES H1 Long</li>
 *   <li>{@code @sqx-id} Strategy 0.2700</li>
 *   <li>Timeframe: 1-Hour Bars (H1)</li>
 *   <li>Long Signal: Bar closes below SuperTrend(480, 4.7)[1]</li>
 *   <li>Long Entry: Limit Pullback at LowD[1] - 1.40 &times; ATR(30)[1] (Valid 3 bars)</li>
 *   <li>Long Risk: SL = 2.1 &times; ATR(14) | PT = 4.1 &times; ATR(24) | TrailingStop = 2.5 &times; ATR(189) (Activation 2.1 &times; ATR(99))</li>
 * </ul>
 */
public class SqMesH1Pullback02700 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MES";
  private static final String STRATEGY_ID = "sq-mes-h1-pullback-0-2700";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro S&P 500 contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 50;
  private static final int H1_BARS_PER_DAY = 24;

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inTrade = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;
  private int barsInTrade = 0;

  private double armedLimitPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMesH1Pullback02700() {
    this(DEFAULT_SYMBOL);
  }

  public SqMesH1Pullback02700(String symbol) {
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
    return "SQ_MES_H1_Pullback_0_2700";
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
      barsInTrade++;
      manageOpenPosition(bar);
      return;
    }

    if (armedBarsRemaining > 0 && armedLimitPrice > 0) {
      if (bar.low() <= armedLimitPrice) {
        double fill = Math.min(bar.open(), armedLimitPrice);
        executeLongEntry(quantize(fill), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    double stUpper = calcSuperTrendUpper(480, 4.7, 1);
    if (getBar(1).close() < stUpper) {
      double lowD1 = calcLowestLow(H1_BARS_PER_DAY, 1);
      double atr30Shift1 = calcAtr(30, 1);
      double limitPrice = quantize(lowD1 - 1.40 * atr30Shift1);
      double slDist = quantize(2.1 * calcAtr(14, 0));
      double ptDist = quantize(4.1 * calcAtr(24, 0));

      if (bar.low() <= limitPrice && limitPrice > 0) {
        executeLongEntry(bar.close(), slDist, ptDist);
        clearArmedOrder();
      } else if (limitPrice > 0) {
        this.armedLimitPrice = limitPrice;
        this.armedSlDist = slDist;
        this.armedPtDist = ptDist;
        this.armedBarsRemaining = 3;
      }
    }
  }

  private void executeLongEntry(double price, double slDist, double ptDist) {
    double safeSlDist = Math.max(slDist, 0.50);
    double safePtDist = Math.max(ptDist, 0.50);
    double sl = quantize(price - safeSlDist);
    double tp = quantize(price + safePtDist);

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
    this.barsInTrade = 0;
  }

  private void manageOpenPosition(Bar bar) {
    double activation = 2.1 * calcAtr(99, 0);
    double trailDist = 2.5 * calcAtr(189, 0);
    if (activation > 0 && trailDist > 0 && (bar.high() - entryPrice) >= activation) {
      double candidateSl = quantize(bar.close() - trailDist);
      if (candidateSl > stopLoss) {
        stopLoss = candidateSl;
      }
    }

    if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
      closePosition();
    }
  }

  private void clearArmedOrder() {
    this.armedLimitPrice = 0.0;
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
      return 5.0;
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
    Bar b = getBar(shift);
    double mid = (b.high() + b.low()) / 2.0;
    return mid + multiplier * calcAtr(atrPeriod, shift);
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}

