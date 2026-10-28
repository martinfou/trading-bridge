package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro S&amp;P 500 (MES) H1 Mean Reversion / Regime ({@code SQ_MES_H1_MeanRev_0_3553}).
 *
 * <p>Backtest KPIs (2013–2026, 2023–2026 Blind OOS): Net Profit $1,378.10 (IS $714.25 / OOS $663.85),
 * Profit Factor 1.38 (IS 1.30 / OOS 1.51), Ret/DD 6.52, Max DD $211.36 (2.1%), 379 trades (108 OOS).
 * Sizing: Strict Fixed 1 Micro Contract (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} Institutional Suite / MES H1 Long</li>
 *   <li>{@code @sqx-id} Strategy 0.3553</li>
 *   <li>Timeframe: 1-Hour Bars (H1)</li>
 *   <li>Long Signal: ADX(40)[s] &lt; RSI(20)[s] for 8 consecutive bars (s = 1..8)</li>
 *   <li>Long Entry: Stop at Lowest(14)[2] - 2.80 &times; ATR(387)[3] (Valid 3 bars)</li>
 *   <li>Long Risk: SL = 2.7 &times; ATR(14) | PT = 2.9 &times; ATR(14) | MoveSL2BE = 1.5 &times; ATR(149) (+1.9 &times; ATR(389))</li>
 * </ul>
 */
public class SqMesH1MeanRev03553 implements Strategy {

  private static final String DEFAULT_SYMBOL = "MES";
  private static final String STRATEGY_ID = "sq-mes-h1-meanrev-0-3553";
  private static final double QUANTITY = 1.0; // Strictly 1 Micro S&P 500 contract (ZERO Martingale)
  private static final int MIN_WARMUP_BARS = 60;

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

  private double armedStopPrice = 0.0;
  private double armedSlDist = 0.0;
  private double armedPtDist = 0.0;
  private int armedBarsRemaining = 0;

  public SqMesH1MeanRev03553() {
    this(DEFAULT_SYMBOL);
  }

  public SqMesH1MeanRev03553(String symbol) {
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
    return "SQ_MES_H1_MeanRev_0_3553";
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

    if (armedBarsRemaining > 0 && armedStopPrice > 0) {
      if (bar.high() >= armedStopPrice) {
        double fill = Math.max(bar.open(), armedStopPrice);
        executeLongEntry(quantize(fill), armedSlDist, armedPtDist);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    boolean adxBelowRsi8Bars = true;
    for (int s = 1; s <= 8; s++) {
      if (calcAdx(40, s) >= calcRsi(20, s)) {
        adxBelowRsi8Bars = false;
        break;
      }
    }

    if (adxBelowRsi8Bars) {
      double lowest14Shift2 = calcLowestLow(14, 2);
      double atr387Shift3 = calcAtr(387, 3);
      double stopPrice = quantize(lowest14Shift2 - 2.80 * atr387Shift3);
      double slDist = quantize(2.7 * calcAtr(14, 0));
      double ptDist = quantize(2.9 * calcAtr(14, 0));

      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeLongEntry(bar.close(), slDist, ptDist);
        clearArmedOrder();
      } else if (stopPrice > 0) {
        this.armedStopPrice = stopPrice;
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
    this.breakEvenMoved = false;
  }

  private void manageOpenPosition(Bar bar) {
    double beTrigger = 1.5 * calcAtr(149, 0);
    if (!breakEvenMoved && beTrigger > 0 && (bar.high() - entryPrice) >= beTrigger) {
      double beAdd = 1.9 * calcAtr(389, 0);
      double newSl = quantize(entryPrice + beAdd);
      if (newSl > stopLoss && newSl < bar.close()) {
        stopLoss = newSl;
      }
      breakEvenMoved = true;
    }

    if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
      closePosition();
    }
  }

  private void clearArmedOrder() {
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

  private double calcRsi(int period, int shift) {
    int end = history.size() - 1 - shift;
    int effectivePeriod = Math.max(1, Math.min(period, end));
    int start = end - effectivePeriod + 1;
    if (start < 1) {
      return 50.0;
    }
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
    double rs = (gains / effectivePeriod) / (losses / effectivePeriod);
    return 100.0 - (100.0 / (1.0 + rs));
  }

  private double calcAdx(int period, int shift) {
    int end = history.size() - 1 - shift;
    int effectivePeriod = Math.max(1, Math.min(period, end));
    int start = end - effectivePeriod + 1;
    if (start < 1) {
      return 20.0;
    }
    double sumPlusDm = 0.0;
    double sumMinusDm = 0.0;
    double sumTr = 0.0;
    for (int i = start; i <= end; i++) {
      Bar cur = history.get(i);
      Bar prev = history.get(i - 1);
      double upMove = cur.high() - prev.high();
      double downMove = prev.low() - cur.low();
      if (upMove > downMove && upMove > 0) {
        sumPlusDm += upMove;
      }
      if (downMove > upMove && downMove > 0) {
        sumMinusDm += downMove;
      }
      double tr = Math.max(cur.high() - cur.low(),
          Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
      sumTr += tr;
    }
    if (sumTr == 0.0) {
      return 0.0;
    }
    double plusDi = 100.0 * (sumPlusDm / sumTr);
    double minusDi = 100.0 * (sumMinusDm / sumTr);
    double denom = plusDi + minusDi;
    return denom == 0.0 ? 0.0 : 100.0 * Math.abs(plusDi - minusDi) / denom;
  }

  private double quantize(double price) {
    return FuturesRegistry.quantizePrice(symbol, price);
  }
}

