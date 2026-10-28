package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.ArrayList;
import java.util.List;

/**
 * GBP/JPY H1 Breakout ({@code SQ_GBPJPY_H1_Breakout_2_20_102}).
 *
 * <p>Backtest KPIs: Net Profit $13,703.04 (IS $11,905.79 / OOS $1,797.25 [+11.8%/yr]), Profit Factor 1.25 (IS 1.24 / OOS 1.45),
 * Ret/DD 13.79, Max DD $993.38 (9.9%), Sharpe 1.00, 3,449 trades (279 OOS).
 * Sizing: Fixed 1,000 units (Zero Martingale).</p>
 *
 * <ul>
 *   <li>{@code @sqx-source} GBPJPY BREAKOUT H1 - Dukascopy</li>
 *   <li>{@code @sqx-id} Strategy 2.20.102</li>
 *   <li>Timeframe: 1-Hour Bars (H1)</li>
 *   <li>Long Signal: KeltnerChannel(20, 2.0).Upper is falling (KC_Upper[1] &lt; KC_Upper[2])</li>
 *   <li>Long Entry: Buy Stop at BollingerBands(50, 2.1).Mid[3] (Valid 175 bars)</li>
 *   <li>Long Risk: SL = 110 pips (1.10 JPY) | PT = 380 pips (3.80 JPY)</li>
 * </ul>
 */
public class SqGbpJpyH1Breakout220102 implements Strategy {

  private static final String DEFAULT_SYMBOL = "GBP_JPY";
  private static final String STRATEGY_ID = "sq-gbpjpy-h1-breakout-2-20-102";
  private static final double QUANTITY = 1000.0; // Fixed micro-lot units (ZERO Martingale)
  private static final double PIP = 0.01;
  private static final double SL_PIPS = 110.0;
  private static final double PT_PIPS = 380.0;
  private static final int MIN_WARMUP_BARS = 55;

  private String symbol;
  private final List<Bar> history = new ArrayList<>();
  private final List<Order> pendingOrders = new ArrayList<>();

  private boolean inTrade = false;
  private Order.Side positionSide = null;
  private double entryPrice = 0.0;
  private double stopLoss = 0.0;
  private double takeProfit = 0.0;

  private double armedStopPrice = 0.0;
  private int armedBarsRemaining = 0;

  public SqGbpJpyH1Breakout220102() {
    this(DEFAULT_SYMBOL);
  }

  public SqGbpJpyH1Breakout220102(String symbol) {
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
    return "SQ_GBPJPY_H1_Breakout_2_20_102";
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
      if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
        closePosition();
      }
      return;
    }

    if (armedBarsRemaining > 0 && armedStopPrice > 0) {
      if (bar.high() >= armedStopPrice) {
        double fill = Math.max(bar.open(), armedStopPrice);
        executeLongEntry(fill);
        clearArmedOrder();
        return;
      }
      armedBarsRemaining--;
      if (armedBarsRemaining <= 0) {
        clearArmedOrder();
      }
    }

    double kcUpperCurr = calcKeltnerUpper(20, 2.0, 1);
    double kcUpperPrev = calcKeltnerUpper(20, 2.0, 2);

    if (kcUpperCurr < kcUpperPrev && kcUpperCurr > 0) {
      double stopPrice = calcSma(50, 3);
      if (bar.close() >= stopPrice && stopPrice > 0) {
        executeLongEntry(bar.close());
        clearArmedOrder();
      } else if (stopPrice > 0) {
        this.armedStopPrice = stopPrice;
        this.armedBarsRemaining = 175;
      }
    }
  }

  private void executeLongEntry(double price) {
    double sl = price - SL_PIPS * PIP;
    double tp = price + PT_PIPS * PIP;

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
  }

  private void clearArmedOrder() {
    this.armedStopPrice = 0.0;
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

  private double calcSma(int period, int shift) {
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
    return sum / n;
  }

  private double calcAtr(int period, int shift) {
    int end = history.size() - 1 - shift;
    int effectivePeriod = Math.max(1, Math.min(period, end));
    int start = end - effectivePeriod + 1;
    if (start < 1) {
      return 0.25;
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

  private double calcKeltnerUpper(int period, double mult, int shift) {
    return calcSma(period, shift) + mult * calcAtr(period, shift);
  }
}

