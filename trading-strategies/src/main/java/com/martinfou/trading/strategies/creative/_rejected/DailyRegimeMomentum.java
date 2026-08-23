package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * DailyRegimeMomentum — Multi-timeframe D1 regime → H1 execution
 *
 * 📊 Data insight (Lewis Jackson / regime methodology): H1 noise dominates
 *    single-timeframe signals (post-fix reality: all simple H1 strategies
 *    converge to PF ~0.9 after costs). The 20-day D1 return is a much
 *    slower, stickier signal: it separates genuine macro trends (BULL ≥ +5%,
 *    BEAR ≤ -5%) from the ~70% of the time the market is SIDEWAYS. Trading
 *    ONLY in the direction of the D1 regime, and ONLY on H1 pullbacks,
 *    removes most of the noise while keeping the directional tailwind.
 *
 * 🔧 Mechanism:
 *    - Build synthetic D1 closes from H1 bars (UTC day boundary)
 *    - mom20 = closeD1[t] / closeD1[t-20] - 1  (computed on CLOSED days only)
 *    - Regime: BULL if mom20 >= +5%, BEAR if mom20 <= -5%, SIDEWAYS otherwise
 *    - BULL → BUY H1 pullback to EMA(20); BEAR → SELL H1 rally to EMA(20)
 *    - SIDEWAYS → no new trades, close existing position
 *    - SL = 2× ATR(14), TP = 4× ATR(14) (RR 1:2)
 *    - Anti-seasonal filter: no trades against the seasonal bias
 *    - Exit if regime flips or turns SIDEWAYS
 *
 * 🎯 Target: 20-60 trades/year, holding multi-day, costs amortized over
 *    longer holds. First test of the D1→H1 multi-timeframe track.
 */
public class DailyRegimeMomentum implements Strategy {

    // --- Parameters ---
    private static final int MOM_WINDOW = 20;         // D1 return window (trading days)
    private static final double MOM_THRESHOLD = 0.05; // ±5% regime boundary
    private static final int EMA_PERIOD = 20;         // H1 pullback EMA
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 2.0;
    private static final double TP_MULT = 4.0;
    private static final double POSITION_SIZE = 1000;
    private static final int MIN_D1 = MOM_WINDOW + 2; // need t-20 close
    private static final int MIN_H1 = Math.max(EMA_PERIOD, ATR_PERIOD) + 5;

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    // Daily aggregation (UTC)
    private final List<Double> dailyCloses = new ArrayList<>();
    private int currentDay = -1;         // yyyy*1000 + dayOfYear of current UTC day
    private double currentDayClose = 0;

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double stopLoss = 0;
    private double takeProfit = 0;

    // --- Constructors ---
    public DailyRegimeMomentum() { this("DailyRegimeMomentum", "EUR_USD"); }
    public DailyRegimeMomentum(String name) { this(name, "EUR_USD"); }
    public DailyRegimeMomentum(String name, String symbol) {
        this(name, symbol, MOM_WINDOW, MOM_THRESHOLD);
    }

    /** Parametric constructor (sweeps only — catalogue uses the 3 standard ones). */
    public DailyRegimeMomentum(String name, String symbol, int momWindow, double momThreshold) {
        this.name = name;
        this.symbol = symbol;
        this.momWindow = momWindow;
        this.momThreshold = momThreshold;
    }

    private final int momWindow;
    private final double momThreshold;

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // --- Daily aggregation: finalize previous day when UTC day changes ---
        ZonedDateTime utc = bar.timestamp().atZone(ZoneId.of("UTC"));
        int dayKey = utc.getYear() * 1000 + utc.getDayOfYear();
        if (currentDay == -1) {
            currentDay = dayKey;
            currentDayClose = bar.close();
        } else if (dayKey != currentDay) {
            dailyCloses.add(currentDayClose);   // previous day is CLOSED → usable signal
            currentDay = dayKey;
            currentDayClose = bar.close();
        } else {
            currentDayClose = bar.close();
        }

        // --- Look-ahead safe: compute indicators on closed history, then add bar ---
        if (history.size() < MIN_H1 - 1) { history.add(bar); return; }

        double ema20 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(ema20) || Double.isNaN(atr) || atr <= 0) return;

        Order.Side regime = regimeSignal();
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        if (inTrade) {
            managePosition(bar, regime);
            return;
        }

        // --- Entry: only in regime direction, on H1 pullback, with seasonal tailwind ---
        if (regime == Order.Side.BUY && bias != Order.Side.SELL) {
            if (bar.low() <= ema20 && bar.close() > ema20 && bar.close() > bar.open()) {
                double entry = bar.close();
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, POSITION_SIZE, entry));
                inTrade = true; tradeDirection = Order.Side.BUY;
                entryPrice = entry; stopLoss = entry - SL_MULT * atr; takeProfit = entry + TP_MULT * atr;
            }
        } else if (regime == Order.Side.SELL && bias != Order.Side.BUY) {
            if (bar.high() >= ema20 && bar.close() < ema20 && bar.close() < bar.open()) {
                double entry = bar.close();
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, POSITION_SIZE, entry));
                inTrade = true; tradeDirection = Order.Side.SELL;
                entryPrice = entry; stopLoss = entry + SL_MULT * atr; takeProfit = entry - TP_MULT * atr;
            }
        }
    }

    private void managePosition(Bar bar, Order.Side regime) {
        // Regime flipped or turned SIDEWAYS → exit
        if (regime == null || regime != tradeDirection) { closePosition(bar.close()); return; }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);
        if (stopHit) { closePosition(stopLoss); return; }
        if (tpHit) { closePosition(takeProfit); return; }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, POSITION_SIZE, price));
        inTrade = false;
    }

    /** D1 regime from CLOSED days only: mom20 = lastClosedClose / close 20 days ago - 1. */
    private Order.Side regimeSignal() {
        if (dailyCloses.size() < momWindow + 2) return null;
        double now = dailyCloses.get(dailyCloses.size() - 1);
        double past = dailyCloses.get(dailyCloses.size() - 1 - momWindow);
        if (past <= 0) return null;
        double mom = now / past - 1.0;
        if (mom >= momThreshold) return Order.Side.BUY;
        if (mom <= -momThreshold) return Order.Side.SELL;
        return null; // SIDEWAYS
    }

    /**
     * Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias()
     * (trading-intelligence), copié ici car trading-strategies ne peut pas
     * dépendre de trading-intelligence (cycle Maven). Symbole normalisé
     * sans underscore (bug silencieux découvert 2026-08-04).
     */
    protected Order.Side getSeasonalBias(String sym, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null;
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }

    @Override public void reset() {
        history.clear(); pending.clear(); dailyCloses.clear();
        currentDay = -1; currentDayClose = 0;
        inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; stopLoss = 0; takeProfit = 0;
    }
}
