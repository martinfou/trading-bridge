package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldWeekdayDxy — GoldWeekdayEffect (long or sessions jours cibles, config
 * VENDREDI) conditionné par le RÉGIME USD (DXY synthétique vs sa moyenne).
 *
 * 📊 CONCEPT (deep dive vendredi 4 septembre 2026, 28e résultat) :
 *    GoldWeekdayEffect (31 août, EXPLORE) a montré un bid safe-haven de
 *    week-end sur l'or : VENDREDI long PF 1.27 (WR 56.9%, DD 5.18%, 1041
 *    trades, net +$15 131), EUR_USD/GBP_USD NÉGATIFS le même jour, FRI
 *    survit le bear 2013-15 quand la bêta perd -16%. La question ouverte
 *    (notée 31 août, 1er et 3 sept) : ce bid vendredi est-il une
 *    manifestation du MÊME régime « twin-refuge USD/or » qui conditionne
 *    l'edge Turtle (GoldTurtleDxyFilter OPPOSITE PF 1.34 le 28 août,
 *    GoldTurtleRiskIndex REJECT le 3 sept : le risk-off AUD/JPY ne
 *    conditionne PAS l'or — seul le dollar qui monte PARCE QUE refuge
 *    sélectionne les trends or quote-USD) ?
 *
 *    Design long-only (GoldWeekdayEffect n'a que des longs) :
 *    - MODE_ALIGNED  : long vendredi SEULEMENT si DXY < SMA_N (USD faible
 *                      → hypothèse « bear dollar » naïve : or fort quand
 *                      le dollar est faible).
 *    - MODE_OPPOSITE : long vendredi SEULEMENT si DXY > SMA_N (USD ferme
 *                      → hypothèse « twin-refuge » : le bid safe-haven de
 *                      week-end est or + USD ensemble, comme les trends
 *                      Turtle du 28 août).
 *    - MODE_OFF      : baseline GoldWeekdayEffect FRI (aucun filtre) —
 *                      doit reproduire PF 1.27 / +$15 131 / 1041 trades.
 *
 *    Interprétation possible des verdicts :
 *    - OPPOSITE améliore (PF/DD/net) et ALIGNED détruit → le Friday bid
 *      est un twin-refuge déguisé ; la dimension jour-de-semaine et la
 *      dimension régime USD sont LE MÊME phénomène → overlay unique.
 *    - OPPOSITE ≈ ALIGNED ≈ OFF (à count comparable) → dimensions
 *      indépendantes ; le Friday bid n'est pas un effet de régime.
 *    - ALIGNED et OPPOSITE tous deux profitables → Pattern C (sélection
 *      de sous-ensemble) → le filtre ne porte pas d'information.
 *
 * 🔧 MÉCANIQUE (look-ahead safe, UTC — mêmes conventions que les deux
 *    parents) :
 *    1. Détection de transition de jour UTC (barre précédente = dernière
 *       de son jour ≠ jour courant) — identique GoldWeekdayEffect.
 *    2. ENTRÉE long à la 1ère barre du jour cible, gateée par le filtre
 *       DXY (entrées uniquement, sorties inchangées).
 *    3. SORTIE à la 1ère barre du jour suivant.
 *    4. Le filtre n'utilise QUE le DXY de la barre STRICTEMENT antérieure
 *       (pointeur qui avance tant que ts(dxy) < ts(barre courante)) —
 *       identique GoldTurtleDxyFilter (28 août).
 *    5. Quantity fixe 10 oz ; swap or ≈ $0 → holds overnight gratuits.
 */
public class GoldWeekdayDxyStrategy implements Strategy {

    public static final int MODE_ALIGNED = 0;
    public static final int MODE_OPPOSITE = 1;
    public static final int MODE_OFF = 2;

    private static final double QUANTITY = 10;   // fixed oz — no ATR leverage artifact
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final String name;
    private final String symbol;
    private final boolean[] days;   // [Mon..Fri]
    private final int filterMode;
    private final int dxySmaPeriod; // in H1 bars

    private final List<Long> dxyTs = new ArrayList<>();
    private final List<Double> dxyVal = new ArrayList<>();
    private int dxyPtr = -1;        // last DXY index with ts < current bar ts

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean inTrade = false;

    /** Config par défaut : VENDREDI, MODE_ALIGNED, SMA 500 H1 (~21 jours). */
    public GoldWeekdayDxyStrategy(String name, String symbol, Map<Long, Double> dxy) {
        this(name, symbol, new boolean[]{false, false, false, false, true}, MODE_ALIGNED, 500, dxy);
    }

    public GoldWeekdayDxyStrategy(String name, String symbol, int filterMode, int dxySmaPeriod,
                                  Map<Long, Double> dxy) {
        this(name, symbol, new boolean[]{false, false, false, false, true}, filterMode, dxySmaPeriod, dxy);
    }

    public GoldWeekdayDxyStrategy(String name, String symbol, boolean[] days, int filterMode,
                                  int dxySmaPeriod, Map<Long, Double> dxy) {
        this.name = name;
        this.symbol = symbol;
        this.days = days.clone();
        this.filterMode = filterMode;
        this.dxySmaPeriod = Math.max(1, dxySmaPeriod);
        if (dxy != null) {
            for (Map.Entry<Long, Double> e : dxy.entrySet()) {
                dxyTs.add(e.getKey());
                dxyVal.add(e.getValue());
            }
        }
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        advanceDxy(bar.timestamp().toEpochMilli());
        history.add(bar);
        int n = history.size();
        if (n < 2) return;

        Bar prev = history.get(n - 2);
        DayOfWeek prevDow = prev.timestamp().atZone(UTC).toLocalDate().getDayOfWeek();
        DayOfWeek curDow = bar.timestamp().atZone(UTC).toLocalDate().getDayOfWeek();

        // Transition de jour UTC : la barre précédente était la DERNIÈRE de son jour.
        if (prevDow == curDow) return;

        // --- SORTIE : la session du jour cible vient de se terminer ---
        if (inTrade && isTargetDay(prevDow)) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close()).closeOnly());
            inTrade = false;
        }

        // --- ENTRÉE : aujourd'hui est le jour cible (prev = veille), gateée DXY ---
        if (!inTrade && isTargetDay(curDow) && filterAllows()) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close()));
            inTrade = true;
        }
    }

    private boolean isTargetDay(DayOfWeek dow) {
        int idx = dow.getValue() - 1; // MONDAY=1 → index 0
        return idx >= 0 && idx < 5 && days[idx];
    }

    /** Avance le pointeur DXY : toutes les entrées avec ts < barre courante. */
    private void advanceDxy(long barTs) {
        while (dxyPtr + 1 < dxyVal.size() && dxyTs.get(dxyPtr + 1) < barTs) {
            dxyPtr++;
        }
    }

    /**
     * Filtre de régime USD sur les entrées. Utilise le DXY de la barre
     * STRICTEMENT antérieure (dxyPtr) — look-ahead safe par construction.
     * Long-only : ALIGNED autorise le long quand USD faible, OPPOSITE
     * quand USD ferme, OFF toujours.
     */
    private boolean filterAllows() {
        if (filterMode == MODE_OFF || dxyPtr < 0) return true;
        int from = Math.max(0, dxyPtr - dxySmaPeriod + 1);
        int count = dxyPtr - from + 1;
        double sum = 0;
        for (int i = from; i <= dxyPtr; i++) sum += dxyVal.get(i);
        double sma = sum / count;
        double dxyNow = dxyVal.get(dxyPtr);
        boolean weakUsd = dxyNow < sma;   // DXY sous sa moyenne = USD faible
        if (filterMode == MODE_OPPOSITE) weakUsd = !weakUsd;
        return weakUsd;                    // long or : USD faible (ou inverse en OPPOSITE)
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
        dxyPtr = -1;
    }
}
