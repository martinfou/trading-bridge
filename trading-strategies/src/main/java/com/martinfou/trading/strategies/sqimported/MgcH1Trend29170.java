package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * MgcH1Trend29170 — Native Evolutionary Strategy Generator (MGC H1 TREND_FOLLOWING).
 *
 * <p>Backtest KPIs: Score 96.0, Net Profit $25932.56, Profit Factor 2.27 (OOS PF 2.94), Sharpe 1.96, Max DD 10.1%, Trades 206.</p>
 *
 * <ul>
 *   <li>Instrument: MGC (H1)</li>
 *   <li>Archetype: TREND_FOLLOWING (BOTH)</li>
 *   <li>Long Signal: MACD(14, 50)[2] &gt; 0 & Rising</li>
 *   <li>Short Signal: MACD(14, 50)[2] &lt; 0 & Falling</li>
 *   <li>Entry Type: Stop Breakout at Highest/Lowest(50)[1] ± 0.67 × ATR(14) (Valid 4 bars)</li>
 *   <li>Exit Rules: SL: 1.5 × ATR(14) | PT: 3.7 × ATR(14) | Trail: 3.0 × ATR | Time Exit: 21 bars</li>
 * </ul>
 */
public class MgcH1Trend29170 implements Strategy {

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

    public MgcH1Trend29170() {
        this(DEFAULT_SYMBOL);
    }

    public MgcH1Trend29170(String symbol) {
        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;
    }

    @Override
    public String name() {
        return "MgcH1Trend29170";
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
        double atr = calcAtr(14, 0);
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
            if (barsInTrade >= 21) {
                closePosition(bar);
                return;
            }
            double trailDist = 2.98 * atr;
            if (positionSide == Order.Side.BUY) {
                stopLoss = Math.max(stopLoss, quantize(bar.close() - trailDist));
            } else if (positionSide == Order.Side.SELL) {
                stopLoss = Math.min(stopLoss, quantize(bar.close() + trailDist));
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

        double slDist = quantize(1.47 * atr);
        double ptDist = quantize(3.68 * atr);

        if (evaluateLong()) {
            double hh = calcHighestHigh(50, 1);
            double stopPrice = quantize(hh + 0.67 * atr);
            if (bar.close() >= stopPrice && stopPrice > 0) {
                executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
                clearArmed();
                return;
            } else if (stopPrice > 0) {
                armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, 4);
            }
        }
        if (evaluateShort()) {
            double ll = calcLowestLow(50, 1);
            double stopPrice = quantize(ll - 0.67 * atr);
            if (bar.close() <= stopPrice && stopPrice > 0) {
                executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
                clearArmed();
            } else if (stopPrice > 0) {
                armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, 4);
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
            .withStrategyId("MgcH1Trend29170")
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
            .withStrategyId("MgcH1Trend29170")
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
        return ((calcEma(14, 2) - calcEma(50, 2)) > 0 && (calcEma(14, 2) - calcEma(50, 2)) > (calcEma(14, 3) - calcEma(50, 3)));
    }

    private boolean evaluateShort() {
        return ((calcEma(14, 2) - calcEma(50, 2)) < 0 && (calcEma(14, 2) - calcEma(50, 2)) < (calcEma(14, 3) - calcEma(50, 3)));
    }

    private double getClose(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return history.get(idx).close();
    }

    private double getOpen(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return history.get(idx).open();
    }

    private double getHigh(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return history.get(idx).high();
    }

    private double getLow(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return history.get(idx).low();
    }

    private double getVolume(int shift) {
        int idx = Math.max(0, history.size() - 1 - shift);
        return Math.max(1.0, history.get(idx).volume());
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

    private double calcStdDev(int period, int shift, double mean) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        if (endIdx <= startIdx) return 0.0;
        double sumSq = 0.0;
        for (int i = startIdx; i <= endIdx; i++) { double d = history.get(i).close() - mean; sumSq += d * d; }
        return Math.sqrt(sumSq / (endIdx - startIdx + 1));
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

    private double calcStochK(int period, int shift) {
        double hh = calcHighestHigh(period, shift);
        double ll = calcLowestLow(period, shift);
        if (hh <= ll) return 50.0;
        return ((getClose(shift) - ll) / (hh - ll)) * 100.0;
    }

    private double calcCci(int period, int shift) {
        double mean = calcSma(period, shift);
        double sd = calcStdDev(period, shift, mean);
        if (sd <= 0.0) return 0.0;
        return (getClose(shift) - mean) / (0.015 * sd);
    }

    private double calcVwap(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        if (endIdx < startIdx) return getClose(shift);
        double pv = 0.0, vol = 0.0;
        for (int i = startIdx; i <= endIdx; i++) {
            Bar b = history.get(i);
            double v = Math.max(1.0, b.volume());
            double typ = (b.high() + b.low() + b.close()) / 3.0;
            pv += typ * v;
            vol += v;
        }
        return vol > 0 ? pv / vol : getClose(shift);
    }

    private double calcAvgVolume(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - period + 1);
        if (endIdx < startIdx) return 1.0;
        double sum = 0.0;
        for (int i = startIdx; i <= endIdx; i++) sum += Math.max(1.0, history.get(i).volume());
        return sum / (endIdx - startIdx + 1);
    }

    private double calcLinRegSlope(int period, int shift) {
        int n = Math.max(3, period);
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(0, endIdx - n + 1);
        int count = endIdx - startIdx + 1;
        if (count < 3) return 0.0;
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
        for (int i = 0; i < count; i++) {
            double y = history.get(startIdx + i).close();
            sumX += i; sumY += y; sumXY += i * y; sumX2 += (double) i * i;
        }
        double denom = count * sumX2 - sumX * sumX;
        return Math.abs(denom) > 1e-9 ? (count * sumXY - sumX * sumY) / denom : 0.0;
    }

    private double calcEfficiencyRatio(int period, int shift) {
        int endIdx = history.size() - 1 - shift;
        int startIdx = Math.max(1, endIdx - period + 1);
        if (endIdx <= startIdx) return 0.5;
        double net = Math.abs(history.get(endIdx).close() - history.get(startIdx - 1).close());
        double path = 0.0;
        for (int i = startIdx; i <= endIdx; i++) {
            path += Math.abs(history.get(i).close() - history.get(i - 1).close());
        }
        return path > 0.0 ? net / path : 0.0;
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
