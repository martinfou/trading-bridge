package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleD1 — Variation D1-native de GoldTurtleTrend (Turtle Donchian sur
 * clôtures JOURNALIÈRES au lieu de barres H1).
 *
 * 📊 CONCEPT: GoldTurtleTrend (17 août 2026, PF 1.17, 1594 trades/20 ans)
 * exécute le canal Donchian 55/20 sur barres H1 — canal 55 H1 ≈ 2.3 jours.
 * La piste « exécution D1 native » (notée 17 & 21 août : canal 55 D1 ≈ 2.5
 * mois, 1594 → ~50 trades) teste la MÊME mécanique Turtle sur des barres D1
 * agrégées depuis les H1, évaluée UNE fois par jour à la clôture UTC.
 * Objectifs : (1) coûts et swap négligeables à ~50 trades/20 ans ; (2) test
 * de robustesse de l'edge « or » à travers les fréquences — si l'edge est
 * structurel (multi-années), il doit survivre à la basse fréquence ; s'il
 * était un artefact de fréquence H1 (whipsaws), il meurt ici.
 *
 * 🔧 MECHANISM (look-ahead safe):
 *    - Agrégation D1 (UTC) dans onBar() — pitfall 11 août : JAMAIS dans
 *      evaluate() (il n'y a pas d'evaluate ici, tout est dans onBar).
 *    - À la transition de jour (1ère barre H1 du nouveau jour), le jour
 *      précédent D est COMPLET → évaluation sur sa clôture :
 *        BUY  si close_D > max(high des `entryChannel` jours AVANT D)
 *        SELL si close_D < min(low  des `entryChannel` jours AVANT D)
 *        Sortie long  : close_D < min(low des `exitChannel` jours AVANT D)
 *        Sortie short : close_D > max(high des `exitChannel` jours AVANT D)
 *    - Canaux sur les jours STRICTEMENT antérieurs à D (jamais D) — miroir
 *      exact de la sémantique H1 de GoldTurtleTrend (maxHigh(n-1, period)).
 *    - Exécution : les ordres posés à la finalisation de D se remplissent au
 *      open de la barre H1 suivante (sémantique du moteur = open de la barre
 *      suivante) ≈ 01:00 UTC du jour D+1.
 *    - Quantity fixe 10 oz (pas de levier ATR — pitfall 12 août).
 *    - Garde SeasonalityFilter inline (neutre pour XAU — même miroir que
 *      GoldTurtleTrendStrategy).
 *
 * 🎯 Attendus: soit l'edge or survit à basse fréquence (PF > 1.2 possible car
 *    quasi zéro coûts, DD réduit par moins de whipsaws), soit il s'évapore —
 *    les deux résultats sont informatifs pour la famille « métaux ».
 */
public class GoldTurtleD1Strategy implements Strategy {

    private static final double QUANTITY = 10;       // fixed oz — no ATR leverage artifact
    private static final int DEFAULT_ENTRY_CHANNEL = 55;  // Turtle S1 entry channel (D1)
    private static final int DEFAULT_EXIT_CHANNEL = 20;   // Turtle S1 exit channel (D1)

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;

    private final List<DailyBar> days = new ArrayList<>();   // completed UTC days, strictly before cur
    private final List<Order> pending = new ArrayList<>();

    private DailyBar cur;              // in-progress day (aggregation)
    private LocalDate curDate;

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;

    public GoldTurtleD1Strategy() { this("GoldTurtleD1", "XAU_USD"); }
    public GoldTurtleD1Strategy(String name) { this(name, "XAU_USD"); }
    public GoldTurtleD1Strategy(String name, String symbol) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL);
    }
    public GoldTurtleD1Strategy(String name, String symbol, int entryChannel, int exitChannel) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        LocalDate day = bar.timestamp().atZone(TimeConventions.UTC).toLocalDate();

        if (cur == null || !day.equals(curDate)) {
            // Day transition: the previous day is COMPLETE → evaluate on its close,
            // then append it to the completed-days list (channels exclude it next time).
            if (cur != null) {
                evaluate(cur);
                days.add(cur);
            }
            cur = new DailyBar(day, bar.open(), bar.high(), bar.low(), bar.close());
            curDate = day;
        } else {
            cur.high = Math.max(cur.high, bar.high());
            cur.low  = Math.min(cur.low, bar.low());
            cur.close = bar.close();
        }
    }

    /** D1-native evaluation on the just-completed day d (channels over days BEFORE d). */
    private void evaluate(DailyBar d) {
        int n = days.size();
        if (n < exitChannel) return;   // warmup: need `exitChannel` completed days

        double prevHighEntry = maxHigh(n, entryChannel);
        double prevLowEntry  = minLow(n, entryChannel);
        double prevHighExit  = maxHigh(n, exitChannel);
        double prevLowExit   = minLow(n, exitChannel);

        // SeasonalityFilter guard (inline mirror — XAU → neutral null)
        Order.Side seasonalBias = getSeasonalBias(symbol, d.date);

        // --- MANAGE EXISTING POSITION (channel exit S1, D1) ---
        if (inTrade) {
            if (tradeDirection == Order.Side.BUY && d.close < prevLowExit) {
                closePosition(d);
                return;
            }
            if (tradeDirection == Order.Side.SELL && d.close > prevHighExit) {
                closePosition(d);
                return;
            }
            return;
        }

        // --- ENTRY (Donchian breakout on DAILY close) ---
        if (d.close > prevHighEntry) {
            if (seasonalBias == null || seasonalBias == Order.Side.BUY) {
                enter(d, Order.Side.BUY);
            }
        } else if (d.close < prevLowEntry) {
            if (seasonalBias == null || seasonalBias == Order.Side.SELL) {
                enter(d, Order.Side.SELL);
            }
        }
    }

    private void enter(DailyBar d, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, QUANTITY, d.close));
        inTrade = true;
        tradeDirection = side;
    }

    private void closePosition(DailyBar d) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, QUANTITY, d.close).closeOnly());
        inTrade = false;
    }

    /** Max high over the `period` days strictly before index `end` (exclusive) of `days`. */
    private double maxHigh(int end, int period) {
        double max = 0;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (days.get(i).high > max) max = days.get(i).high;
        }
        return max;
    }

    /** Min low over the `period` days strictly before index `end` (exclusive) of `days`. */
    private double minLow(int end, int period) {
        double min = Double.MAX_VALUE;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (days.get(i).low < min) min = days.get(i).low;
        }
        return min;
    }

    /** Daily bar aggregation (UTC). */
    private static final class DailyBar {
        final LocalDate date;
        double open, high, low, close;
        DailyBar(LocalDate date, double open, double high, double low, double close) {
            this.date = date;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    /**
     * Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias()
     * (trading-intelligence), copié ici car trading-strategies ne peut pas
     * dépendre de trading-intelligence (cycle Maven). Même pattern que
     * GoldTurtleTrendStrategy.
     */
    protected Order.Side getSeasonalBias(String sym, LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null; // XAU_USD → no registered pattern → neutral
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }

    @Override public List<Order> getPendingOrders() {
        var copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
    @Override public void reset() {
        days.clear();
        pending.clear();
        cur = null;
        curDate = null;
        inTrade = false;
        tradeDirection = Order.Side.BUY;
    }
}
