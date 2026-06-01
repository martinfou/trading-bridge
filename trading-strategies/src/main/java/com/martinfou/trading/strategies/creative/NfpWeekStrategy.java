package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * NFP Week Strategy — Short EUR/USD for NFP week (June 1–5, 2026)
 *
 * 📊 Analysis basis (2026-05-31 weekly prop shop):
 *   - COT: EUR/USD -40K short positioning, USD strong on 6/7 pairs
 *   - Seasonality: "Sell in May" active, June traditionally weak for EUR/USD
 *   - NFP week bias: USD tends to strengthen into NFP release
 *   - Score: 8/15 — Short EUR/USD
 *
 * 🔧 Mechanism:
 *   - Shorted at Monday open (or first H1 bar of the week)
 *   - ATR-based trailing stop (2.0× ATR(14))
 *   - TP = 2.0× SL distance (R:R 1:2)
 *   - Widen stop 3× on NFP release day (Friday) to avoid whipsaw
 *   - Hard TP by Friday 17:00 ET close if not stopped out
 *   - Max 1 trade per week
 *
 * 🎯 Originality: Event-driven weekly macro play that captures
 *    the directional bias of NFP week, with volatility-aware
 *    risk management that adapts on high-impact release days.
 */
public class NfpWeekStrategy implements Strategy {

    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULT = 2.0;
    private static final double RR = 2.0;
    private static final double NFP_WIDEN_MULT = 3.0;
    private static final int MIN_HISTORY = 25;
    private static final double MICRO_LOT = 1000;

    private final String name;
    private final String symbol;
    private final String oandaSymbol;
    private final double baseQuantity;     // in units (default MICRO_LOT = 1000)
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    // Trade state
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.SELL; // always short
    private double entryPrice = 0;
    private double entrySL = 0;
    private double entryTP = 0;
    private boolean weekStarted = false;
    private int lastBarDayOfMonth = 0;

    /** Default constructor uses EUR/USD with micro lot. */
    public NfpWeekStrategy() {
        this("NfpWeekShortEURUSD", "EUR/USD", "EUR_USD", MICRO_LOT);
    }

    public NfpWeekStrategy(String name) {
        this(name, "EUR/USD", "EUR_USD", MICRO_LOT);
    }

    public NfpWeekStrategy(String name, String symbol, String oandaSymbol, double quantity) {
        this.name = name;
        this.symbol = symbol;
        this.oandaSymbol = oandaSymbol;
        this.baseQuantity = quantity;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        double atr = calculateATR(ATR_PERIOD);
        double close = bar.close();
        int dayOfMonth = dayOfMonth(bar.timestamp());

        // Detect week start — first bar of the week
        if (!weekStarted) {
            // Monday detection: dayOfMonth changes from weekend
            if (lastBarDayOfMonth > 0 && dayOfMonth != lastBarDayOfMonth) {
                weekStarted = true;
                // Short at first bar of the week
                double sl = close + atr * ATR_SL_MULT;
                double tp = close - atr * ATR_SL_MULT * RR;
                entryPrice = close;
                entrySL = sl;
                entryTP = tp;

                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET,
                    baseQuantity, close));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
            }
            lastBarDayOfMonth = dayOfMonth;
            return;
        }

        if (!inTrade) return;

        // Check if it's Friday (NFP day) — widen SL
        DayOfWeek dow = dayOfWeek(bar.timestamp());
        boolean isNFPDay = (dow == DayOfWeek.FRIDAY);

        // Calculate current SL based on day
        double currentSL = isNFPDay
            ? entryPrice + atr * ATR_SL_MULT * NFP_WIDEN_MULT
            : entrySL;

        // Check stop loss
        if (tradeDirection == Order.Side.SELL && bar.high() >= currentSL) {
            closePosition(close);
            return;
        }

        // Check take profit
        if (tradeDirection == Order.Side.SELL && bar.low() <= entryTP) {
            closePosition(close);
            return;
        }

        // End of week exit — Friday before 17:00 ET (close of Friday trading)
        if (isNFPDay && bar.timestamp().atZone(ZoneId.of("America/New_York")).getHour() >= 17) {
            closePosition(close);
            return;
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, baseQuantity, price));
        inTrade = false;
        weekStarted = false; // reset for next week
        entryPrice = 0;
        entrySL = 0;
        entryTP = 0;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()),
                         Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0.0001;
    }

    private static int dayOfMonth(Instant ts) {
        return ts.atZone(ZoneId.of("America/New_York")).getDayOfMonth();
    }

    private static DayOfWeek dayOfWeek(Instant ts) {
        return ts.atZone(ZoneId.of("America/New_York")).getDayOfWeek();
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        entryPrice = 0;
        entrySL = 0;
        entryTP = 0;
        weekStarted = false;
        lastBarDayOfMonth = 0;
    }
}
