package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtlePyramid — Turtle Donchian 55/20 sur XAU/USD (H1) AVEC PYRAMIDING.
 *
 * 📊 CONCEPT: Variation de GoldTurtleTrend (2026-08-17, PF 1.17 = premier edge
 *    positif post-fix, 13e résultat). La méthode Turtle originale de Richard
 *    Dennis ne se limite pas au breakout Donchian : elle pyramide — ajouter
 *    jusqu'à 4 unités, une à chaque mouvement de ½ N (ATR) EN FAVEUR de la
 *    position. C'est le cœur du système Turtle : « position sizing is more
 *    important than entry signals ».
 *
 *    Hypothèse : si l'or a une structure de trend que le FX n'a pas (prouvé
 *    par le contrôle EUR_USD de hier : PF 0.88 vs 1.17), alors le pyramiding
 *    devrait AMPLIFIER l'edge par unité de risque — les trends or durent des
 *    semaines, donc chaque ½ ATR de continuation offre une unité rentable.
 *    Le FX n'a pas cette structure → le pyramiding devrait y dégrader ou
 *    rester neutre (contrôle négatif).
 *
 * 🔧 MECHANISM (look-ahead safe : canaux + ATR sur barres STRICTEMENT avant
 *    la barre courante, pattern GoldTurtleTrend validé) :
 *    1. Entrée : close > high(55) précédent → BUY 1 unité (qty fixe)
 *                close < low(55) précédent  → SELL 1 unité
 *    2. Pyramiding : si units < maxUnits ET close > lastPyramidPrice + step*ATR
 *       (BUY) → ordre MARKET BUY même côté (le moteur scale-in via addQuantity,
 *       jamais de hedge). Miroir pour SELL. UN ajout max par barre.
 *    3. Sortie : close < low(20) précédent (BUY) → closeOnly de TOUTES les
 *       unités (units * qtyPerUnit). Miroir pour SELL.
 *    4. Quantity fixe par unité (pas de calcRiskPosition ATR H1 — pitfall
 *       AugustRiskFade : levier 7× sur positions multi-semaines).
 *
 * 🎯 Objectif : soit le pyramiding amplifie l'edge or (PF > 1.2 sur plateau,
 *    DD < 20%) → candidat catalog, soit il ajoute du risque sans PnL
 *    (DD explose, PF plat) → leçon : l'edge or est dans l'entrée, pas dans
 *    l'accumulation. Les deux sont informatifs.
 */
public class GoldTurtlePyramidStrategy implements Strategy {

    private static final double DEFAULT_QUANTITY_PER_UNIT = 10;   // oz par unité (comme GoldTurtleTrend)
    private static final int DEFAULT_ENTRY_CHANNEL = 55;   // Turtle S1 entry
    private static final int DEFAULT_EXIT_CHANNEL = 20;    // Turtle S1 exit
    private static final int DEFAULT_ATR_PERIOD = 20;      // N (Turtle)
    private static final double DEFAULT_PYRAMID_STEP = 0.5; // ½ N entre unités
    private static final int DEFAULT_MAX_UNITS = 4;        // Turtle classique

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int atrPeriod;
    private final double pyramidStep;
    private final int maxUnits;
    private final double quantityPerUnit;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private int unitsAdded = 0;
    private double lastPyramidPrice = 0;

    public GoldTurtlePyramidStrategy() { this("GoldTurtlePyramid", "XAU_USD"); }
    public GoldTurtlePyramidStrategy(String name) { this(name, "XAU_USD"); }
    public GoldTurtlePyramidStrategy(String name, String symbol) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL,
            DEFAULT_ATR_PERIOD, DEFAULT_PYRAMID_STEP, DEFAULT_MAX_UNITS, DEFAULT_QUANTITY_PER_UNIT);
    }
    public GoldTurtlePyramidStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                     int atrPeriod, double pyramidStep, int maxUnits, double quantityPerUnit) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.atrPeriod = atrPeriod;
        this.pyramidStep = pyramidStep;
        this.maxUnits = maxUnits;
        this.quantityPerUnit = quantityPerUnit;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int n = history.size();
        if (n < exitChannel + 2) return;

        // Indicateurs sur barres STRICTEMENT avant la courante (look-ahead safe)
        double prevHighEntry = maxHigh(n - 1, entryChannel);
        double prevLowEntry  = minLow(n - 1, entryChannel);
        double prevHighExit  = maxHigh(n - 1, exitChannel);
        double prevLowExit   = minLow(n - 1, exitChannel);
        double atr = atr(n - 1, atrPeriod);
        if (atr <= 0) atr = 1e-9;

        Order.Side seasonalBias = getSeasonalBias(symbol, bar.timestamp()); // XAU → null (neutre)

        // --- GESTION DE LA POSITION EXISTANTE ---
        if (inTrade) {
            // Sortie Donchian 20 : fermer TOUTES les unités
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLowExit) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHighExit) {
                closePosition(bar);
                return;
            }
            // Pyramiding : +1 unité par barre si mouvement de step*ATR en faveur
            if (unitsAdded < maxUnits) {
                if (tradeDirection == Order.Side.BUY && bar.close() > lastPyramidPrice + pyramidStep * atr) {
                    addUnit(bar, Order.Side.BUY);
                } else if (tradeDirection == Order.Side.SELL && bar.close() < lastPyramidPrice - pyramidStep * atr) {
                    addUnit(bar, Order.Side.SELL);
                }
            }
            return;
        }

        // --- ENTRÉE (breakout Donchian 55) : 1ère unité ---
        if (bar.close() > prevHighEntry) {
            if (seasonalBias == null || seasonalBias == Order.Side.BUY) {
                enter(bar, Order.Side.BUY);
            }
        } else if (bar.close() < prevLowEntry) {
            if (seasonalBias == null || seasonalBias == Order.Side.SELL) {
                enter(bar, Order.Side.SELL);
            }
        }
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

    /** Max high sur les `period` barres strictement avant l'index `end` (exclusif). */
    private double maxHigh(int end, int period) {
        double max = 0;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (history.get(i).high() > max) max = history.get(i).high();
        }
        return max;
    }

    /** Min low sur les `period` barres strictement avant l'index `end` (exclusif). */
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

    /**
     * Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias()
     * (trading-intelligence), copié ici car trading-strategies ne peut pas
     * dépendre de trading-intelligence (cycle Maven). Même pattern que
     * GoldTurtleTrendStrategy. XAU_USD → aucun pattern enregistré → neutre.
     */
    protected Order.Side getSeasonalBias(String sym, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null; // XAU_USD → neutre
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
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        unitsAdded = 0;
        lastPyramidPrice = 0;
    }
}
