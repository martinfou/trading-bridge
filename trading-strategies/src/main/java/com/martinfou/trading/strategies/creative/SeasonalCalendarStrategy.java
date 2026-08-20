package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * SeasonalCalendarStrategy — Calendrier saisonnier long/short complet (H1)
 *
 * 📊 CONCEPT: BearishMonthsFadeStrategy (SELL May+Aug, validé 2026-08-19,
 *    commit 0143d23c) couvre le côté baissier du calendrier saisonnier FX.
 *    Le miroir haussier est le mois d'AVRIL — le pattern mensuel le plus fort
 *    de l'univers (SeasonalityAnalyzer 2006-2026, années extrêmes exclues) :
 *      - GBP_USD : avg +1.81%, hit 88.9%, p &lt; 0.0001, Sharpe 1.01
 *      - EUR_USD : avg +1.46%, hit 72.2%, p = 0.0033, Sharpe 0.69
 *    Split anti-artefact (méthode du 5 août — celle qui a tué XAU August) :
 *      - GBP IS 2006-2015 : +2.55%, hit 100% → OOS 2016-2026 : +1.06%, hit 77.8%
 *      - EUR IS 2006-2015 : +1.85%, hit 77.8% → OOS 2016-2026 : +1.07%, hit 66.7%
 *      - Contrôle mois voisins : Mar (55.6%/50%) vs Avr (88.9%/72.2%) vs Mai
 *        (27.8%/27.8%) — AVRIL EST DISTINCT, et Mai est le miroir baissier
 *        direct : le « sell in May » est en réalité « buy April, then sell May-Aug ».
 *
 * 🔧 MECHANISM (généralisation de BearishMonthsFade — fenêtres + direction) :
 *    1. Windows calendaires UTC : liste de mois + direction par mois
 *       (ex. {4=BUY} pour le miroir avril ; {4=BUY, 5=SELL, 8=SELL} pour le
 *       calendrier long/short complet)
 *    2. Entrée au market sur la première barre de la fenêtre (jour &gt;= openDay)
 *    3. Sortie closeOnly à la fin de la fenêtre (jour &gt; closeDay, sinon forcée
 *       à la première barre du mois suivant — y compris transition directe
 *       fenêtre→fenêtre comme Avr→Mai, bug latent du BearishMonthsFade
 *       d'origine qui n'apparaissait pas car {5,8,11} ne sont jamais adjacents)
 *    4. Quantity fixe 10K units — PAS de sizing ATR (artefact levier 7× sur
 *       positions mensuelles, pitfall du 12 août)
 *    5. Max 1 trade par fenêtre, fenêtres disjointes
 *
 * 🎯 Rationale: complète le calendrier saisonnier. Le côté court
 *    (Mai+Août) est le premier edge FX net positif post-fix. Le côté long
 *    (Avril) est le pattern mensuel le plus statistiquement fort des 20 ans.
 *    Si les deux survivent individuellement en OOS, le calendrier long/short
 *    complet est la version consolidée de la famille.
 *
 * ⚠️ Swap caveat: SwapCalculator utilise des taux CONSTANTS 2024-26 sur
 *    2006-2026 (artefact). Sur les positions longues avril, le swap crédite
 *    +3.8/+4.0 pips/jour (AUD/NZD) — le net peut SURESTIMER l'edge long.
 *    Lire PnL prix et swap SÉPARÉMENT (BacktestResult.totalSwap).
 */
public class SeasonalCalendarStrategy implements Strategy {

    // --- Parameters ---
    private static final double QUANTITY = 10_000;   // fixed units — no leverage artifact
    private static final int MIN_HISTORY = 5;
    /** Miroir haussier : BUY April (mois 4). */
    public static final int[] DEFAULT_WINDOW_MONTHS = {4};
    public static final Order.Side[] DEFAULT_DIRECTIONS = {Order.Side.BUY};
    /** Calendrier long/short complet : BUY Apr + SELL May + SELL Aug. */
    public static final int[] FULL_CALENDAR_MONTHS = {4, 5, 8};
    public static final Order.Side[] FULL_CALENDAR_DIRECTIONS =
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL};

    private final String name;
    private final String symbol;
    private final int[] windowMonths;
    private final Order.Side[] directions;
    private final int entryDayOffset;   // décalage entrée (jours après le 1er)
    private final int exitDayOffset;    // décalage sortie (jours avant la fin)
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private int currentWindowMonth = -1;
    private int currentWindowYear = -1;
    private int currentWindowOpenDay = -1;
    private int currentWindowCloseDay = -1;
    private boolean windowSpent = false;   // fenêtre déjà négociée (anti re-entry)

    public SeasonalCalendarStrategy() { this("SeasonalCalendar", "GBP_USD"); }
    public SeasonalCalendarStrategy(String name) { this(name, "GBP_USD"); }
    public SeasonalCalendarStrategy(String name, String symbol) {
        this(name, symbol, DEFAULT_WINDOW_MONTHS, DEFAULT_DIRECTIONS, 0, 0);
    }
    /** Variante : mois + directions (même longueur). */
    public SeasonalCalendarStrategy(String name, String symbol, int[] months, Order.Side[] sides) {
        this(name, symbol, months, sides, 0, 0);
    }
    /** Variante robustesse : décaler l'entrée de entryDays après le 1er du mois,
     *  et la sortie exitDays avant la fin du mois. */
    public SeasonalCalendarStrategy(String name, String symbol, int[] months, Order.Side[] sides,
                                    int entryDayOffset, int exitDayOffset) {
        if (months.length != sides.length) {
            throw new IllegalArgumentException("months and sides must have same length");
        }
        this.name = name;
        this.symbol = symbol;
        this.windowMonths = months.clone();
        this.directions = sides.clone();
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

        boolean inWindowMonth = isWindowMonth(month);

        // --- Detect window transitions (UTC-based trading-day counting) ---
        if (inWindowMonth && currentWindowMonth != month) {
            // NEW window month: close any lingering position from previous window
            // (critical for ADJACENT windows like Apr→May — the original
            //  BearishMonthsFade never had adjacent windows so this was latent)
            if (inTrade) {
                forceExit(bar.close());
            }
            currentWindowMonth = month;
            currentWindowYear = year;
            currentWindowOpenDay = 1 + entryDayOffset;
            currentWindowCloseDay = daysInMonth - exitDayOffset;
            windowSpent = false;
        } else if (!inWindowMonth && currentWindowMonth != -1) {
            // Exited window month → close any open position at market
            if (inTrade) {
                forceExit(bar.close());
            }
            currentWindowMonth = -1;
            windowSpent = false;
            return;  // no entry possible outside a window
        }

        // --- MANAGE EXISTING POSITION ---
        if (inTrade) {
            // Window closed (past exit day) → force exit at market
            if (day > currentWindowCloseDay) {
                forceExit(bar.close());
                windowSpent = true;  // blocage re-entrée dans la même fenêtre
                return;
            }
        }

        // --- ENTER AT WINDOW OPEN ---
        if (!inTrade && !windowSpent && inWindowMonth && currentWindowMonth == month
                && year == currentWindowYear && day >= currentWindowOpenDay) {
            enterTrade(bar, directionFor(month));
        }
    }

    private boolean isWindowMonth(int month) {
        for (int m : windowMonths) {
            if (m == month) return true;
        }
        return false;
    }

    private Order.Side directionFor(int month) {
        for (int i = 0; i < windowMonths.length; i++) {
            if (windowMonths[i] == month) return directions[i];
        }
        throw new IllegalStateException("No direction for month " + month);
    }

    private void enterTrade(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, QUANTITY, bar.close()));
        inTrade = true;
        tradeDirection = side;
    }

    private void forceExit(double price) {
        Order.Side exitSide = (tradeDirection == Order.Side.BUY) ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, QUANTITY, price).closeOnly());
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
        tradeDirection = null;
        currentWindowMonth = -1;
        currentWindowYear = -1;
        currentWindowOpenDay = -1;
        currentWindowCloseDay = -1;
        windowSpent = false;
    }
}
