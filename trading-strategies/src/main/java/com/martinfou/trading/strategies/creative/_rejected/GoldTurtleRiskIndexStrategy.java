package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleRiskIndex — GoldTurtleTrend (Turtle Donchian 55/20, XAU/USD H1)
 * conditionné par le RÉGIME DE RISK-APPETITE (indice AUD/JPY synthétique).
 *
 * 📊 CONCEPT (jeudi 3 septembre 2026, intermarket/cross-asset) :
 *    GoldTurtleDxyFilter (28 août) a montré que l'edge or vit quand le DXY
 *    synthétique est AU-DESSUS de sa moyenne ~21 jours (OPPOSITE : PF 1.34
 *    vs 1.17 baseline ; ALIGNED « bear dollar » : PF 0.95 = détruit l'edge).
 *    Interprétation retenue : les trends or tradables sont des phases
 *    risk-off (USD-refuge + or-refuge ensemble). MAIS le DXY est un gauge
 *    ambigu : il mélange la direction du dollar ET le risk-appetite (ses
 *    composantes EUR/GBP/JPY/CAD/CHF réagissent différemment en stress).
 *
 *    Question intermarket du jour : le vrai régime derrière l'edge or est-il
 *    le RISK-OFF générique (mesuré par un indice de risk-appetite pur,
 *    décorrélé de la direction USD) plutôt que le « USD ferme » en soi ?
 *
 *    Gauge : RAI (Risk Appetite Index) = AUD/JPY synthétique = close(AUD_USD)
 *    × close(USD_JPY), aligné par timestamp H1 — la paire canonique
 *    « devise commodity vs devise refuge ». Haut = risk-on, bas = risk-off.
 *    Construit par le runner, passé au constructeur (comme le DXY le 28 août).
 *
 *    - MODE_ALIGNED  : BUY or si RAI > SMA_N (risk-on → or = actif risk ?),
 *                      SELL or si RAI < SMA_N. Hypothèse naïve « or risk-on ».
 *    - MODE_OPPOSITE : BUY or si RAI < SMA_N (risk-off → or = refuge),
 *                      SELL or si RAI > SMA_N. Hypothèse « or = refuge »
 *                      (l'analogue économique de DXY-OPPOSITE).
 *    - MODE_OFF      : baseline GoldTurtleTrend.
 *
 *    Pronostics (à falsifier) :
 *    * Si OPPOSITE (risk-off) ≈ ou > DXY-OPPOSITE → l'edge or est un edge de
 *      RISK-OFF ; AUD/JPY est un gauge plus propre que le DXY → outil pour
 *      les playbooks news.
 *    * Si OPPOSITE (risk-off) < DXY-OPPOSITE (surtout en bull 2006-12, où
 *      l'or montait PENDANT le risk-on commodity boom) → l'état opératif est
 *      plus spécifique que le risk-off générique (phases où l'or monte AVEC
 *      un dollar ferme, pas toutes les phases de stress).
 *    * ALIGNED (risk-on) doit détruire ou affaiblir (contrôle directionnel).
 *
 * 🔧 MÉCANIQUE (look-ahead safe) — identique à GoldTurtleDxyFilterStrategy :
 *    1. Entrées/sorties Turtle Donchian 55/20 sur les barres STRICTEMENT
 *       antérieures.
 *    2. Le RAI est passé en Map<timestamp, niveau> et consommé avec un
 *       pointeur qui avance tant que ts(rai) < ts(barre courante) — le régime
 *       est connu à l'instant de la décision. Le filtre gate les ENTRÉES
 *       uniquement (sorties inchangées).
 *    3. Quantité fixe 10 oz — pas d'artefact de sizing ATR.
 *
 * 🎯 Verdicts possibles :
 *    - OPPOSITE reproduit/améliore DXY-OPPOSITE → edge = risk-off (or refuge),
 *      le RAI devient le gauge de régime de référence pour la famille or.
 *    - OPPOSITE nettement plus faible que DXY-OPPOSITE → le facteur
 *      « USD ferme » (twin-refuge USD/or) est le vrai sélecteur, PAS le
 *      risk-off générique (certaines phases risk-off voient JPY/CHF refuges
 *      concurrents capter les flows, or haché — cf. XAU_JPY PF 0.97 le 27/08).
 *    - ALIGNED ≈ OPPOSITE ≈ baseline → filtre inerte.
 */
public class GoldTurtleRiskIndexStrategy implements Strategy {

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
    private final int raiSmaPeriod;   // in H1 bars

    private final List<Long> raiTs = new ArrayList<>();
    private final List<Double> raiVal = new ArrayList<>();
    private int raiPtr = -1;          // last RAI index with ts < current bar ts

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;

    /** MODE_ALIGNED, SMA 500 H1 (~21 jours). */
    public GoldTurtleRiskIndexStrategy(String name, String symbol, Map<Long, Double> rai) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL, MODE_ALIGNED, 500, rai);
    }

    public GoldTurtleRiskIndexStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                       int filterMode, int raiSmaPeriod, Map<Long, Double> rai) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.filterMode = filterMode;
        this.raiSmaPeriod = Math.max(1, raiSmaPeriod);
        if (rai != null) {
            for (Map.Entry<Long, Double> e : rai.entrySet()) {
                raiTs.add(e.getKey());
                raiVal.add(e.getValue());
            }
        }
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        advanceRai(bar.timestamp().toEpochMilli());
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

    /** Avance le pointeur RAI : toutes les entrées avec ts < barre courante. */
    private void advanceRai(long barTs) {
        while (raiPtr + 1 < raiVal.size() && raiTs.get(raiPtr + 1) < barTs) {
            raiPtr++;
        }
    }

    /**
     * Filtre de régime risk-appetite sur les entrées. Utilise le RAI de la
     * barre STRICTEMENT antérieure (raiPtr) — look-ahead safe par construction.
     * Retourne true si le trade est autorisé.
     */
    private boolean filterAllows(Order.Side side) {
        if (filterMode == MODE_OFF || raiPtr < 0) return true;
        int from = Math.max(0, raiPtr - raiSmaPeriod + 1);
        int count = raiPtr - from + 1;
        double sum = 0;
        for (int i = from; i <= raiPtr; i++) sum += raiVal.get(i);
        double sma = sum / count;
        double raiNow = raiVal.get(raiPtr);
        boolean riskOn = raiNow > sma;   // AUD/JPY au-dessus de sa moyenne = risk-on
        if (filterMode == MODE_OPPOSITE) riskOn = !riskOn;
        // ALIGNED : long or en risk-on (or = actif risk) ; short or en risk-off
        // OPPOSITE : long or en risk-off (or = refuge) ; short or en risk-on
        if (side == Order.Side.BUY) return riskOn;
        return !riskOn;
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
        raiPtr = -1;
    }
}
