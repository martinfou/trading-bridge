package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Keltner Channel Mean Reversion Strategy — Mean Reversion + Volatility
 *
 * 📊 Data insight: When price touches or exceeds the outer Keltner Channel
 *    (EMA(20) ± 2×ATR), the market is statistically extended. These levels
 *    act as dynamic support/resistance. Price tends to revert toward the
 *    middle EMA within 2-3 bars, offering a mean reversion opportunity.
 *
 * 🔧 Mechanism: Keltner channel touch → mean reversion trade.
 *    - Keltner bands: middle = EMA(20), upper = EMA + 2×ATR, lower = EMA - 2×ATR
 *    - Price touches/exceeds upper band → SELL (reversion to middle)
 *    - Price touches/goes below lower band → BUY (reversion to middle)
 *    - Only trade if the touch is sharp (body at or beyond band)
 *    - Target: middle EMA(20). Stop: beyond outer band + 0.5×ATR
 *
 * 🎯 Originality: Unlike Bollinger Bands (statistical volatility), Keltner
 *    uses ATR which adapts better to each asset's true volatility profile.
 *    The reversion-to-EMA mechanism is simpler and more robust across
 *    different FX pairs with varying volatility characteristics.
 */
public class KeltnerChannelReversalStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double targetPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 10000;

    public KeltnerChannelReversalStrategy() {
        this("KeltnerChannelReversal", "GBP/JPY");
    }

    public KeltnerChannelReversalStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public KeltnerChannelReversalStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        double atr = calculateATR(14);
        double ema20 = calculateEMA(20);
        double upperBand = ema20 + 2.0 * atr;
        double lowerBand = ema20 - 2.0 * atr;

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            // Exit: reached target (middle EMA) or max bars
            if (barsHeld >= 4) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                // Target is middle EMA or better
                if (bar.high() >= targetPrice) { closePosition(bar); return; }
                // Stop beyond lower band
                if (bar.low() <= lowerBand - atr * 0.3) { closePosition(bar); return; }
            } else {
                if (bar.low() <= targetPrice) { closePosition(bar); return; }
                if (bar.high() >= upperBand + atr * 0.3) { closePosition(bar); return; }
            }
            return;
        }

        // Check for upper band touch → SELL (mean reversion down to EMA)
        if (bar.high() >= upperBand && bar.close() < upperBand + atr * 0.2) {
            if (bar.close() < bar.open() || (bar.close() > bar.open() && bar.close() < upperBand)) {
                // Body close to or beyond upper band → reversal likely
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                targetPrice = ema20; // Target: middle EMA
                barsHeld = 0;
                return;
            }
        }

        // Check for lower band touch → BUY (mean reversion up to EMA)
        if (bar.low() <= lowerBand && bar.close() > lowerBand - atr * 0.2) {
            if (bar.close() > bar.open() || (bar.close() < bar.open() && bar.close() > lowerBand)) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                targetPrice = ema20; // Target: middle EMA
                barsHeld = 0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double calculateEMA(int period) {
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++) {
            ema = history.get(i).close() * k + ema * (1 - k);
        }
        return ema;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0;
        targetPrice = 0; barsHeld = 0;
    }
}
