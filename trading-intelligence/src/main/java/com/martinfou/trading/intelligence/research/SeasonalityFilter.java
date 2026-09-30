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
    //
    // ⚠️ AUDIT DU 30 SEPTEMBRE 2026 (46e résultat, `RunSeasonalityFilterAudit`) — les
    // « hitRate » ci-dessous sont des revendications FULL-SAMPLE qui NE SE REPRODUISENT PAS :
    // mesuré sur barres réelles 2006-2026 (numéros = année du relevé), chaque pattern rend
    // 3 à 15 points de moins que revendiqué, et les 8 se DÉGRADENT en OOS (2016-2026) :
    //   USDCAD 10/12→11/26  94 % revendiqué → 84.2 % mesuré (IS 90 % / OOS 77.8 %) ; et
    //                       le contrôle ±1 mois donne des nets SUPÉRIEURS ($2 903 vs $2 242)
    //                       ⇒ ce n'est pas une fenêtre, c'est une SAISON (automne USD_CAD).
    //   USD_JPY 09/27→11/11 88 % revendiqué → 73.7 % mesuré, IS −0.26 % / OOS +1.75 %
    //                       = late-bloomer, DÉJÀ REJETÉ par le backtest du 24 août 2026.
    //   GBP_USD 03/11→04/25 83 % → 71.4 % (IS 90 % → OOS 54.5 %)
    //   EUR_USD 03/16→04/30 72 % → 61.9 % (IS 70 % → OOS 54.5 %)
    //   AUD_USD 06/04→07/19 75 % → 63.2 % (t 0.70 = non significatif)
    //   USDCAD 04/01→04/30  72 % → 71.4 % (IS 90 % → OOS 54.5 %)
    //   GBP_USD 04/01→04/30 89 % → 85.7 % (le plus solide ; IS 100 % → OOS 72.7 %)
    //   EUR_USD 04/01→04/30 72 % → 57.1 % (IS 60 % → OOS 54.5 %)
    // Les 8 se réduisent à DEUX phénomènes réels (faiblesse USD d'avril, force USD_CAD
    // d'automne) plus un pattern falsifié. Rapport : reports/2026-09-30-seasonality-audit.txt
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
     * ⚠️ 30 septembre 2026 (audit RunSeasonalityFilterAudit) — deux corrections :
     * 1. NORMALISATION DU SYMBOLE : les clés de PATTERNS sont écrites sans underscore
     *    (« USDCAD ») alors que les runners passent « USD_CAD ». Sans normalisation,
     *    2 des 8 patterns (USDCAD 10/12→11/26 revendiqué 94 %, USDCAD avril SELL)
     *    étaient INATTEIGNABLES — bug silencieux de la même classe que la clé de swap.
     * 2. FUSEAU : le comptage des jours se faisait en America/New_York, ce qui décale
     *    l'ouverture de fenêtre de 4-5 h (04:00Z au lieu de 00:00Z) — pitfall documenté
     *    « les stratégies calendaires doivent compter en UTC ». Passage à ZoneOffset.UTC.
     *
     * @param symbol e.g. "USDCAD", "USD_CAD", "EUR_USD"
     * @param now    timestamp courant
     * @return Order.Side.BUY, SELL, ou null si neutre
     */
    public static Order.Side getBias(String symbol, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String sym = normalize(symbol);

        for (SeasonalBias p : PATTERNS) {
            if (normalize(p.symbol()).equals(sym) && p.matches(month, day)) {
                return p.bias();
            }
        }
        return null; // neutral
    }

    /** Normalise un symbole : « USD_CAD » et « USDCAD » désignent la même paire. */
    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.replace("_", "").toUpperCase();
    }

    /**
     * Retourne le pattern saisonnier complet pour une paire (s'il existe).
     */
    public static SeasonalBias getPattern(String symbol, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String sym = normalize(symbol);

        for (SeasonalBias p : PATTERNS) {
            if (normalize(p.symbol()).equals(sym) && p.matches(month, day)) {
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
