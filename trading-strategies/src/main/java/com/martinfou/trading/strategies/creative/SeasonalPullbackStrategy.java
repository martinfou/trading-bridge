package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * SeasonalPullbackStrategy — Seasonal window pullback trades (H1)
 *
 * 📊 Concept: Trade ONLY during USDCAD's strongest seasonal windows, entering
 *    on pullbacks to EMA(100). Outside windows, no trades. This captures the
 *    seasonal directional drift while getting a better entry on intraday dips.
 *
 *    USDCAD seasonal patterns (21-yr research, Dukascopy H1):
 *    - Oct 12 → Nov 26: BUY (94% hit rate) — enter on dips to EMA(100)
 *    - April: SELL (72% hit rate) — enter on rallies to EMA(100)
 *
 *    By trading only within these windows and requiring a pullback, we get
 *    ~15-25 trades/year with strong directional bias. Costs are negligible
 *    due to low trade count.
 *
 * 🔧 Mechanism:
 *    - Seasonal window detection: Apr 1-30 (SELL), Oct 12-Nov 26 (BUY)
 *    - Entry: price touches/r touches EMA(100) during window (pullback entry)
 *    - No entry outside windows
 *    - SL = 1.5× ATR(14), TP = 4.0× ATR(14) (wide targets for directional drift)
 *    - Manual SL/TP via manageExit(), closeOnly() exit
 *    - Max 1 trade/day
 *
 * 🎯 Target: 15-25 trades/year, Sharpe > 1.0, PF > 1.5, DD < 5%
 *    Key insight: Few trades = negligible cost impact
 */
public class SeasonalPullbackStrategy implements Strategy {

    // --- Parameters ---
    private static final int EMA_PERIOD = 100;
    private static final int ATR_PERIOD = 14;
    private static final double PULLBACK_TOLERANCE = 0.001; // 0.1% tolerance around EMA
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 4.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = Math.max(EMA_PERIOD, ATR_PERIOD) + 5;

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int lastTradeDay = -1;
    private int tradesToday = 0;
    private int cooldownBars = 0;
    private static final int COOLDOWN_BARS = 3;

    // --- Constructors ---
    public SeasonalPullbackStrategy() { this("SeasonalPullbackStrategy", "USDCAD"); }
    public SeasonalPullbackStrategy(String name) { this(name, "USDCAD"); }
    public SeasonalPullbackStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        double ema100 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(ema100) || Double.isNaN(atr) || atr <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, ema100, atr);
        }
    }

    /**
     * USDCAD seasonal window detection.
     * - Oct 12 → Nov 26: BUY window (94% hit rate)
     * - April: SELL window (72% hit rate)
     */
    private Order.Side getSeasonalWindow(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        if ((month == 10 && day >= 12) || (month == 11 && day <= 26)) return Order.Side.BUY;
        if (month == 4) return Order.Side.SELL;
        return null; // outside window
    }

    private void evaluateEntry(Bar bar, double ema100, double atr) {
        double close = bar.close();
        double emaPct = Math.abs(close - ema100) / ema100;

        // Must be within seasonal window
        Order.Side window = getSeasonalWindow(bar.timestamp());
        if (window == null) return;

        // Must be near EMA(100) (pullback)
        if (emaPct > PULLBACK_TOLERANCE) return;

        // BUY window: close should be at or slightly below EMA (pullback in uptrend)
        if (window == Order.Side.BUY && close <= ema100) {
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        }
        // SELL window: close should be at or slightly above EMA (pullback in downtrend)
        else if (window == Order.Side.SELL && close >= ema100) {
            entryPrice = close;
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            tradesToday++;
        }
    }

    private void managePosition(Bar bar) {
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);
        if (stopHit) { exitPosition(stopLoss); return; }
        if (tpHit) { exitPosition(takeProfit); return; }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        lastTradeDay = -1;
        tradesToday = 0;
        cooldownBars = 0;
    }
}
