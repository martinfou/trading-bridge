package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * SqMgcM30MeanRev50426 — Native Evolutionary Strategy Generator (MGC M30 MEAN_REVERSION).
 *
 * <p>Backtest KPIs: Score 74.0, Net Profit $1910.64, Profit Factor 1.73 (OOS PF 1.90), Sharpe 0.55, Max DD 8.4%, Trades 28.</p>
 *
 * <ul>
 *   <li>Instrument: MGC (M30)</li>
 *   <li>Archetype: MEAN_REVERSION (BOTH)</li>
 *   <li>Long Signal: RSI(100)[2] &lt; 40 (Oversold)</li>
 *   <li>Short Signal: RSI(100)[2] &gt; 60 (Overbought)</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(50)[1] ± 0.54 × ATR(20) (Valid 7 bars)</li>
 *   <li>Exit Rules: SL: 2.4 × ATR(20) | PT: 3.7 × ATR(20) | Time Exit: 62 bars</li>
 * </ul>
 */
public class SqMgcM30MeanRev50426 implements Strategy {

    private static final String DEFAULT_SYMBOL = "MGC";
    private String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side positionSide = null;
    private double stopLoss = 0.0;
    private double takeProfit = 0.0;
    private int barsInTrade = 0;

    private Order.Side armedSide = null;
    private double armedStopPrice = 0.0;
    private double armedSlDist = 0.0;
    private double armedPtDist = 0.0;
    private int armedBarsRemaining = 0;

    public SqMgcM30MeanRev50426() {
        this(DEFAULT_SYMBOL);
    }

    public SqMgcM30MeanRev50426(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "SqMgcM30MeanRev50426";
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
        if (history.size() < 61) return;
        double atr = calcAtr(20, 0);
        if (atr <= 0) return;

        if (inTrade) {
            barsInTrade++;
            if (positionSide == Order.Side.BUY && (bar.low() <= stopLoss || bar.high() >= takeProfit)) {
                closePosition(bar);
                return;
            } else if (positionSide == Order.Side.SELL && (bar.high() >= stopLoss || bar.low() <= takeProfit)) {
                closePosition(bar);
                return;
            }
            if (barsInTrade >= 62) {
                closePosition(bar);
                return;
            }
            return;
        }

        if (armedSide != null && armedBarsRemaining > 0) {
            if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
                executeEntry(Order.Side.BUY, bar.close(), armedSlDist, armedPtDist);
                clearArmed();
                return;
            } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
                executeEntry(Order.Side.SELL, bar.close(), armedSlDist, armedPtDist);
                clearArmed();
                return;
            }
            armedBarsRemaining--;
            if (armedBarsRemaining <= 0) clearArmed();
        }

        double slDist = quantize(2.40 * atr);
        double ptDist = quantize(3.70 * atr);

        if (evaluateLong()) {
            double hh = calcHighestHigh(50, 1);
            double stopPrice = quantize(hh + 0.54 * atr);
            if (bar.close() >= stopPrice && stopPrice > 0) {
                executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
                clearArmed();
                return;
            } else if (stopPrice > 0) {
                armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 7);
            }
        }
        if (evaluateShort()) {
            double ll = calcLowestLow(50, 1);
            double stopPrice = quantize(ll - 0.54 * atr);
            if (bar.close() <= stopPrice && stopPrice > 0) {
                executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
                clearArmed();
            } else if (stopPrice > 0) {
                armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, 7);
            }
        }
    }

    private void executeEntry(Order.Side side, double price, double slDist, double ptDist) {
        double minOffset = Math.max(price * 0.0003, 0.0001);
        double safeSl = Math.max(slDist, minOffset);
        double safePt = Math.max(ptDist, minOffset);
        double sl = side == Order.Side.BUY ? quantize(price - safeSl) : quantize(price + safeSl);
        double tp = side == Order.Side.BUY ? quantize(price + safePt) : quantize(price - safePt);
        pendingOrders.add(new Order(symbol, side, Order.Type.MARKET, 1.0, price)
            .withStrategyId("SqMgcM30MeanRev50426")
            .withStopLoss(sl)
            .withTakeProfit(tp));
        this.inTrade = true;
        this.positionSide = side;
        this.stopLoss = sl;
        this.takeProfit = tp;
        this.barsInTrade = 0;
    }

    private void closePosition(Bar bar) {
        if (!inTrade || positionSide == null) return;
        Order.Side exitSide = positionSide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pendingOrders.add(new Order(symbol, exitSide, Order.Type.MARKET, 1.0, bar.close())
            .withStrategyId("SqMgcM30MeanRev50426")
            .asCloseOnly());
        this.inTrade = false;
        this.positionSide = null;
        this.barsInTrade = 0;
    }

    private void armOrder(Order.Side side, double stopPrice, double slDist, double ptDist, int validBars) {
        this.armedSide = side;
        this.armedStopPrice = stopPrice;
        this.armedSlDist = slDist;
        this.armedPtDist = ptDist;
        this.armedBarsRemaining = Math.max(1, validBars);
    }

    private void clearArmed() {
        this.armedSide = null;
        this.armedStopPrice = 0.0;
        this.armedSlDist = 0.0;
        this.armedPtDist = 0.0;
        this.armedBarsRemaining = 0;
    }

    private boolean evaluateLong() {
        return (calcRsi(100, 2) < 40.0);
    }

    private boolean evaluateShort() {
        return (calcRsi(100, 2) > 60.0);
    }

    private double getClose(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return history.get(idx).close();
    }

    private double calcSma(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        if (endIdx < startIdx) return 0.0;
        double sum = 0.0;
        for (int i = startIdx; i <= endIdx; i++) sum += history.get(i).close();
        return sum / (endIdx - startIdx + 1);
    }

    private double calcEma(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period * 2);
        if (endIdx < startIdx) return 0.0;
        double k = 2.0 / (period + 1.0);
        double ema = history.get(startIdx).close();
        for (int i = startIdx + 1; i <= endIdx; i++) ema = (history.get(i).close() - ema) * k + ema;
        return ema;
    }

    private double calcRsi(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(1, endIdx - period + 1);
        if (endIdx < startIdx) return 50.0;
        double gains = 0.0, losses = 0.0;
        for (int i = startIdx; i <= endIdx; i++) {
            double diff = history.get(i).close() - history.get(i - 1).close();
            if (diff > 0) gains += diff; else losses -= diff;
        }
        if (losses == 0.0) return 100.0;
        return 100.0 - (100.0 / (1.0 + gains / losses));
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
        inTrade = false;
        positionSide = null;
        stopLoss = 0.0;
        takeProfit = 0.0;
        barsInTrade = 0;
        clearArmed();
    }
}
