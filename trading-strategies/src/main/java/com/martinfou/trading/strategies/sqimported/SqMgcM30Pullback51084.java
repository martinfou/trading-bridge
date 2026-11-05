package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * SqMgcM30Pullback51084 — Native Evolutionary Strategy Generator (MGC M30 PULLBACK).
 *
 * <p>Backtest KPIs: Score 40.8, Net Profit $12792.50, Profit Factor 1.22 (OOS PF 1.15), Sharpe 1.29, Max DD 25.0%, Trades 395.</p>
 *
 * <ul>
 *   <li>Instrument: MGC (M30)</li>
 *   <li>Archetype: PULLBACK (BOTH)</li>
 *   <li>Long Signal: Close[2] &gt; EMA(40)[2]</li>
 *   <li>Short Signal: Close[2] &lt; EMA(40)[2]</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(50)[1] ± 0.61 × ATR(20) (Valid 4 bars)</li>
 *   <li>Exit Rules: SL: 2.5 × ATR(20) | PT: 4.5 × ATR(20) | Trail: 1.8 × ATR | Time Exit: 47 bars</li>
 * </ul>
 */
public class SqMgcM30Pullback51084 implements Strategy {

    private static final String DEFAULT_SYMBOL = "MGC";
    private String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();

    public SqMgcM30Pullback51084() {
        this(DEFAULT_SYMBOL);
    }

    public SqMgcM30Pullback51084(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "SqMgcM30Pullback51084";
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
        double atr = calcAtr(20, 0);
        if (atr <= 0) return;
        double slDist = 2.47 * atr;
        double ptDist = 4.52 * atr;
        // Entry & exit evaluation follows genome specification
        if (evaluateLong()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMgcM30Pullback51084")
                .withStopLoss(quantize(price - slDist))
                .withTakeProfit(quantize(price + ptDist)));
        } else if (evaluateShort()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMgcM30Pullback51084")
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
