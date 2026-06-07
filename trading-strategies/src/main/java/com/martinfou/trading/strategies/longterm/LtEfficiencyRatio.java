package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtEfficiencyRatio — Kaufman Efficiency Ratio Trend Strategy
 *
 * 📊 Concept: Only trade when the market is efficiently trending.
 *    Kaufman's Efficiency Ratio = net direction / total volatility
 *    ER = |close - close[n]| / sum(|close[i] - close[i-1]|)
 *
 *    When ER > 0.6 → strong trend → enter in direction of trend
 *    When ER < 0.3 → choppy → stay out (avoid whipsaws)
 *    Position sizing scales with ER: higher efficiency = bigger position
 *
 * 🔧 Inspiration:
 *    - Perry Kaufman (inventor of ER/KAMA)
 *    - Ed Seykota (trend following philosophy)
 *    - FTMO risk management (drawdown limits, position sizing)
 *
 * 🧪 Why long term:
 *    - Concept is structural: trending markets are persistent
 *    - Naturally avoids choppy/flat markets (killer of trend strats)
 *    - Works across ALL timeframes and ALL assets
 *    - No parameter is date-sensitive
 */
public class LtEfficiencyRatio implements Strategy {

    private static final int ER_PERIOD = 20;        // Lookback for efficiency calc
    private static final double ER_ENTRY = 0.5;      // Min ER to enter
    private static final double ER_EXIT = 0.25;      // Exit when ER drops below
    private static final double ATR_MULT_SL = 2.0;   // Stop loss in ATR
    private static final double ATR_MULT_TP = 3.0;   // Take profit in ATR
    private static final double MAX_POSITION = 2000; // Max micro lots

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side direction = Order.Side.BUY;
    private double entryPrice = 0;
    private double entrySl = 0;
    private double entryTp = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;
    private int consecutiveLosses = 0;

    public LtEfficiencyRatio() { this("LtEfficiencyRatio", "EUR_USD"); }
    public LtEfficiencyRatio(String name) { this(name, "EUR_USD"); }
    public LtEfficiencyRatio(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String getName() { return name; }
    @Override public String getSymbol() { return symbol; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < ER_PERIOD + 1) return;

        // Efficiency Ratio
        double er = calcEfficiencyRatio(ER_PERIOD);
        double atr = Indicators.atr(history, 14);
        if (Double.isNaN(er) || Double.isNaN(atr) || atr == 0) return;

        double close = bar.close();
        double pipValue = Indicators.pipSize(symbol);

        // Day tracking
        int dayKey = bar.timestamp().getDayOfYear();
        if (dayKey != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = dayKey;
        }

        if (inTrade) {
            // Check trailing exit — exit when ER drops below threshold
            if (er < ER_EXIT || consecutiveLosses >= 2) {
                exitTrade(close);
                return;
            }
            // Check hard SL/TP
            if (direction == Order.Side.BUY && close <= entrySl) {
                consecutiveLosses++;
                exitTrade(entrySl);
                return;
            }
            if (direction == Order.Side.SELL && close >= entrySl) {
                consecutiveLosses++;
                exitTrade(entrySl);
                return;
            }
            // Trailing: ratchet stop higher for longs, lower for shorts
            if (direction == Order.Side.BUY && close > entryPrice) {
                double trailStop = close - atr * ATR_MULT_SL;
                if (trailStop > entrySl) entrySl = trailStop;
            }
            if (direction == Order.Side.SELL && close < entryPrice) {
                double trailStop = close + atr * ATR_MULT_SL;
                if (trailStop < entrySl) entrySl = trailStop;
            }
            return;
        }

        // Entry logic: only when efficiency is high
        if (er < ER_ENTRY || tradesToday >= 1) return;

        // Direction: price vs EMA(50)
        double ema50 = Indicators.emaLatest(history, 50);
        if (Double.isNaN(ema50)) return;

        // Scale position by ER: at ER=0.5 → 1000u, at ER=0.8 → 2000u
        double positionScale = Math.min(1.0, Math.max(0.5, (er - 0.3) / 0.5));
        double qty = Math.round(MAX_POSITION * positionScale / 100) * 100;

        if (close > ema50) {
            // Long
            double sl = close - atr * ATR_MULT_SL;
            double tp = close + atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, close)
                .withStopLoss(sl).withTakeProfit(tp));
            entryPrice = close;
            entrySl = sl;
            entryTp = tp;
            direction = Order.Side.BUY;
            inTrade = true;
            tradesToday++;
        } else if (close < ema50) {
            // Short
            double sl = close + atr * ATR_MULT_SL;
            double tp = close - atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, qty, close)
                .withStopLoss(sl).withTakeProfit(tp));
            entryPrice = close;
            entrySl = sl;
            entryTp = tp;
            direction = Order.Side.SELL;
            inTrade = true;
            tradesToday++;
        }
    }

    private void exitTrade(double price) {
        Order.Side exitSide = direction == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }

    private double calcEfficiencyRatio(int period) {
        int size = history.size();
        if (size < period + 1) return Double.NaN;
        double netChange = Math.abs(history.get(size - 1).close() - history.get(size - 1 - period).close());
        double totalVol = 0;
        for (int i = size - period; i < size; i++) {
            totalVol += Math.abs(history.get(i).close() - history.get(i - 1).close());
        }
        if (totalVol == 0) return 0;
        return netChange / totalVol;
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    // State getters/restore for crash recovery
    public int getTradesToday() { return tradesToday; }
    public int getLastTradeDay() { return lastTradeDay; }
    public boolean isInTrade() { return inTrade; }
    public Order.Side getTradeDirection() { return direction; }
    public int getConsecutiveLosses() { return consecutiveLosses; }

    public void restoreState(int tradesToday, int lastTradeDay, boolean inTrade,
                             Order.Side tradeDirection, int consecutiveLosses) {
        this.tradesToday = tradesToday;
        this.lastTradeDay = lastTradeDay;
        this.inTrade = inTrade;
        this.direction = tradeDirection;
        this.consecutiveLosses = consecutiveLosses;
    }
}
