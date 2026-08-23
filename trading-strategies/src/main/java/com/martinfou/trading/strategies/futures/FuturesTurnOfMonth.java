package com.martinfou.trading.strategies.futures;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.indicators.Indicators;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FuturesTurnOfMonth — Turn-of-the-Month (TOTM) Calendar Anomaly on US Index Futures (MES, MNQ, M2K).
 *
 * 📊 Quantitative Research: Ariel (1987), Lakonishok & Smidt (1988), McConnell & Xu (2008).
 *    The S&P 500 historically generates the vast majority of its cumulative net return during
 *    the turn of the month (the last trading day of the month + first 3 trading days of the next month),
 *    driven by automatic pension contributions, 401(k) inflows, and monthly fund rebalancing.
 *
 * 🔧 Mechanism:
 *    - Long-only regime aligned with macro uptrend (Price > EMA 200).
 *    - Window: Enters on the last trading day of each month (Day of Month >= 27 & trading day distance <= 1).
 *    - Exit: Closes position at the end of the 3rd trading day of the new month (approx 36 H1 bars) or trailing ATR stop.
 *    - Strict 1 trade per month constraint.
 */
public class FuturesTurnOfMonth implements Strategy {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final int DEFAULT_REGIME_EMA = 200;
    private static final int DEFAULT_ATR_PERIOD = 14;
    private static final double DEFAULT_SL_ATR = 2.5;
    private static final int MAX_HOLDING_BARS = 36; // ~3-4 trading days of RTH

    private final String name;
    private final String symbol;
    private final int regimeEmaPeriod;
    private final int atrPeriod;
    private final double slAtrMult;

    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private int activeYearMonth = -1;

    public FuturesTurnOfMonth() {
        this("FuturesTurnOfMonth", "MES");
    }

    public FuturesTurnOfMonth(String name) {
        this(name, "MES");
    }

    public FuturesTurnOfMonth(String name, String symbol) {
        this(name, symbol, DEFAULT_REGIME_EMA, DEFAULT_ATR_PERIOD, DEFAULT_SL_ATR);
    }

    public FuturesTurnOfMonth(String name, String symbol, int regimeEmaPeriod, int atrPeriod, double slAtrMult) {
        this.name = name;
        this.symbol = symbol;
        this.regimeEmaPeriod = regimeEmaPeriod > 0 ? regimeEmaPeriod : DEFAULT_REGIME_EMA;
        this.atrPeriod = atrPeriod > 0 ? atrPeriod : DEFAULT_ATR_PERIOD;
        this.slAtrMult = slAtrMult > 0 ? slAtrMult : DEFAULT_SL_ATR;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equalsIgnoreCase(symbol) && !bar.symbol().equalsIgnoreCase(symbol.replace("/", "_"))) {
            return;
        }

        history.add(bar);
        if (history.size() < regimeEmaPeriod + 10) return;

        ZonedDateTime nyTime = bar.timestamp().atZone(NY_ZONE);
        LocalDate date = nyTime.toLocalDate();
        int ym = date.getYear() * 100 + date.getMonthValue();

        if (inTrade) {
            barsInTrade++;
            double atr = Indicators.atr(history, atrPeriod);
            if (Double.isNaN(atr)) atr = 10.0;

            // Stop Loss check
            if (bar.low() <= stopLoss) {
                closePosition();
                return;
            }

            // Time-based exit after 3 trading days of the new month (day 3 or 4) or max holding bars
            if ((date.getDayOfMonth() >= 4 && date.getDayOfMonth() <= 20) || barsInTrade >= MAX_HOLDING_BARS) {
                closePosition();
            }
        } else {
            // Check for entry during the last trading day of the month (Days 28-31)
            int dom = date.getDayOfMonth();
            int lengthOfMonth = date.lengthOfMonth();

            if (dom >= lengthOfMonth - 1 && ym != activeYearMonth) {
                evaluateEntry(bar, ym);
            }
        }
    }

    private void evaluateEntry(Bar bar, int ym) {
        double regimeEma = Indicators.emaLatest(history, regimeEmaPeriod);
        double atr = Indicators.atr(history, atrPeriod);
        if (Double.isNaN(regimeEma) || Double.isNaN(atr) || atr <= 0) return;

        // Long-only filter: only enter when price is above 200 EMA
        if (bar.close() > regimeEma) {
            entryPrice = bar.close();
            stopLoss = entryPrice - slAtrMult * atr;
            barsInTrade = 0;
            inTrade = true;
            activeYearMonth = ym;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, entryPrice)
                .withStopLoss(stopLoss));
        }
    }

    private void closePosition() {
        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, 0.0).asCloseOnly());
        inTrade = false;
        barsInTrade = 0;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        barsInTrade = 0;
        activeYearMonth = -1;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }
}
