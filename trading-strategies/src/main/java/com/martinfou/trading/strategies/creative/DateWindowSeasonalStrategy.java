package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * DateWindowSeasonalStrategy — Fenêtre calendaire à dates précises (H1)
 *
 * 📊 CONCEPT: Généralise SeasonalCalendarStrategy (fenêtres par MOIS) aux
 *    fenêtres à JOURS précis — les patterns saisonniers SeasonalityAnalyzer
 *    sont souvent des fenêtres comme « Sep 27 → Nov 11 » ou « Oct 12 → Nov 26 »
 *    qui ne s'alignent pas sur le 1er du mois.
 *
 *    Fenêtres testées (2026-08-24, pre-validation AutumnWindowCheck) :
 *      - USD/JPY BUY Sep 27 → Nov 11 : REJECT (edge absent IS, régime récent)
 *      - USD/CAD BUY Oct 12 → Nov 26 : MARGINAL (stable 90%→78% mais peu
 *        distinct de novembre — le contrôle Nov 1-30 est PLUS fort : 86% OOS)
 *      - USD/CAD BUY Nov 1 → Nov 30 : variante exploratoire (contrôle qui
 *        surpasse la fenêtre officielle)
 *
 * 🔧 MECHANISM (pattern AugustRiskFade / SeasonalCalendar) :
 *    1. Fenêtre calendaire UTC : start (mois/jour) → end (mois/jour), annuelle.
 *    2. Entrée market sur la première barre de la fenêtre (détection de
 *       transition open), sortie closeOnly sur la première barre APRÈS la fin
 *       de fenêtre (transition close) — le pattern AugustRiskFade validé.
 *    3. Quantity fixe 10K units — PAS de sizing ATR (artefact levier sur
 *       positions multi-semaines, pitfall du 12 août).
 *    4. Max 1 trade par an (lastTradeYear).
 *
 * ⚠️ Swap caveat: SwapCalculator utilise des taux CONSTANTS 2024-26 sur
 *    2006-2026 (artefact). Lire PnL prix et swap SÉPARÉMENT.
 */
public class DateWindowSeasonalStrategy implements Strategy {

    // --- Parameters ---
    private static final double QUANTITY = 10_000;   // fixed units — no leverage artifact
    private static final int MIN_HISTORY = 5;

    private final String name;
    private final String symbol;
    private final int startMonth, startDay, endMonth, endDay;
    private final Order.Side direction;
    private final double quantity;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private boolean inWindow = false;
    private int lastTradeYear = -1;

    /** Fenêtres officielles SeasonalityFilter + variantes. */
    public static final int[] USDJPY_WINDOW = {9, 27, 11, 11};
    public static final int[] USDCAD_WINDOW = {10, 12, 11, 26};
    public static final int[] NOVEMBER_WINDOW = {11, 1, 11, 30};

    public DateWindowSeasonalStrategy(String name, String symbol) {
        this(name, symbol, USDCAD_WINDOW[0], USDCAD_WINDOW[1], USDCAD_WINDOW[2], USDCAD_WINDOW[3],
            Order.Side.BUY);
    }
    /** Variante fenêtre complète. */
    public DateWindowSeasonalStrategy(String name, String symbol, int[] window, Order.Side direction) {
        this(name, symbol, window[0], window[1], window[2], window[3], direction);
    }
    public DateWindowSeasonalStrategy(String name, String symbol,
                                      int startMonth, int startDay, int endMonth, int endDay,
                                      Order.Side direction) {
        this(name, symbol, startMonth, startDay, endMonth, endDay, direction, QUANTITY);
    }
    /** Variante avec quantité personnalisée (ex: 10 oz pour XAU_USD). */
    public DateWindowSeasonalStrategy(String name, String symbol,
                                      int startMonth, int startDay, int endMonth, int endDay,
                                      Order.Side direction, double quantity) {
        this.name = name;
        this.symbol = symbol;
        this.startMonth = startMonth;
        this.startDay = startDay;
        this.endMonth = endMonth;
        this.endDay = endDay;
        this.direction = direction;
        this.quantity = quantity;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        if (history.size() < MIN_HISTORY - 1) {
            history.add(bar);
            return;
        }
        history.add(bar);

        ZonedDateTime zdt = bar.timestamp().atZone(ZoneId.of("UTC"));
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        boolean nowInWindow = inWindow(month, day);
        boolean windowOpened = nowInWindow && !inWindow;
        boolean windowClosed = !nowInWindow && inWindow;
        inWindow = nowInWindow;

        // --- MANAGE EXISTING POSITION (exit first bar after window end) ---
        if (inTrade) {
            if (windowClosed) {
                forceExit(bar.close());
                return;
            }
        }

        // --- ENTER AT WINDOW OPEN ---
        if (windowOpened && !inTrade && year != lastTradeYear) {
            lastTradeYear = year;
            enterTrade(bar);
        }
    }

    /** True si (month, day) est dans la fenêtre annuelle [start, end]. */
    private boolean inWindow(int month, int day) {
        // Fenêtre qui traverse le Nouvel An (ex: Dec 20 → Jan 10)
        if (startMonth > endMonth || (startMonth == endMonth && startDay > endDay)) {
            return (month > startMonth || (month == startMonth && day >= startDay))
                || (month < endMonth || (month == endMonth && day <= endDay));
        }
        return (month > startMonth || (month == startMonth && day >= startDay))
            && (month < endMonth || (month == endMonth && day <= endDay));
    }

    private void enterTrade(Bar bar) {
        pending.add(new Order(symbol, direction, Order.Type.MARKET, quantity, bar.close()));
        inTrade = true;
        tradeDirection = direction;
    }

    private void forceExit(double price) {
        Order.Side exitSide = (tradeDirection == Order.Side.BUY) ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, quantity, price).closeOnly());
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
        inWindow = false;
        lastTradeYear = -1;
    }
}
