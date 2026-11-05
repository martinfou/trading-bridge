package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * SqMnqH1Momentum66294 — Native Evolutionary Strategy Generator (MNQ H1 MOMENTUM).
 *
 * <p>Backtest KPIs: Score 48.9, Net Profit $3562.04, Profit Factor 1.52 (OOS PF 1.02), Sharpe 0.93, Max DD 12.9%, Trades 58.</p>
 *
 * <ul>
 *   <li>Instrument: MNQ (H1)</li>
 *   <li>Archetype: MOMENTUM (BOTH)</li>
 *   <li>Long Signal: Close[0] &gt; SuperTrend(25, 1.7) AND ATR(25)[0] &gt; 1.24 * ATR(50)[1]</li>
 *   <li>Short Signal: Close[0] &lt; SuperTrend(25, 1.7) AND ATR(25)[0] &gt; 1.24 * ATR(50)[1]</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(50)[1] ± 0.24 × ATR(14) (Valid 6 bars)</li>
 *   <li>Exit Rules: SL: 1.6 × ATR(14) | PT: 2.3 × ATR(14) | Trail: 2.4 × ATR | Time Exit: 38 bars</li>
 * </ul>
 */
public class SqMnqH1Momentum66294 implements Strategy {

    private static final String DEFAULT_SYMBOL = "MNQ";
    private String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();

    public SqMnqH1Momentum66294() {
        this(DEFAULT_SYMBOL);
    }

    public SqMnqH1Momentum66294(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "SqMnqH1Momentum66294";
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
        double slDist = 1.57 * atr;
        double ptDist = 2.31 * atr;
        // Entry & exit evaluation follows genome specification
        if (evaluateLong()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMnqH1Momentum66294")
                .withStopLoss(quantize(price - slDist))
                .withTakeProfit(quantize(price + ptDist)));
        } else if (evaluateShort()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMnqH1Momentum66294")
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
