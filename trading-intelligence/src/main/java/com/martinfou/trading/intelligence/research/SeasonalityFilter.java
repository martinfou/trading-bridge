package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Order;
import java.time.*;

/**
 * SeasonalityFilter — Biais saisonnier pour les stratégies de trading.
 *
 * Basé sur les patterns détectés par SeasonalityAnalyzer et SlidingWindowAnalyzer.
 * Permet à n'importe quelle stratégie de filtrer ses signaux selon la saison.
 *
 * Usage:
 *   Order.Side bias = SeasonalityFilter.getBias(bar.timestamp());
 *   if (bias == Order.Side.BUY && mySignal == SELL) skip;
 */
public class SeasonalityFilter {

    /** Un pattern saisonnier enregistré. */
    public record SeasonalBias(
        String symbol,
        int startMonth, int startDay,
        int endMonth, int endDay,
        Order.Side bias,
        double hitRate,
        String thesis
    ) {
        public boolean matches(int month, int day) {
            // Handle year-crossing windows (e.g., Dec 20 → Jan 10)
            if (startMonth > endMonth || (startMonth == endMonth && startDay > endDay)) {
                // Year-crossing window
                return (month > startMonth || (month == startMonth && day >= startDay))
                    || (month < endMonth || (month == endMonth && day <= endDay));
            }
            // Normal window
            return (month > startMonth || (month == startMonth && day >= startDay))
                && (month < endMonth || (month == endMonth && day <= endDay));
        }
    }

    // All detected patterns from research
    private static final SeasonalBias[] PATTERNS = {
        // USDCAD — Autumn strength (94% hit rate!)
        new SeasonalBias("USDCAD", 10, 12, 11, 26, Order.Side.BUY, 0.94,
            "End of driving season → oil ↓ → CAD ↓ → USDCAD ↑"),

        // USDJPY — Autumn yen weakness (88% hit rate)
        new SeasonalBias("USD_JPY", 9, 27, 11, 11, Order.Side.BUY, 0.88,
            "Fiscal half-end → JPY weakness → USDJPY ↑"),

        // GBPUSD — Spring strength (83% hit rate)
        new SeasonalBias("GBP_USD", 3, 11, 4, 25, Order.Side.BUY, 0.83,
            "UK spring economic upswing + new tax year"),

        // EURUSD — Spring dividend repatriation (72% hit rate)
        new SeasonalBias("EUR_USD", 3, 16, 4, 30, Order.Side.BUY, 0.72,
            "European dividend season → EUR repatriation"),

        // AUDUSD — June-July (75% hit rate)
        new SeasonalBias("AUD_USD", 6, 4, 7, 19, Order.Side.BUY, 0.75,
            "End of Australian fiscal year"),

        // USDCAD — April weakness (72% hit rate bearish)
        new SeasonalBias("USDCAD", 4, 1, 4, 30, Order.Side.SELL, 0.72,
            "April effect: USDCAD bearish, mirror of EUR/GBP strength"),

        // GBPUSD — April strength (88.9% hit rate monthly)
        new SeasonalBias("GBP_USD", 4, 1, 4, 30, Order.Side.BUY, 0.89,
            "April seasonal strength for GBP"),

        // EURUSD — April strength (72.2% hit rate monthly)
        new SeasonalBias("EUR_USD", 4, 1, 4, 30, Order.Side.BUY, 0.72,
            "April seasonal strength for EUR"),
    };

    /**
     * Retourne le biais saisonnier pour une paire à une date donnée.
     *
     * @param symbol e.g. "USDCAD", "EUR_USD"
     * @param now    timestamp courant
     * @return Order.Side.BUY, SELL, ou null si neutre
     */
    public static Order.Side getBias(String symbol, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        for (SeasonalBias p : PATTERNS) {
            if (p.symbol().equals(symbol) && p.matches(month, day)) {
                return p.bias();
            }
        }
        return null; // neutral
    }

    /**
     * Retourne le pattern saisonnier complet pour une paire (s'il existe).
     */
    public static SeasonalBias getPattern(String symbol, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        for (SeasonalBias p : PATTERNS) {
            if (p.symbol().equals(symbol) && p.matches(month, day)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Retourne tous les patterns enregistrés.
     */
    public static SeasonalBias[] allPatterns() {
        return PATTERNS.clone();
    }
}
