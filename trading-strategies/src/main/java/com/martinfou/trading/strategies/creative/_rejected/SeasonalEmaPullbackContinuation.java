package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.strategies.prop.AbstractPropStrategy;
import com.martinfou.trading.strategies.prop.PropSessions;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * SeasonalEmaPullbackContinuation — Variation d'EmaPullbackContinuation
 * avec filtre directionnel saisonnier (Inline SeasonalityFilter).
 *
 * 📊 Concept: EmaPullbackContinuation (prop) est la SEULE stratégie post-fix
 *    look-ahead avec PF > 1.0 (1.05, marginal sur EUR/USD). Le SeasonalityFilter
 *    est le seul edge validé du système (patterns 72-94% hit rate sur 20+ ans).
 *    Cette variation fusionne les deux : on garde la mécanique de pullback EMA
 *    EXACTE, et on ajoute UNE SEULE règle — ne jamais entrer contre le biais
 *    saisonnier actif de la paire.
 *
 * 🔧 Mécanisme (identique à EmaPullbackContinuation, sauf filtre saisonnier):
 *    - EMA 20/50/200 + RSI(14) + ATR(14)
 *    - Tendance: ema50 > ema200 (long seulement) / ema50 < ema200 (short)
 *    - Entrée: pullback au EMA20 (low <= ema20 && close > ema20, RSI 40-60)
 *    - SL: min(low, ema50) - 0.3×ATR / TP: RR 2.5
 *    - Session: pas de trades 21h-1h UTC
 *    - Max 3 trades/jour, halt après 2 pertes consécutives
 *    - ✨ NOUVEAU: si biais saisonnier = SELL → skip longs; si BUY → skip shorts
 *
 * 🎯 Hypothèse: les entrées pullback alignées avec le biais saisonnier
 *    institutionnel (repatriation, fiscal year-end) devraient avoir un
 *    win rate supérieur, poussant le PF marginal de 1.05 vers > 1.2.
 *
 * ❌ REJECTED 2026-08-04 — Gate échoué (PF ≥ 1.2 sur 2/3 paires requis).
 *    Résultats avec coûts ($0.07 + 0.01%, 2006-2026, $50K) :
 *    EUR_USD 1.02 / GBP_USD 1.16 / USD_JPY 1.05 / USD_CAD 0.93 / AUD_USD 0.94.
 *    Le filtre saisonnier améliore TOUJOURS le PF (+0.00 à +0.05, jamais pire,
 *    -129 à -191 trades filtrés) mais insuffisant pour franchir le gate.
 *    Seule GBP_USD (1.16, +2.96%) s'approche. Moved to creative/_rejected/.
 */
public class SeasonalEmaPullbackContinuation extends AbstractPropStrategy {

    public SeasonalEmaPullbackContinuation(String symbol) {
        super("SeasonalEmaPullback", symbol);
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

        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        if (ema50 > ema200 && bar.low() <= ema20 && bar.close() > ema20
            && bar.close() > bar.open() && rsi >= 40 && rsi <= 60
            && bias != Order.Side.SELL) {
            double entry = bar.close();
            double sl = Math.min(bar.low(), ema50) - atr * 0.3;
            enterLong(bar, sl, rrTp(entry, sl, Indicators.TradeSide.LONG));
        } else if (ema50 < ema200 && bar.high() >= ema20 && bar.close() < ema20
            && bar.close() < bar.open() && rsi >= 40 && rsi <= 60
            && bias != Order.Side.BUY) {
            double entry = bar.close();
            double sl = Math.max(bar.high(), ema50) + atr * 0.3;
            enterShort(bar, sl, rrTp(entry, sl, Indicators.TradeSide.SHORT));
        }
    }

    /**
     * Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias()
     * (trading-intelligence), copié ici car trading-strategies ne peut pas
     * dépendre de trading-intelligence (cycle Maven). Même pattern que
     * EngulfingReversalStrategy.
     */
    protected Order.Side getSeasonalBias(String sym, java.time.Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        // Normalisation: le SeasonalityFilter source enregistre "USDCAD" sans underscore,
        // mais les runners passent "USD_CAD". Normaliser évite le bug silencieux où le
        // filtre ne matche jamais (résultats identiques à la baseline, découvert 2026-08-04).
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null;
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }
}
