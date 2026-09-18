package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * MondayReversionStrategy — RÉVERSION DU LUNDI conditionnelle à la semaine précédente (FX).
 *
 * 📊 CONCEPT (deep dive vendredi, 39e résultat — 18 sept 2026)
 * Découverte NON PRÉVUE du 38e résultat (CrossAssetWeekendFactorCheck, 17 sept), partie F :
 * le LUNDI est un jour de RÉVERSION du lundi-jeudi précédent, sur 8/8 paires FX, des DEUX
 * côtés du split IS/OOS :
 *   W4 = rendement du lundi-jeudi de la semaine écoulée (close jeudi / close vendredi 2 sem. avant).
 *   | W4<0 -> lundi +0.086% (GBP_USD t 2.55, EUR t 2.73, USD_JPY t 2.43, USD_CHF t 2.33)
 *   | W4>0 -> lundi −0.045/−0.075% (cellules miroir négatives)
 * L'effet est INDÉPENDANT du fade du vendredi (F1), l'OR en est exempt (6e contrôle d'instrument
 * de la famille or) et USD_CAD — neutre sur le fade — réagit ici : les deux phénomènes sont
 * indépendants. Rare structure IS/OOS stable des DEUX côtés (les 5 derniers effets calendaires
 * mouraient au split).
 *
 * 🔧 MÉCANISME (look-ahead safe, UTC)
 *  1. Suivi des closes journaliers (dernière barre de chaque jour UTC) + liste ordonnée des
 *     séances complétées (jours ouvrables ; l'engine saute déjà sam/dim pour le FX).
 *  2. À la TRANSITION de jour vers un jour cible (défaut : LUNDI) : signal
 *     W4 = (close[sessions.size()-2] − close[sessions.size()-6]) / close[sessions.size()-6]
 *     → pour un lundi c'est exactement le rendement lundi-jeudi de la semaine écoulée
 *     (size-2 = jeudi de la semaine écoulée, size-6 = vendredi d'il y a 2 semaines) —
 *     réplique EXACTE de la formule de CrossAssetWeekendFactorCheck partie F.
 *  3. Direction = REVERSION : W4<0 → BUY, W4>0 → SELL (MOMENTUM = contrôle inverse).
 *  4. SORTIE à la transition suivante (≈ 23 h de hold : lundi ~01:00 → mardi 00:00 open).
 *  5. Quantity fixe 10 000 unités (convention DateWindowSeasonal/BearishMonthsFade) — les
 *     fenêtres calendaires pures n'utilisent JAMAIS calcRiskPosition (pitfall sizing du 12 août).
 *
 * ⚠️ Configurations de contrôle
 *  - policy : REVERSION (défaut), REVERSION_LONG_ONLY / SHORT_ONLY (décomposition des jambes),
 *    MOMENTUM (contrôle directionnel), ALWAYS_LONG / ALWAYS_SHORT (contrôle de dérive).
 *  - days   : masque jour-cible (défaut = LUNDI seul) → contrôle de SPÉCIFICITÉ DU JOUR.
 *  - signalLagWeeks : décale la fenêtre signal de N semaines (contrôle de falsification :
 *    une semaine périmée ne doit plus rien donner).
 *  - minAbsSignal : seuil sur |W4| (balayage de MAGNITUDE — réponse monotone attendue).
 *
 * PAS de SeasonalityFilter inline : la dimension testée est le JOUR/la semaine, mélanger le
 * calendrier MOIS confondrait les deux dimensions (même choix que GoldWeekdayEffect/FxFridayFade).
 */
public class MondayReversionStrategy implements Strategy {

    /** Politique de direction de la jambe de session. */
    public enum Policy { REVERSION, REVERSION_LONG_ONLY, REVERSION_SHORT_ONLY, MOMENTUM, ALWAYS_LONG, ALWAYS_SHORT }

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final double DEFAULT_QTY = 10_000;   // unités FX

    private final String name;
    private final String symbol;

    private Policy policy = Policy.REVERSION;
    /** Masque jour-cible [0]=MON..[4]=FRI. Défaut : LUNDI seul. */
    private boolean[] days = {true, false, false, false, false};
    private double minAbsSignal = 0.0;
    private int signalLagWeeks = 0;
    private double quantity = DEFAULT_QTY;
    /**
     * Filtre F1 (patch du 18 sept, issu de la décomposition Python) : n'entrer que si le
     * VENDREDI précédent était BAISSIER. La cellule 2×2 la plus forte de la pré-validation
     * était F1<0|W4<0 (+6.4 bp sur 8 paires, n=2013) contre +1.7 bp pour F1>0|W4<0 :
     * l'hypothèse devient « rebond du lundi après une semaine RISK-OFF (semaine ET vendredi
     * baissiers) », pas « réversion mécanique de la semaine ». Défaut false = rétro-compatible.
     */
    private boolean requireFridayDown = false;

    private final TreeMap<LocalDate, Double> closes = new TreeMap<>();
    private final List<LocalDate> sessions = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private LocalDate lastDate;
    private boolean inTrade;
    private Order.Side tradeDirection;

    // Diagnostics (probe / doctor)
    private int entries;
    private int exits;
    private int skippedByMagnitude;
    private int skippedNoHistory;
    private int skippedByFriday;
    private double lastSignal = Double.NaN;
    private double lastFriday = Double.NaN;

    public MondayReversionStrategy() { this("MondayReversion", "EUR_USD"); }
    public MondayReversionStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    // ------------------------------------------------------------- fluent API
    public MondayReversionStrategy withPolicy(Policy p) { this.policy = p; return this; }
    public MondayReversionStrategy withDays(boolean[] mask) { this.days = mask.clone(); return this; }
    public MondayReversionStrategy withQuantity(double q) { this.quantity = q; return this; }
    public MondayReversionStrategy withMinAbsSignal(double s) { this.minAbsSignal = s; return this; }
    public MondayReversionStrategy withSignalLagWeeks(int l) { this.signalLagWeeks = Math.max(0, l); return this; }
    /** Filtre F1 : n'entrer que si le vendredi précédent était baissier (patch 18 sept). */
    public MondayReversionStrategy withRequireFridayDown(boolean b) { this.requireFridayDown = b; return this; }

    // ------------------------------------------------------------- diagnostics
    public int entryCount() { return entries; }
    public int exitCount() { return exits; }
    public int skippedByMagnitude() { return skippedByMagnitude; }
    public int skippedNoHistory() { return skippedNoHistory; }
    public int skippedByFriday() { return skippedByFriday; }
    public double lastSignal() { return lastSignal; }
    public double lastFridayReturn() { return lastFriday; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        LocalDate curDate = bar.timestamp().atZone(UTC).toLocalDate();
        closes.put(curDate, bar.close());          // close courant du jour (dernière barre = close du jour)
        if (lastDate == null) { lastDate = curDate; return; }
        if (curDate.equals(lastDate)) return;       // même jour UTC : rien à faire

        // --- transition de jour UTC ---
        LocalDate prevDate = lastDate;
        lastDate = curDate;
        if (isWeekday(prevDate)) sessions.add(prevDate);

        // SORTIE : la séance précédente était un jour cible (≈ close de la session)
        if (inTrade) {
            Order.Side closeSide = (tradeDirection == Order.Side.BUY) ? Order.Side.SELL : Order.Side.BUY;
            pending.add(new Order(symbol, closeSide, Order.Type.MARKET, quantity, bar.close()).closeOnly());
            inTrade = false;
            tradeDirection = null;
            exits++;
        }

        // ENTRÉE : le jour courant est un jour cible
        if (!isTargetDay(curDate.getDayOfWeek())) return;

        int baseIdx = sessions.size() - 6 - 5 * signalLagWeeks;   // vendredi d'il y a 2 semaines (lag 0)
        int endIdx = sessions.size() - 2 - 5 * signalLagWeeks;    // jeudi de la semaine écoulée (lag 0)
        if (baseIdx < 0 || endIdx < 0) { skippedNoHistory++; return; }

        Double cA = closes.get(sessions.get(baseIdx));
        Double cB = closes.get(sessions.get(endIdx));
        if (cA == null || cB == null || cA <= 0) { skippedNoHistory++; return; }

        double w4 = (cB - cA) / cA;
        lastSignal = w4;
        if (Math.abs(w4) < minAbsSignal) { skippedByMagnitude++; return; }

        // Filtre F1 : vendredi précédent baissier (dernière séance = vendredi)
        int fIdx = sessions.size() - 1;
        Double cFri = closes.get(sessions.get(fIdx));
        Double cThu = closes.get(sessions.get(fIdx - 1));
        if (cFri != null && cThu != null && cThu > 0) {
            double f1 = (cFri - cThu) / cThu;
            lastFriday = f1;
            if (requireFridayDown && f1 >= 0) { skippedByFriday++; return; }
        }

        Order.Side dir = directionFor(w4);
        if (dir == null) return;                    // jambe filtrée par la politique

        pending.add(new Order(symbol, dir, Order.Type.MARKET, quantity, bar.close()));
        inTrade = true;
        tradeDirection = dir;
        entries++;
    }

    /** Direction de la jambe selon la politique et le signe du signal. */
    private Order.Side directionFor(double w4) {
        boolean up = w4 < 0;    // semaine BAISSIÈRE -> réversion haussière
        switch (policy) {
            case REVERSION:            return up ? Order.Side.BUY : Order.Side.SELL;
            case REVERSION_LONG_ONLY:  return up ? Order.Side.BUY : null;
            case REVERSION_SHORT_ONLY: return up ? null : Order.Side.SELL;
            case MOMENTUM:             return up ? Order.Side.SELL : Order.Side.BUY;
            case ALWAYS_LONG:          return Order.Side.BUY;
            case ALWAYS_SHORT:         return Order.Side.SELL;
            default:                   return null;
        }
    }

    private boolean isTargetDay(DayOfWeek dow) {
        int idx = dow.getValue() - 1;               // MONDAY=1 -> 0
        return idx >= 0 && idx < 5 && days[idx];
    }

    private static boolean isWeekday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override public List<Order> getPendingOrders() {
        var copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override public void reset() {
        closes.clear();
        sessions.clear();
        pending.clear();
        lastDate = null;
        inTrade = false;
        tradeDirection = null;
        entries = 0; exits = 0; skippedByMagnitude = 0; skippedNoHistory = 0; skippedByFriday = 0;
        lastSignal = Double.NaN;
        lastFriday = Double.NaN;
    }
}
