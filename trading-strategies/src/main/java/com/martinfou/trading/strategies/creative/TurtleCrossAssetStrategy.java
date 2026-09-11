package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.util.*;

/**
 * TurtleCrossAssetStrategy — Turtle Donchian 55/20 (mécanique EXACTE de
 * GoldTurtleTrendStrategy) rendue AGNOSTIQUE de l'univers, avec quantité
 * paramétrable ($/point).
 *
 * 📊 CONCEPT (deep dive vendredi 11 septembre 2026, 34e résultat) :
 *    La mécanique Turtle a été validée sur XAU/USD H1 (PF 1.17, 17 août) puis
 *    déclinée en dimensions instrument / fréquence / quote / régime. La seule
 *    piste jamais explorée est l'UNIVERS : `data/historical/futures/` contient
 *    MES_D1 (S&P 500), MNQ_D1 (Nasdaq 100) et M2K_D1 (Russell 2000) en D1
 *    2006-2026 — révélé le 10 sept (le skill affirmait à tort que le S&P 500
 *    était absent du repo). Le S&P 500 est le « vrai actif de risque » : la
 *    mécanique Turtle y a-t-elle un edge, ou l'edge est-il propre à l'or ?
 *
 *    Pourquoi une classe dédiée plutôt que GoldTurtleTrendStrategy :
 *    GoldTurtleTrendStrategy a QUANTITY = 10 hardcodé (10 oz) ; or un indice
 *    qui fait ×6 (S&P), ×17 (Nasdaq) ou ×2 (Russell) exige un paramètre de
 *    taille explicite pour que le levier de fin de période soit lisible et
 *    testable en sweep. La logique de signal est copiée à l'identique.
 *
 * ✅ VALIDATION D'IMPLÉMENTATION OBLIGATOIRE (dans le runner) :
 *    XAU_USD H1 2006-2025, quantité 10, 55/20 DOIT reproduire EXACTEMENT
 *    GoldTurtleTrendStrategy : PF 1.17 / WR 41.5% / DD 9.35% / 1594 trades /
 *    net +$17 705. Tant que ce n'est pas reproduit, aucune conclusion indice.
 *
 * 🔧 MÉCANIQUE (look-ahead safe, identique à GoldTurtleTrend) :
 *    1. Entry BUY  si close > high des entryChannel barres STRICTEMENT antérieures
 *       Entry SELL si close < low des entryChannel barres STRICTEMENT antérieures
 *    2. Exit BUY  si close < low des exitChannel barres précédentes
 *       Exit SELL si close > high des exitChannel barres précédentes
 *    3. Quantité FIXE ($/point) — pas d'ATR leverage artifact
 *       (pitfall AugustRiskFade : calcRiskPosition invalide en multi-semaines)
 *    4. SeasonalityFilter : neutre pour indices et XAU (aucun pattern enregistré)
 *       — la classe ne dépend pas de trading-intelligence (cycle Maven).
 */
public class TurtleCrossAssetStrategy implements Strategy {

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final double quantity;      // $ par point d'indice (ou oz pour l'or)

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;

    public TurtleCrossAssetStrategy(String name, String symbol) {
        this(name, symbol, 55, 20, 5.0);
    }

    public TurtleCrossAssetStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                    double quantity) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.quantity = quantity;
    }

    public double quantity() { return quantity; }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int n = history.size();
        if (n < exitChannel + 2) return;

        double prevHighEntry = maxHigh(n - 1, entryChannel);
        double prevLowEntry  = minLow(n - 1, entryChannel);
        double prevHighExit  = maxHigh(n - 1, exitChannel);
        double prevLowExit   = minLow(n - 1, exitChannel);

        if (inTrade) {
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLowExit) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHighExit) {
                closePosition(bar);
                return;
            }
            return;
        }

        if (bar.close() > prevHighEntry) {
            enter(bar, Order.Side.BUY);
        } else if (bar.close() < prevLowEntry) {
            enter(bar, Order.Side.SELL);
        }
    }

    private void enter(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, quantity, bar.close()));
        inTrade = true;
        tradeDirection = side;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, quantity, bar.close()).closeOnly());
        inTrade = false;
    }

    /** Max high over `period` barres strictement AVANT l'index `end` (exclusif). */
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
    }
}
