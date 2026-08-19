package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * BearishMonthsFadeStrategy — Grouped bearish-months family fade (H1)
 *
 * 📊 CONCEPT: The August curse (validated 2026-08-12 by AugustRiskCheck +
 *    AugustWindowScan) is NOT an August-only phenomenon. The sliding-window
 *    scan found a FAMILY of bearish windows: Apr-May, Aug, and Nov-Dec all
 *    show hit rates 25-38% (i.e. 62-75% of years DOWN) on risk pairs.
 *    Monthly SeasonalityAnalyzer on GBP_USD (2006-2026, outliers excluded):
 *      - May:     hit 27.8% (72% of years DOWN), avg -0.52%
 *      - August:  hit 31.3% (69% of years DOWN), avg -0.49%  ← validated by
 *        AugustRiskCheck IS 30% → OOS 33% (stable), July BULLISH 56-63% (distinct)
 *      - November: hit 31.3% (69% of years DOWN), avg -0.21%
 *    Grouping the family solves AugustRiskFade's fatal flaw: 1 window/year =
 *    18-19 trades in 20 years &lt; gate 30. With 3 windows/year → ~60 trades.
 *
 * 🔧 MECHANISM:
 *    1. Windows (UTC trading-day counting): May 1-31, Aug 1-31, Nov 1-30
 *    2. Entry: SELL at market on first bar of each window
 *    3. Exit: BUY closeOnly on last bar of the window (forced at close)
 *    4. Fixed quantity 10K units — NO ATR-based sizing (leverage artifact on
 *       monthly holds: ATR H1 sizing × 1-month position = ~7x leverage, DD 146%)
 *    5. Max 1 trade per window, no overlap possible (windows are disjoint)
 *
 * 🎯 Rationale: pure calendar drift. After the look-ahead fix + costs + swap,
 *    all H1 FX indicator strategies die (PF ~0.9). The only surviving pattern
 *    class is seasonal window drift with FIXED quantity. August alone was
 *    net +6.1% on GBP (only pair where constant 2024-26 swap model didn't
 *    kill it). The grouped family on GBP is the test the Aug-12 note flagged:
 *    "la famille « mois baissiers » (Avr-Mai + Août + Nov-Déc) mériterait un
 *    test groupé avec swaps réalistes."
 *
 * ⚠️ Swap caveat: SwapCalculator uses CONSTANT 2024-26 rates over 2006-2026
 *    (artifact). On AUD/NZD shorts the constant model credits +3.8/+4.0 pips
 *    per day long but debits -6.2/-6.5 short — the engine's constant rates
 *    killed the August AUD/NZD net. This grouped test reports price PnL and
 *    swap SEPARATELY (BacktestResult.totalSwap) to expose the artifact.
 */
public class BearishMonthsFadeStrategy implements Strategy {

    // --- Parameters ---
    private static final double QUANTITY = 10_000;   // fixed units — no leverage artifact
    private static final int MIN_HISTORY = 5;
    // Bearish months family: May(5), August(8), November(11)
    private static final int[] DEFAULT_WINDOW_MONTHS = {5, 8, 11};

    private final String name;
    private final String symbol;
    private final int[] windowMonths;
    private final int entryDayOffset;   // décalage entrée (jours après le 1er)
    private final int exitDayOffset;    // décalage sortie (jours avant la fin)
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private int currentWindowMonth = -1;
    private int currentWindowYear = -1;
    private int currentWindowOpenDay = -1;
    private int currentWindowCloseDay = -1;
    private boolean windowSpent = false;   // fenêtre déjà négociée (anti re-entry)

    public BearishMonthsFadeStrategy() { this("BearishMonthsFade", "GBP_USD"); }
    public BearishMonthsFadeStrategy(String name) { this(name, "GBP_USD"); }
    public BearishMonthsFadeStrategy(String name, String symbol) {
        this(name, symbol, DEFAULT_WINDOW_MONTHS, 0, 0);
    }
    /** Variante pour sweep par fenêtre : passer ex. {5}, {8}, {11}. */
    public BearishMonthsFadeStrategy(String name, String symbol, int[] months) {
        this(name, symbol, months, 0, 0);
    }
    /** Variante robustesse : décaler l'entrée de entryDays après le 1er du mois,
     *  et la sortie exitDays avant la fin du mois. */
    public BearishMonthsFadeStrategy(String name, String symbol, int[] months,
                                     int entryDayOffset, int exitDayOffset) {
        this.name = name;
        this.symbol = symbol;
        this.windowMonths = months.clone();
        this.entryDayOffset = entryDayOffset;
        this.exitDayOffset = exitDayOffset;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Need minimum history
        if (history.size() < MIN_HISTORY - 1) {
            history.add(bar);
            return;
        }
        history.add(bar);

        ZonedDateTime zdt = bar.timestamp().atZone(ZoneId.of("UTC"));
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        int daysInMonth = zdt.toLocalDate().lengthOfMonth();

        boolean windowOpened = false;
        boolean windowClosed = false;

        // Detect window transitions (UTC-based trading-day counting)
        if (isWindowMonth(month)) {
            if (currentWindowMonth != month) {
                currentWindowMonth = month;
                currentWindowYear = year;
                currentWindowOpenDay = 1 + entryDayOffset;
                currentWindowCloseDay = daysInMonth - exitDayOffset;
                windowSpent = false;
                windowOpened = (day >= currentWindowOpenDay);
            }
            // Window closes once we pass the exit day → force exit
            if (inTrade && day > currentWindowCloseDay) {
                windowClosed = true;
            }
        } else {
            if (currentWindowMonth != -1) {
                currentWindowMonth = -1;
                windowClosed = true;
            }
        }

        // --- MANAGE EXISTING POSITION ---
        if (inTrade) {
            // Window closed (past exit day or next month) → force exit at market
            if (windowClosed) {
                forceExit(bar.close());
                windowSpent = true;  // blocage re-entrée dans la même fenêtre
                return;
            }
        }

        // --- ENTER AT WINDOW OPEN ---
        if (!inTrade && !windowSpent && isWindowMonth(month) && currentWindowMonth == month
                && year == currentWindowYear && day >= currentWindowOpenDay) {
            enterTrade(bar);
        }
    }

    private boolean isWindowMonth(int month) {
        for (int m : windowMonths) {
            if (m == month) return true;
        }
        return false;
    }

    private void enterTrade(Bar bar) {
        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close()));
        inTrade = true;
    }

    private void forceExit(double price) {
        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, price).closeOnly());
        inTrade = false;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

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
        currentWindowMonth = -1;
        currentWindowYear = -1;
        currentWindowOpenDay = -1;
        currentWindowCloseDay = -1;
        windowSpent = false;
    }
}
