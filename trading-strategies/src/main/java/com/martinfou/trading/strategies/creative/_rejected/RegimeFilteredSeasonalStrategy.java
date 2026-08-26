package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * RegimeFilteredSeasonalStrategy — Calendrier saisonnier 4+5+8 avec filtre de
 * régime D1 (prix vs SMA-N jour) — mercredi 26 août 2026 (pattern saisonnier).
 *
 * 📊 CONCEPT: SeasonalCalendarStrategy (4+5+8 : BUY Apr + SELL May + SELL Aug,
 *    GBP PF 2.83 / EUR PF 2.01, validé 2026-08-20) est la famille la plus
 *    robuste post-fix. Piste ouverte du 21 août : « filtre régime D1 — sur
 *    2 trades/an, un filtre mensuel a plus de sens que sur H1 ». Source
 *    tradernewbie (Rapport saisonnalité) : les effets saisonniers sont plus
 *    forts quand le prix est ALIGNÉ sur la tendance longue.
 *
 * 🔧 HYPOTHÈSE DIRECTIONNELLE (filterMode ALIGNED) :
 *    - BUY  April : ne trader que si le prix D1 est AU-DESSUS de sa SMA-N
 *      (le rally saisonnier avril est un phénomène risk-on — amplifié en
 *      régime haussier, acheté moins vite en régime baissier)
 *    - SELL May/Aug : ne trader que si le prix D1 est EN-DESSOUS de sa SMA-N
 *      (le fade saisonnier s'aligne sur la faiblesse déjà en place)
 *    Contrôles : filterMode OPPOSITE (doit être pire) et NONE (baseline).
 *
 * 🔧 MECHANISM (pattern SeasonalCalendarStrategy + DateWindowSeasonalStrategy) :
 *    1. Fenêtres calendaires UTC (mois → direction), entrée market à
 *       l'ouverture de fenêtre, sortie closeOnly à la clôture (transition
 *       fenêtre adjacente Avr→Mai forcée — bug latent BearishMonthsFade).
 *    2. Agrégation D1 (UTC) dans onBar() AVANT la décision — look-ahead safe :
 *       seules les closes D1 COMPLÈTES sont utilisées (le jour courant n'est
 *       finalisé qu'au changement de jour). Pitfall du 11 août : l'agrégation
 *       D1 doit vivre dans onBar(), PAS dans evaluate().
 *    3. Filtre évalué à l'ouverture de fenêtre : lastCompletedClose vs SMA-N
 *       des closes D1 complètes. Si l'historique est insuffisant (< N closes),
 *       la fenêtre est SKIPPÉE (pas de trade sans historique — réaliste).
 *    4. Quantity fixe 10K units — PAS de sizing ATR (artefact levier 7×).
 *
 * ⚠️ Swap caveat: SwapCalculator = taux CONSTANTS 2024-26 sur 2006-2026.
 *    Lire PnL prix et swap SÉPARÉMENT (BacktestResult.totalSwap).
 */
public class RegimeFilteredSeasonalStrategy implements Strategy {

    /** Modes de filtre. */
    public enum FilterMode { NONE, ALIGNED, OPPOSITE }

    // --- Parameters ---
    private static final double QUANTITY = 10_000;   // fixed units — no leverage artifact
    private static final int MIN_HISTORY = 5;
    private static final int MAX_DAILY = 400;        // cap daily closes

    /** Calendrier long/short complet : BUY Apr + SELL May + SELL Aug. */
    public static final int[] CALENDAR_MONTHS = {4, 5, 8};
    public static final Order.Side[] CALENDAR_DIRECTIONS =
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL};

    private final String name;
    private final String symbol;
    private final int[] windowMonths;
    private final Order.Side[] directions;
    private final int smaDays;          // 0 = pas de filtre
    private final FilterMode mode;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private final List<Double> dailyCloses = new ArrayList<>();

    private LocalDate currentDay = null;
    private double currentDayClose = 0;

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private int currentWindowMonth = -1;
    private int currentWindowYear = -1;
    private boolean windowSpent = false;

    /** Baseline : aucun filtre (équivaut à SeasonalCalendar 4+5+8). */
    public RegimeFilteredSeasonalStrategy(String name, String symbol) {
        this(name, symbol, 0, FilterMode.NONE);
    }

    /** Variante filtrée : SMA-N jours + mode (ALIGNED / OPPOSITE). */
    public RegimeFilteredSeasonalStrategy(String name, String symbol, int smaDays, FilterMode mode) {
        this.name = name;
        this.symbol = symbol;
        this.windowMonths = CALENDAR_MONTHS.clone();
        this.directions = CALENDAR_DIRECTIONS.clone();
        this.smaDays = smaDays;
        this.mode = mode;
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
        LocalDate d = zdt.toLocalDate();
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        // --- Aggregate D1 closes (LOOK-AHEAD SAFE: only COMPLETED days) ---
        // Le jour courant n'est finalisé qu'au changement de jour → au moment
        // de la décision (1ère barre de la fenêtre), dailyCloses contient
        // toutes les closes D1 complètes jusqu'à la veille inclus.
        if (currentDay == null) {
            currentDay = d;
            currentDayClose = bar.close();
        } else if (!d.equals(currentDay)) {
            dailyCloses.add(currentDayClose);          // finalize previous day
            if (dailyCloses.size() > MAX_DAILY) dailyCloses.remove(0);
            currentDay = d;
            currentDayClose = bar.close();
        } else {
            currentDayClose = bar.close();             // intraday — not finalized
        }

        boolean inWindowMonth = isWindowMonth(month);

        // --- Detect window transitions (UTC-based) ---
        if (inWindowMonth && currentWindowMonth != month) {
            if (inTrade) {
                forceExit(bar.close());
            }
            currentWindowMonth = month;
            currentWindowYear = year;
            windowSpent = false;
        } else if (!inWindowMonth && currentWindowMonth != -1) {
            if (inTrade) {
                forceExit(bar.close());
            }
            currentWindowMonth = -1;
            windowSpent = false;
            return;
        }

        // --- MANAGE EXISTING POSITION ---
        if (inTrade) {
            if (month != currentWindowMonth || year != currentWindowYear) {
                forceExit(bar.close());
                windowSpent = true;
                return;
            }
        }

        // --- ENTER AT WINDOW OPEN (first bar of the window month) ---
        if (!inTrade && !windowSpent && inWindowMonth && currentWindowMonth == month
                && year == currentWindowYear) {
            // La transition ci-dessus n'a lieu que sur la 1ère barre du mois de
            // fenêtre → cette condition ne se déclenche qu'à l'ouverture de
            // fenêtre (day 1, ou jour suivant si week-end/férié). windowSpent
            // et inTrade bloquent tout re-entry dans la même fenêtre.
            Order.Side side = directionFor(month);
            if (passesFilter(side)) {
                enterTrade(bar, side);
            } else {
                windowSpent = true;   // fenêtre filtrée → pas de re-entry
            }
        }
    }

    /** Filtre régime D1 : dernier close complet vs SMA-N (look-ahead safe). */
    private boolean passesFilter(Order.Side side) {
        if (smaDays <= 0 || mode == FilterMode.NONE) return true;
        int n = dailyCloses.size();
        if (n < smaDays) return false;   // pas assez d'historique → skip (réaliste)

        double sum = 0;
        for (int i = n - smaDays; i < n; i++) sum += dailyCloses.get(i);
        double sma = sum / smaDays;
        double lastClose = dailyCloses.get(n - 1);

        boolean above = lastClose > sma;
        boolean wantAbove = (side == Order.Side.BUY);
        if (mode == FilterMode.OPPOSITE) wantAbove = !wantAbove;
        return above == wantAbove;
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
        dailyCloses.clear();
        currentDay = null;
        currentDayClose = 0;
        inTrade = false;
        tradeDirection = null;
        currentWindowMonth = -1;
        currentWindowYear = -1;
        windowSpent = false;
    }
}
