package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleDxyFilter — GoldTurtleTrend (Turtle Donchian 55/20, XAU/USD H1)
 * conditionné par le RÉGIME USD (indice dollar synthétique).
 *
 * 📊 CONCEPT (deep dive vendredi 28 août 2026, suite GoldCrossQuote du 27) :
 *    GoldCrossQuote a montré que l'edge Turtle de l'or (PF 1.17 XAU/USD vs
 *    0.97-1.08 sur les 7 crosses XAU/XXX) est concentré dans le QUOTE USD —
 *    le « bull or » est en partie un « bear dollar » déguisé. Question :
 *    un filtre de régime USD (DXY synthétique vs sa moyenne mobile) rend-il
 *    la capture Turtle plus propre ?
 *
 *    - MODE_ALIGNED  : BUY seulement si DXY < SMA_N (USD faible → or fort),
 *                      SELL seulement si DXY > SMA_N (USD fort → or faible).
 *                      Hypothèse : l'edge or vit dans les régimes USD extrêmes.
 *    - MODE_OPPOSITE : contrôle inverse (méthode anti-artefact du 26 août,
 *                      RegimeFilteredSeasonal) — si le filtre porte de
 *                      l'information directionnelle, le contrôle OPPOSITE
 *                      DOIT détruire ou inverser l'edge. S'il reste
 *                      profitable, le filtre ne fait que sélectionner un
 *                      sous-ensemble (Pattern C).
 *    - MODE_OFF      : baseline GoldTurtleTrend (aucun filtre).
 *
 * 🔧 MÉCANIQUE (look-ahead safe) :
 *    1. Entrées/sorties Turtle Donchian 55/20 identiques à GoldTurtleTrend
 *       (canaux calculés sur les barres STRICTEMENT antérieures).
 *    2. Le DXY synthétique (pondération classique EUR 0.576 / JPY 0.136 /
 *       GBP 0.119 / CAD 0.091 / CHF 0.036, SEK absent → renormalisé) est
 *       construit par le runner sur les closes H1 alignées par timestamp et
 *       passé au constructeur.
 *    3. Le filtre n'utilise QUE le DXY de la barre STRICTEMENT antérieure
 *       (pointeur qui avance tant que ts(dxy) < ts(barre courante)) — le
 *       régime est connu à l'instant de la décision, jamais la barre en
 *       cours. Le filtre gate les ENTRÉES uniquement (sorties inchangées).
 *
 * 🎯 Verdicts possibles :
 *    - ALIGNED améliore + OPPOSITE détruit → l'edge or est (partiellement)
 *      un edge de régime USD → config recommandée.
 *    - ALIGNED ≈ OPPOSITE ≈ baseline → filtre inerte (régime USD lent
 *      n'explique pas la capture Turtle).
 *    - ALIGNED et OPPOSITE tous deux profitables → Pattern C (sélection de
 *      sous-ensemble, pas d'information directionnelle) → REJECT.
 */
public class GoldTurtleDxyFilterStrategy implements Strategy {

    public static final int MODE_ALIGNED = 0;
    public static final int MODE_OPPOSITE = 1;
    public static final int MODE_OFF = 2;

    private static final double QUANTITY = 10;       // fixed oz — no ATR leverage artifact
    private static final int DEFAULT_ENTRY_CHANNEL = 55;
    private static final int DEFAULT_EXIT_CHANNEL = 20;

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int filterMode;
    private final int dxySmaPeriod;   // in H1 bars

    private final List<Long> dxyTs = new ArrayList<>();
    private final List<Double> dxyVal = new ArrayList<>();
    private int dxyPtr = -1;          // last DXY index with ts < current bar ts

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;

    /** MODE_ALIGNED, SMA 500 H1 (~21 jours). */
    public GoldTurtleDxyFilterStrategy(String name, String symbol, Map<Long, Double> dxy) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL, MODE_ALIGNED, 500, dxy);
    }

    public GoldTurtleDxyFilterStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                       int filterMode, int dxySmaPeriod, Map<Long, Double> dxy) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
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
        if (n < exitChannel + 2) return;

        double prevHigh55 = maxHigh(n - 1, entryChannel);
        double prevLow55  = minLow(n - 1, entryChannel);
        double prevHigh20 = maxHigh(n - 1, exitChannel);
        double prevLow20  = minLow(n - 1, exitChannel);

        if (inTrade) {
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLow20) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHigh20) {
                closePosition(bar);
                return;
            }
            return;
        }

        if (bar.close() > prevHigh55) {
            if (filterAllows(Order.Side.BUY)) enter(bar, Order.Side.BUY);
        } else if (bar.close() < prevLow55) {
            if (filterAllows(Order.Side.SELL)) enter(bar, Order.Side.SELL);
        }
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
     * Retourne true si le trade est autorisé.
     */
    private boolean filterAllows(Order.Side side) {
        if (filterMode == MODE_OFF || dxyPtr < 0) return true;
        int from = Math.max(0, dxyPtr - dxySmaPeriod + 1);
        int count = dxyPtr - from + 1;
        double sum = 0;
        for (int i = from; i <= dxyPtr; i++) sum += dxyVal.get(i);
        double sma = sum / count;
        double dxyNow = dxyVal.get(dxyPtr);
        boolean weakUsd = dxyNow < sma;   // DXY sous sa moyenne = USD faible
        if (filterMode == MODE_OPPOSITE) weakUsd = !weakUsd;
        if (side == Order.Side.BUY) return weakUsd;   // long or : USD faible
        return !weakUsd;                               // short or : USD fort
    }

    private void enter(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, QUANTITY, bar.close()));
        inTrade = true;
        tradeDirection = side;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, QUANTITY, bar.close()).closeOnly());
        inTrade = false;
    }

    private double maxHigh(int end, int period) {
        double max = 0;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (history.get(i).high() > max) max = history.get(i).high();
        }
        return max;
    }

    private double minLow(int end, int period) {
        double min = Double.MAX_VALUE;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (history.get(i).low() < min) min = history.get(i).low();
        }
        return min;
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
        tradeDirection = Order.Side.BUY;
        dxyPtr = -1;
    }
}
