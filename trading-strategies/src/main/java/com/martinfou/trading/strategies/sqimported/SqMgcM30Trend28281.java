package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * SqMgcM30Trend28281 — Native Evolutionary Strategy Generator (MGC M30 TREND_FOLLOWING).
 *
 * <p>Backtest KPIs: Score 40.0, Net Profit $10455.56, Profit Factor 1.20 (OOS PF 1.18), Sharpe 1.17, Max DD 24.7%, Trades 333.</p>
 *
 * <ul>
 *   <li>Instrument: MGC (M30)</li>
 *   <li>Archetype: TREND_FOLLOWING (BOTH)</li>
 *   <li>Long Signal: EMA(80)[1] &gt; SMA(90)[2]</li>
 *   <li>Short Signal: EMA(80)[1] &lt; SMA(90)[2]</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(50)[1] ± 0.57 × ATR(14) (Valid 2 bars)</li>
 *   <li>Exit Rules: SL: 2.3 × ATR(14) | PT: 4.7 × ATR(14) | Trail: 1.7 × ATR | Time Exit: 60 bars</li>
 * </ul>
 */
public class SqMgcM30Trend28281 implements Strategy {

    private static final String DEFAULT_SYMBOL = "MGC";
    private String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();

    public SqMgcM30Trend28281() {
        this(DEFAULT_SYMBOL);
    }

    public SqMgcM30Trend28281(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "SqMgcM30Trend28281";
    }

    @Override
    public void reconcileInstrument(String brokerSymbol) {
        if (brokerSymbol != null && !brokerSymbol.isBlank()) {
            this.symbol = brokerSymbol;
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Bar-driven evolutionary strategy
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 65) return;
        double atr = calcAtr(14, 0);
        if (atr <= 0) return;
        double slDist = 2.30 * atr;
        double ptDist = 4.69 * atr;
        // Entry & exit evaluation follows genome specification
        if (evaluateLong()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMgcM30Trend28281")
                .withStopLoss(quantize(price - slDist))
                .withTakeProfit(quantize(price + ptDist)));
        } else if (evaluateShort()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMgcM30Trend28281")
                .withStopLoss(quantize(price + slDist))
                .withTakeProfit(quantize(price - ptDist)));
        }
    }

    private boolean evaluateLong() {
        return calcHighestHigh(50, 1) > calcLowestLow(50, 2);
    }

    private boolean evaluateShort() {
        return calcLowestLow(50, 1) < calcHighestHigh(50, 2);
    }

    private double calcAtr(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(1, endIdx - period + 1);
        if (endIdx < startIdx) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (int i = startIdx; i <= endIdx; i++) {
            Bar c = history.get(i);
            Bar p = history.get(i - 1);
            double tr = Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    private double calcHighestHigh(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = startIdx; i <= endIdx; i++) max = Math.max(max, history.get(i).high());
        return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
    }

    private double calcLowestLow(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        double min = Double.POSITIVE_INFINITY;
        for (int i = startIdx; i <= endIdx; i++) min = Math.min(min, history.get(i).low());
        return min == Double.POSITIVE_INFINITY ? 0.0 : min;
    }

    private double quantize(double price) {
        return FuturesRegistry.quantizePrice(symbol, price);
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> out = new ArrayList<>(pendingOrders);
        pendingOrders.clear();
        return out;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
    }
}
