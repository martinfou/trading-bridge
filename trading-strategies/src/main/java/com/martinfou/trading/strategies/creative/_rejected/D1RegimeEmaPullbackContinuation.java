package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.strategies.prop.AbstractPropStrategy;
import com.martinfou.trading.strategies.prop.PropSessions;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * D1RegimeEmaPullbackContinuation — variation de EmaPullbackContinuation (prop)
 * + filtre de régime D1 (rendement 20j ±5%).
 *
 * 📊 Insight (post-fix reality + leçon DailyRegimeMomentum 2026-08-10) :
 *    EmaPullbackContinuation est la SEULE stratégie post-fix avec PF ~1.05.
 *    Le régime D1 (mom20 ±5%) est un signal directionnel valide (comme le
 *    SeasonalityFilter) mais son exécution H1 directe meurt sous les coûts.
 *    Cette variation réutilise le meilleur des deux : la logique d'entrée
 *    éprouvée de la baseline + le régime D1 comme FILTRE de contre-tendance.
 *
 * 🔧 Mechanism:
 *    - Mêmes règles d'entrée que la baseline (EMA20/50/200, RSI 40-60,
 *      pullback, RR 2.5, session filter 21-1h, max 3 trades/jour, halt 2 losses)
 *    - Synthetic D1 closes (UTC) — look-ahead safe (jours clôturés uniquement)
 *    - regime = BULL si mom20 ≥ +5%, BEAR si mom20 ≤ -5%, SIDEWAYS sinon
 *    - BULL  → shorts bloqués (longs et sideways autorisés)
 *    - BEAR  → longs bloqués  (shorts et sideways autorisés)
 *    - SIDEWAYS → pas de restriction (la baseline décide avec EMA50/200)
 *
 * 🎯 Hypothèse : les entrées contre le momentum D1 fort (fin de trend,
 *    contre-pente 20j) sont les losers de la baseline. Les filtrer doit
 *    améliorer le PF sans tuer le nombre de trades (SIDEWAYS ~70% du temps
 *    reste autorisé).
 */
public class D1RegimeEmaPullbackContinuation extends AbstractPropStrategy {

    private static final int MOM_WINDOW = 20;          // D1 return window (trading days)
    private static final double MOM_THRESHOLD = 0.05;  // ±5% regime boundary

    // Daily aggregation (UTC)
    private final List<Double> dailyCloses = new ArrayList<>();
    private int currentDay = -1;          // yyyy*1000 + dayOfYear of current UTC day
    private double currentDayClose = 0;

    // --- Constructors ---
    public D1RegimeEmaPullbackContinuation(String symbol) {
        this("D1RegimeEmaPullbackContinuation", symbol, MOM_WINDOW, MOM_THRESHOLD);
    }

    public D1RegimeEmaPullbackContinuation(String name, String symbol) {
        this(name, symbol, MOM_WINDOW, MOM_THRESHOLD);
    }

    /** Parametric constructor (sweeps only). */
    public D1RegimeEmaPullbackContinuation(String name, String symbol, int momWindow, double momThreshold) {
        super(name, symbol);
        this.momWindow = momWindow;
        this.momThreshold = momThreshold;
    }

    private final int momWindow;
    private final double momThreshold;

    /**
     * Aggregation D1 à CHAQUE barre, AVANT la logique de la baseline.
     * ⚠️ Critique : AbstractPropStrategy.onBar() n'appelle evaluate() que si
     * canEnter() (pas de position, limites du jour non atteintes). Si
     * l'agrégation D1 vivait dans evaluate(), les closes des jours où une
     * position est ouverte seraient corrompues (jusqu'à ~2700 jours sur
     * 20 ans) → régime D1 = bruit. Découvert le 11 août 2026.
     */
    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        aggregateDaily(bar);
        super.onBar(bar);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 210) return;
        if (PropSessions.inHourRange(bar, 21, 1)) return;

        double ema20 = Indicators.emaLatest(history, 20);
        double ema50 = Indicators.emaLatest(history, 50);
        double ema200 = Indicators.emaLatest(history, 200);
        double rsi = Indicators.rsi(history, 14);
        double atr = atr(14);

        Order.Side regime = regimeSignal();
        boolean longAllowed = regime != Order.Side.SELL;  // BEAR blocks longs
        boolean shortAllowed = regime != Order.Side.BUY;  // BULL blocks shorts

        if (longAllowed && ema50 > ema200 && bar.low() <= ema20 && bar.close() > ema20
            && bar.close() > bar.open() && rsi >= 40 && rsi <= 60) {
            double entry = bar.close();
            double sl = Math.min(bar.low(), ema50) - atr * 0.3;
            enterLong(bar, sl, rrTp(entry, sl, Indicators.TradeSide.LONG));
        } else if (shortAllowed && ema50 < ema200 && bar.high() >= ema20 && bar.close() < ema20
            && bar.close() < bar.open() && rsi >= 40 && rsi <= 60) {
            double entry = bar.close();
            double sl = Math.max(bar.high(), ema50) + atr * 0.3;
            enterShort(bar, sl, rrTp(entry, sl, Indicators.TradeSide.SHORT));
        }
    }

    /** Finalize previous UTC day when day changes (look-ahead safe: closed days only). */
    private void aggregateDaily(Bar bar) {
        ZonedDateTime utc = bar.timestamp().atZone(ZoneId.of("UTC"));
        int dayKey = utc.getYear() * 1000 + utc.getDayOfYear();
        if (currentDay == -1) {
            currentDay = dayKey;
            currentDayClose = bar.close();
        } else if (dayKey != currentDay) {
            dailyCloses.add(currentDayClose);   // previous day CLOSED → usable
            currentDay = dayKey;
            currentDayClose = bar.close();
        } else {
            currentDayClose = bar.close();
        }
    }

    /** D1 regime from CLOSED days only: mom20 = lastClosedClose / close 20 days ago - 1. */
    private Order.Side regimeSignal() {
        if (dailyCloses.size() < momWindow + 2) return null;
        double now = dailyCloses.get(dailyCloses.size() - 1);
        double past = dailyCloses.get(dailyCloses.size() - 1 - momWindow);
        if (past <= 0) return null;
        double mom = now / past - 1.0;
        if (mom >= momThreshold) return Order.Side.BUY;
        if (mom <= -momThreshold) return Order.Side.SELL;
        return null; // SIDEWAYS
    }

    @Override
    public void reset() {
        super.reset();
        dailyCloses.clear();
        currentDay = -1;
        currentDayClose = 0;
    }
}
