package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtlePartialExit — GoldTurtlePyramid AVEC SORTIE PARTIELLE S2 (XAU/USD H1).
 *
 * 📊 CONCEPT: Deep dive du 21 août 2026. La piste ouverte du 18 août (GoldTurtlePyramid)
 *    identifiait le problème : maxUnits=4 (Turtle classique) double le net (+$39.1K)
 *    mais triple le DD (29.3% > gate 20%). Le sweet spot 2u (PF 1.16, DD 15.7%,
 *    net +$28.8K) restait en-deçà. L'idée S2 (Turtle System 2) : sortir la MOITIÉ
 *    de la position sur un canal rapide (Donchian 10), l'autre moitié sur le canal
 *    lent (Donchian 20). Hypothèse : le pyramiding amplifie le net ET le DD
 *    proportionnellement (démontré le 18 août) ; une sortie en 2 paliers devrait
 *    verrouiller une partie du PnL avant les retracements profonds → réduire le DD
 *    des configs agressives (3-4u) SOUS le gate 20% tout en gardant le net.
 *
 * 🔧 MECHANISM (look-ahead safe — canaux + ATR sur barres strictement avant la
 *    barre courante, pattern GoldTurtlePyramid validé) :
 *    1. Entrée : breakout Donchian 55 (1 unité, qty fixe 10 oz)
 *    2. Pyramiding : +1 unité par ½ ATR en faveur, jusqu'à maxUnits
 *    3. SORTIE PARTIELLE : close < low(10) précédent (BUY) avec unitsAdded >= 2
 *       → closeOnly de (unitsAdded / 2) * qty. Flag partialExitDone. Plus de
 *       pyramiding après (mode réduction).
 *    4. SORTIE FINALE : close < low(20) précédent (BUY) → closeOnly du RESTE.
 *    Miroir pour SELL.
 *
 * ⚠️ Swap XAU ≈ 0 (démontré 18 août) → les sorties multi-paliers ne créent pas
 *    d'artefact de carry. Le comptage trades du moteur compte chaque réduction
 *    partielle comme un trade (REDUCE_ONLY) — les trades totaux augmenteront,
 *    c'est attendu.
 */
public class GoldTurtlePartialExitStrategy implements Strategy {

    private static final double DEFAULT_QUANTITY_PER_UNIT = 10;
    private static final int DEFAULT_ENTRY_CHANNEL = 55;
    private static final int DEFAULT_EXIT_CHANNEL = 20;
    private static final int DEFAULT_PARTIAL_EXIT_CHANNEL = 10;
    private static final int DEFAULT_ATR_PERIOD = 20;
    private static final double DEFAULT_PYRAMID_STEP = 0.5;
    private static final int DEFAULT_MAX_UNITS = 4;

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int partialExitChannel;
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
    private boolean partialExitDone = false;

    public GoldTurtlePartialExitStrategy() { this("GoldTurtlePartialExit", "XAU_USD"); }
    public GoldTurtlePartialExitStrategy(String name, String symbol) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL, DEFAULT_PARTIAL_EXIT_CHANNEL,
            DEFAULT_ATR_PERIOD, DEFAULT_PYRAMID_STEP, DEFAULT_MAX_UNITS, DEFAULT_QUANTITY_PER_UNIT);
    }
    public GoldTurtlePartialExitStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                         int partialExitChannel, int atrPeriod, double pyramidStep,
                                         int maxUnits, double quantityPerUnit) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.partialExitChannel = partialExitChannel;
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
        double prevHighPartial = maxHigh(n - 1, partialExitChannel);
        double prevLowPartial  = minLow(n - 1, partialExitChannel);
        double atr = atr(n - 1, atrPeriod);
        if (atr <= 0) atr = 1e-9;

        Order.Side seasonalBias = getSeasonalBias(symbol, bar.timestamp()); // XAU → null

        // --- GESTION DE LA POSITION EXISTANTE ---
        if (inTrade) {
            // SORTIE FINALE : Donchian 20 → fermer le reste (toujours prioritaire)
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLowExit) {
                closeRemaining(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHighExit) {
                closeRemaining(bar);
                return;
            }
            // SORTIE PARTIELLE : Donchian 10 → fermer la moitié (une seule fois)
            if (!partialExitDone && unitsAdded >= 2) {
                if (tradeDirection == Order.Side.BUY && bar.close() < prevLowPartial) {
                    closeHalf(bar);
                } else if (tradeDirection == Order.Side.SELL && bar.close() > prevHighPartial) {
                    closeHalf(bar);
                }
            }
            // Pyramiding : seulement avant la sortie partielle (mode réduction après)
            if (!partialExitDone && unitsAdded < maxUnits) {
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
        partialExitDone = false;
    }

    /** Scale-in même côté : le moteur cumule via Position.addQuantity. */
    private void addUnit(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, quantityPerUnit, bar.close()));
        unitsAdded++;
        lastPyramidPrice = bar.close();
    }

    /** Sortie partielle : fermer la MOITIÉ des unités (arrondi inférieur, min 1). */
    private void closeHalf(Bar bar) {
        int halfUnits = Math.max(1, unitsAdded / 2);
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        double qty = halfUnits * quantityPerUnit;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, qty, bar.close()).closeOnly());
        unitsAdded -= halfUnits;
        partialExitDone = true;
    }

    /** Sortie finale : fermer TOUTES les unités restantes. */
    private void closeRemaining(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        double totalQty = unitsAdded * quantityPerUnit;
        if (totalQty > 0) {
            pending.add(new Order(symbol, closeSide, Order.Type.MARKET, totalQty, bar.close()).closeOnly());
        }
        inTrade = false;
        unitsAdded = 0;
        partialExitDone = false;
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

    /** Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias() (XAU → neutre). */
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
        partialExitDone = false;
    }
}
