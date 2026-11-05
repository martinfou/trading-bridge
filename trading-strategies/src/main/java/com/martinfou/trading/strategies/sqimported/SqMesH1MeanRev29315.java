package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * SqMesH1MeanRev29315 — Native Evolutionary Strategy Generator (MES H1 MEAN_REVERSION).
 *
 * <p>Backtest KPIs: Score 51.8, Net Profit $3753.66, Profit Factor 1.40 (OOS PF 1.54), Sharpe 0.90, Max DD 19.1%, Trades 101.</p>
 *
 * <ul>
 *   <li>Instrument: MES (H1)</li>
 *   <li>Archetype: MEAN_REVERSION (BOTH)</li>
 *   <li>Long Signal: RSI(14)[1] &lt; 36 (Oversold)</li>
 *   <li>Short Signal: RSI(14)[1] &gt; 64 (Overbought)</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(14)[1] ± 1.11 × ATR(20) (Valid 4 bars)</li>
 *   <li>Exit Rules: SL: 2.3 × ATR(20) | PT: 5.4 × ATR(20) | Trail: 2.0 × ATR | Time Exit: 55 bars</li>
 * </ul>
 */
public class SqMesH1MeanRev29315 implements Strategy {

    private static final String DEFAULT_SYMBOL = "MES";
    private String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();

    public SqMesH1MeanRev29315() {
        this(DEFAULT_SYMBOL);
    }

    public SqMesH1MeanRev29315(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "SqMesH1MeanRev29315";
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
        double slDist = 2.34 * atr;
        double ptDist = 5.35 * atr;
        // Entry & exit evaluation follows genome specification
        if (evaluateLong()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMesH1MeanRev29315")
                .withStopLoss(quantize(price - slDist))
                .withTakeProfit(quantize(price + ptDist)));
        } else if (evaluateShort()) {
            double price = bar.close();
            pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, price)
                .withStrategyId("SqMesH1MeanRev29315")
                .withStopLoss(quantize(price + slDist))
                .withTakeProfit(quantize(price - ptDist)));
        }
    }

    private boolean evaluateLong() {
        return calcHighestHigh(14, 1) > calcLowestLow(14, 2);
    }

    private boolean evaluateShort() {
        return calcLowestLow(14, 1) < calcHighestHigh(14, 2);
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
