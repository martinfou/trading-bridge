package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldFridayWindowStrategy — Variation GoldWeekdayEffect FRI : long or
 * restreint à une FENÊTRE HORAIRE du vendredi (UTC).
 *
 * 📊 CONCEPT (variation, mardi 8 sept 2026, 30e résultat) :
 *    GoldWeekdayEffect (31 août, FRI long PF 1.27 / +$15 131 / 1041 trades)
 *    tient TOUTE la session du vendredi (entrée 1re barre ven 00:00 UTC,
 *    sortie lundi open en pratique car le moteur saute sam/dim). Le bid
 *    safe-haven de week-end or a été caractérisé : twin-refuge (DXY
 *    OPPOSITE, 4 sept, PF 1.40 OOS-stable), SESSION et non GAP (7 sept,
 *    GoldWeekendGap REJECT — le gap de réouverture est vide).
 *
 *    QUESTION (piste ouverte 7 sept) : où DANS la session du vendredi le
 *    bid vit-il ? Londres (fixing 10:30/15:00) ? NY (dé-risk US avant la
 *    fermeture 17:00 ET ≈ 21:00 UTC) ? Réparti uniformément ?
 *
 *    Pré-validation GoldFridayHourCheck (Pattern D) sur barres réelles :
 *    - Le rendement VENDREDI full-day OOS 2016-25 = +0.037% cumulé, mais il
 *      s'accumule en ASIE/early (00:00-08:00 UTC : OOS +0.044% cumulé au
 *      pic H8) puis stagne (NY afternoon OOS ≈ 0).
 *    - La jambe NY afternoon 13:00-20:00 UTC est IS-only : +0.12% cumulé
 *      IS 2006-15 (bull or beta) vs ≈ 0 OOS → PAS un bid de week-end, un
 *      artefact du bull 2006-2012 (même signature que XAU August 5 août).
 *    - Londres 07:00-12:00 = léger drag (moyenne ≈ 0).
 *
 *    HYPOTHÈSE : la fenêtre [entrée, sortie) qui garde le bid OOS-stable
 *    (Asia/early) sans la jambe NY IS-only devrait améliorer PF OOS et
 *    concentrer le net PAR TRADE. Le full-day sert de baseline (doit
 *    reproduire PF 1.27).
 *
 * 🔧 MÉCANIQUE (look-ahead safe, UTC) :
 *    1. Entrée long vendredi à la 1re barre d'heure ≥ entryHour.
 *       - entryHour = 0 : transition de jour ven (comme GoldWeekdayEffect).
 *       - entryHour > 0 : transition d'heure DANS le vendredi (prev hour <
 *         entryHour ≤ cur hour).
 *    2. Sortie à la 1re barre d'heure ≥ exitHour (fill bar suivante open —
 *       cohérent moteur). exitHour ≤ 21 pour rester intraday vendredi ;
 *       le moteur saute sam/dim, une sortie après 21:00 partirait au lundi.
 *    3. Quantity fixe 10 oz — convention famille or.
 *
 * Paramètres : entryHour / exitHour (UTC, 0..21, entryHour < exitHour).
 */
public class GoldFridayWindowStrategy implements Strategy {

    private static final double QUANTITY = 10;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final String name;
    private final String symbol;
    private final int entryHour;
    private final int exitHour;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean inTrade = false;

    public GoldFridayWindowStrategy(String name, String symbol, int entryHour, int exitHour) {
        this.name = name;
        this.symbol = symbol;
        this.entryHour = Math.max(0, entryHour);
        this.exitHour = Math.min(23, exitHour);
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int n = history.size();
        if (n < 2) return;

        Bar prev = history.get(n - 2);
        ZonedDateTime zPrev = prev.timestamp().atZone(UTC);
        ZonedDateTime zCur = bar.timestamp().atZone(UTC);
        DayOfWeek prevDow = zPrev.getDayOfWeek();
        DayOfWeek curDow = zCur.getDayOfWeek();
        int prevHour = zPrev.getHour();
        int curHour = zCur.getHour();

        boolean dayTransition = prevDow != curDow;

        // --- SORTIE (prioritaire) ---
        // Sortie de fin de fenêtre : 1re barre d'heure ≥ exitHour un vendredi
        // (la barre précédente était avant exitHour).
        if (inTrade && curDow == DayOfWeek.FRIDAY && !dayTransition
                && prevHour < exitHour && curHour >= exitHour) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close()).closeOnly());
            inTrade = false;
        }

        // --- ENTRÉE ---
        if (!inTrade) {
            if (dayTransition && curDow == DayOfWeek.FRIDAY && entryHour == 0) {
                // 1re barre du vendredi (transition jeu -> ven)
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close()));
                inTrade = true;
            } else if (curDow == DayOfWeek.FRIDAY && !dayTransition && entryHour > 0
                    && prevHour < entryHour && curHour >= entryHour) {
                // 1re barre d'heure ≥ entryHour DANS le vendredi
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close()));
                inTrade = true;
            }
        }

        // Filet de sécurité : si encore en position à la dernière barre tradée
        // du vendredi (heure ≥ 22, plus de barres réelles utiles), on coupe —
        // évite de porter la position au lundi via le skip sam/dim.
        if (inTrade && curDow == DayOfWeek.FRIDAY && curHour >= 22) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close()).closeOnly());
            inTrade = false;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override public List<Order> getPendingOrders() {
        var copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
    }
}
