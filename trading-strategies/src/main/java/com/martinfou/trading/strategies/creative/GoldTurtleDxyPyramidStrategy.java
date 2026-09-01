package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleDxyPyramid — GoldTurtleTrend + filtre régime DXY OPPOSITE + PYRAMIDING ½ ATR.
 *
 * 📊 CONCEPT (mardi 1er septembre 2026, variation — piste ouverte du 28 août) :
 *    Le filtre DXY OPPOSITE (GoldTurtleDxyFilterStrategy, 23e résultat) améliore
 *    la capture Turtle de l'or : PF 1.17 → 1.34, DD 9.35 → 8.06%, net +$18.1K
 *    en 830 trades (vs 1594 baseline). Il sélectionne les trades des régimes
 *    USD FERMES (risk-off USD-refuge/or-refuge) — la moitié des trades baseline
 *    est coupée.
 *
 *    Le pyramiding (GoldTurtlePyramidStrategy, 14e résultat) est un amplificateur
 *    d'exposition : maxUnits=2, step=½ ATR → net +$28.8K (+62% vs baseline) pour
 *    DD 15.7% (sous gate 20%), PF inchangé ~1.16.
 *
 *    Question : le pyramiding sur les trades FILTRÉS (OPPOSITE) amplifie-t-il le
 *    net SANS exploser le DD ? Le filtre coupe déjà les trades de moitié — si les
 *    trades restants sont de meilleure qualité, le scale-in pourrait ajouter de
 *    l'exposition au bon moment. Si le pyramiding n'ajoute que du bruit (comme en
 *    FX), le PF devrait rester plat et le DD grimper → Pattern C (amplificateur,
 *    pas alpha) — documenter.
 *
 * 🔧 MÉCANIQUE (look-ahead safe, fusion GoldTurtleDxyFilter + GoldTurtlePyramid) :
 *    1. Entrée initiale : breakout Donchian 55 (1 unité), gate par filtre DXY
 *       (mêmes modes ALIGNED/OPPOSITE/OFF que GoldTurtleDxyFilterStrategy).
 *    2. Pyramiding : +1 unité par barre si close > lastPyramidPrice + step*ATR
 *       (BUY), miroir SELL. maxUnits paramétrable. Le filtre DXY ne gate QUE
 *       l'entrée initiale (le pyramiding suit la position existante).
 *    3. Sortie : close < low(20) précédent (BUY) → closeOnly de TOUTES les unités.
 *    4. Quantity fixe par unité (10 oz) — pas de calcRiskPosition (pitfall
 *       AugustRiskFade : levier 7× sur positions multi-semaines).
 *
 * 🎯 Verdicts possibles :
 *    - OPPOSITE + pyramiding 2u : PF ≥ 1.2, DD < 20%, net > OPPOSITE 1u →
 *      amplification réelle sur trades filtrés → config recommandée famille or.
 *    - PF ≈ OPPOSITE 1u, net ↑ proportionnel au DD ↑ → Pattern C confirmé
 *      (le pyramiding reste un amplificateur, le filtre fait le travail).
 *    - ALIGNED + pyramiding : doit rester mauvais (contrôle directionnel).
 */
public class GoldTurtleDxyPyramidStrategy implements Strategy {

    public static final int MODE_ALIGNED = 0;
    public static final int MODE_OPPOSITE = 1;
    public static final int MODE_OFF = 2;

    private static final double QUANTITY_PER_UNIT = 10;   // oz par unité
    private static final int DEFAULT_ENTRY_CHANNEL = 55;
    private static final int DEFAULT_EXIT_CHANNEL = 20;
    private static final int DEFAULT_ATR_PERIOD = 20;
    private static final double DEFAULT_PYRAMID_STEP = 0.5;
    private static final int DEFAULT_MAX_UNITS = 2;        // sweet spot pyramide or

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int atrPeriod;
    private final double pyramidStep;
    private final int maxUnits;
    private final double quantityPerUnit;
    private final int filterMode;
    private final int dxySmaPeriod;

    private final List<Long> dxyTs = new ArrayList<>();
    private final List<Double> dxyVal = new ArrayList<>();
    private int dxyPtr = -1;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private int unitsAdded = 0;
    private double lastPyramidPrice = 0;

    /** MODE_ALIGNED, SMA 500 H1 (~21 jours), maxUnits=2. */
    public GoldTurtleDxyPyramidStrategy(String name, String symbol, Map<Long, Double> dxy) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL,
            DEFAULT_ATR_PERIOD, DEFAULT_PYRAMID_STEP, DEFAULT_MAX_UNITS, QUANTITY_PER_UNIT,
            MODE_ALIGNED, 500, dxy);
    }

    public GoldTurtleDxyPyramidStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                        int atrPeriod, double pyramidStep, int maxUnits, double quantityPerUnit,
                                        int filterMode, int dxySmaPeriod, Map<Long, Double> dxy) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.atrPeriod = atrPeriod;
        this.pyramidStep = pyramidStep;
        this.maxUnits = Math.max(1, maxUnits);
        this.quantityPerUnit = quantityPerUnit;
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
        double atr = atr(n - 1, atrPeriod);
        if (atr <= 0) atr = 1e-9;

        if (inTrade) {
            // Sortie Donchian 20 : fermer TOUTES les unités
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLow20) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHigh20) {
                closePosition(bar);
                return;
            }
            // Pyramiding : +1 unité par barre si step*ATR en faveur
            if (unitsAdded < maxUnits) {
                if (tradeDirection == Order.Side.BUY && bar.close() > lastPyramidPrice + pyramidStep * atr) {
                    addUnit(bar, Order.Side.BUY);
                } else if (tradeDirection == Order.Side.SELL && bar.close() < lastPyramidPrice - pyramidStep * atr) {
                    addUnit(bar, Order.Side.SELL);
                }
            }
            return;
        }

        // Entrée : breakout Donchian 55, gate par filtre DXY
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

    /** Filtre de régime USD sur l'entrée INITIALE uniquement (look-ahead safe). */
    private boolean filterAllows(Order.Side side) {
        if (filterMode == MODE_OFF || dxyPtr < 0) return true;
        int from = Math.max(0, dxyPtr - dxySmaPeriod + 1);
        int count = dxyPtr - from + 1;
        double sum = 0;
        for (int i = from; i <= dxyPtr; i++) sum += dxyVal.get(i);
        double sma = sum / count;
        double dxyNow = dxyVal.get(dxyPtr);
        boolean weakUsd = dxyNow < sma;
        if (filterMode == MODE_OPPOSITE) weakUsd = !weakUsd;
        if (side == Order.Side.BUY) return weakUsd;
        return !weakUsd;
    }

    private void enter(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, quantityPerUnit, bar.close()));
        inTrade = true;
        tradeDirection = side;
        unitsAdded = 1;
        lastPyramidPrice = bar.close();
    }

    /** Scale-in même côté : le moteur cumule via Position.addQuantity (jamais de hedge). */
    private void addUnit(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, quantityPerUnit, bar.close()));
        unitsAdded++;
        lastPyramidPrice = bar.close();
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        double totalQty = unitsAdded * quantityPerUnit;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, totalQty, bar.close()).closeOnly());
        inTrade = false;
        unitsAdded = 0;
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

    /** ATR = moyenne des True Ranges sur les `period` barres strictement avant `end`. */
    private double atr(int end, int period) {
        int from = Math.max(1, end - period);
        double sum = 0;
        int count = 0;
        for (int i = from; i < end; i++) {
            Bar b = history.get(i);
            Bar prev = history.get(i - 1);
            double tr = b.high() - b.low();
            tr = Math.max(tr, Math.abs(b.high() - prev.close()));
            tr = Math.max(tr, Math.abs(b.low() - prev.close()));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
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
        unitsAdded = 0;
        lastPyramidPrice = 0;
        dxyPtr = -1;
    }
}
