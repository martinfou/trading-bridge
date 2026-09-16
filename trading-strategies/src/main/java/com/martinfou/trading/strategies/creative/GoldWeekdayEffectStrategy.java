package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldWeekdayEffect — Effet jour-de-semaine sur XAU/USD : long or pendant les
 * sessions de jours cibles (UTC).
 *
 * 📊 CONCEPT (idée nouvelle, 24e résultat) : la recherche calendaire du pipeline
 * n'a exploré que la dimension MOIS (Avr/Mai/Août/Oct/Nov). La dimension
 * JOUR-DE-SEMAINE n'a jamais été testée. La pré-validation statistique
 * (GoldWeekdayCheck, 31 août) montre sur XAU_USD 2006-2026 :
 *   - MERCREDI : +0.057%/session (IS +0.040% / OOS +0.075%, hit 53→56%) = le
 *     jour le PLUS STABLE, s'améliore en OOS ;
 *   - VENDREDI : +0.096%/session (IS +0.151% / OOS +0.041%) = le plus FORT en
 *     IS mais s'effrite en OOS (→ reste ≈ bêta or +0.04%/jour) ;
 *   - LUNDI : -0.036% = seul jour négatif (mais OOS ≈ 0, ne pas short).
 *   Contrôle paires : EUR_USD et GBP_USD sont NÉGATIFS le vendredi (-0.03% /
 *   -0.05%) → l'effet vendredi est OR-SPÉCIFIQUE (bid safe-haven de week-end,
 *   cohérent avec GoldTurtleDxyFilter : long or quand USD ferme, 28 août).
 *
 * 🔧 MÉCANISME (look-ahead safe, UTC — pitfall timezone du 31 juillet) :
 *   1. Détection de transition de jour : quand la barre PRÉCÉDENTE est la
 *      dernière barre d'un jour UTC différent de la barre courante.
 *   2. ENTRÉE long à la 1ère barre du jour cible (≈ open de la session) :
 *      prevDate == veille du jour cible.
 *   3. SORTIE à la 1ère barre du jour suivant (≈ close de la session) :
 *      prevDate == jour cible.
 *   4. Quantity fixe 10 oz (même convention GoldTurtle — pas de levier ATR).
 *   5. Swap or ≈ $0 (18 août) → les holds overnight multi-jours sont gratuits,
 *      avantage structurel vs FX (carry tue les edges overnight).
 *
 * Configurations : tradeWednesday / tradeFriday (combinaison possible).
 * PAS de SeasonalityFilter inline : le calendrier MOIS est une famille validée
 * SÉPARÉMENT (4+5+8 GBP), l'appliquer ici confondrait les deux dimensions.
 */
public class GoldWeekdayEffectStrategy implements Strategy {

    private static final double QUANTITY = 10;   // fixed oz — pas d'artefact de levier ATR
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final String name;
    private final String symbol;
    private final boolean[] days;  // [Mon..Fri]
    /**
     * Direction de la jambe de session (patch rétro-compatible du 16 sept 2026).
     * Défaut = BUY (comportement historique, famille or). BUY permet de tester
     * l'effet jour-de-semaine FX, où la structure est RISK-OFF le vendredi
     * (SELL des paires risk, cf. FxCalendarDimCheck 37e résultat).
     */
    private final Order.Side direction;
    /**
     * Quantité par trade (patch rétro-compatible du 16 sept 2026).
     * Défaut = 10 (oz, famille or). Pour le FX il faut 10 000 unités
     * (convention DateWindowSeasonal / BearishMonthsFade) → utiliser
     * {@link #withQuantity(double)}.
     */
    private double quantity = QUANTITY;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection;

    public GoldWeekdayEffectStrategy() { this("GoldWeekdayEffect", "XAU_USD", true, true); }
    public GoldWeekdayEffectStrategy(String name, String symbol) { this(name, symbol, true, true); }
    public GoldWeekdayEffectStrategy(String name, String symbol, boolean tradeWednesday, boolean tradeFriday) {
        this(name, symbol, tradeWednesday, tradeFriday, Order.Side.BUY);
    }
    /** Variante : masque de jours complet (index 0=MON .. 4=FRI). */
    public GoldWeekdayEffectStrategy(String name, String symbol, boolean[] days) {
        this(name, symbol, days, Order.Side.BUY);
    }
    public GoldWeekdayEffectStrategy(String name, String symbol, boolean tradeWednesday, boolean tradeFriday,
                                     Order.Side direction) {
        this.name = name;
        this.symbol = symbol;
        this.days = new boolean[]{false, false, tradeWednesday, false, tradeFriday};
        this.direction = direction;
    }
    /** Variante : masque de jours complet + direction de la jambe de session. */
    public GoldWeekdayEffectStrategy(String name, String symbol, boolean[] days, Order.Side direction) {
        this.name = name;
        this.symbol = symbol;
        this.days = days.clone();
        this.direction = direction;
    }

    /** Fluent : quantité par trade (10 oz or vs 10 000 unités FX). Rétro-compatible. */
    public GoldWeekdayEffectStrategy withQuantity(double q) { this.quantity = q; return this; }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int n = history.size();
        if (n < 2) return;

        Bar prev = history.get(n - 2);
        DayOfWeek prevDow = prev.timestamp().atZone(UTC).toLocalDate().getDayOfWeek();
        DayOfWeek curDow = bar.timestamp().atZone(UTC).toLocalDate().getDayOfWeek();

        // Transition de jour UTC : la barre précédente était la DERNIÈRE de son jour.
        if (prevDow == curDow) return;

        // --- SORTIE : la session du jour cible vient de se terminer ---
        // (pas de return : si le jour courant est aussi cible, on ré-entre
        //  immédiatement — nécessaire pour les masques continus type ALLDAYS)
        if (inTrade && isTargetDay(prevDow)) {
            Order.Side closeSide = (tradeDirection == Order.Side.BUY) ? Order.Side.SELL : Order.Side.BUY;
            pending.add(new Order(symbol, closeSide, Order.Type.MARKET, quantity, bar.close()).closeOnly());
            inTrade = false;
        }

        // --- ENTRÉE : aujourd'hui est le jour cible (prev = veille) ---
        if (!inTrade && isTargetDay(curDow)) {
            pending.add(new Order(symbol, direction, Order.Type.MARKET, quantity, bar.close()));
            inTrade = true;
            tradeDirection = direction;
        }
    }

    private boolean isTargetDay(DayOfWeek dow) {
        int idx = dow.getValue() - 1; // MONDAY=1 → index 0
        return idx >= 0 && idx < 5 && days[idx];
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
