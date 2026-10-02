package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Order;
import java.time.*;
import java.util.*;

/**
 * SwapCalculator — Calcule les frais de swap (rollover) overnight pour les positions forex.
 *
 * Swap rates are per-instrument and sourced from OANDA's own financing rates
 * (see {@link RealCostModel#FINANCING_ANNUAL}), converted to pips/day.
 *
 * Wednesday = triple swap (3× normal rate).
 * Rollover time: 5:00 PM ET (17:00 NY time).
 */
public class SwapCalculator {

    // Swap rates in pips per standard lot (100k units) per day
    // Positive = credit (you earn), Negative = debit (you pay)
    // For 1k units: divide by 100
    private static final Map<String, double[]> SWAP_RATES = new LinkedHashMap<>();

    static {
        // Format: { longSwap, shortSwap } in pips per day. Positive = credit (you earn),
        // Negative = debit (you pay). calculateSwap() scales by the position's pip value
        // (pipSize × quantity, JPY-converted), so the pip count here is size-independent.
        // Source: OANDA /v3/instruments financing.longRate / shortRate (annual fraction),
        // converted with RealCostModel.swapPipsPerDay(rate, mid, symbol) at the 2026-10-01 mids.
        //   swap[pips/lot/day] = rate × mid / (pipSize × 365)
        // The previous table (2024-2026 "approximate interbank") had three wrong signs and
        // was ~32× too small on gold — see FEE-AUDIT.md §4. Keys are underscore-normalized;
        // ratesFor() tolerates underscore-less lookups.
        //
        // LIMITATION (documented, not fixed): these rates are a 2026-10-01 SNAPSHOT applied as
        // constants across the whole backtest window. There are no historical financing series,
        // so carry history is not real — e.g. AUD_USD longs earned in 2010-2014 but the 2026 rate
        // charges them. Bound the error with the swap-sensitivity sweep (×0/×1/×2) in
        // RunCostModelReal; do not read a swap-dominant verdict as validated.
        SWAP_RATES.put("EUR_USD",  new double[]{-0.76,  0.14});   // EUR < USD
        SWAP_RATES.put("GBP_USD",  new double[]{-0.45, -0.29});   // both negative (GBP, USD > 0)
        SWAP_RATES.put("USD_JPY",  new double[]{ 0.78, -1.65});   // USD >> JPY
        SWAP_RATES.put("AUD_USD",  new double[]{-0.08, -0.32});   // AUD slightly below USD
        SWAP_RATES.put("NZD_USD",  new double[]{-0.35,  0.02});   // NZD < USD
        SWAP_RATES.put("USD_CAD",  new double[]{ 0.26, -1.11});   // USD > CAD
        SWAP_RATES.put("USD_CHF",  new double[]{ 0.72, -1.20});   // USD > CHF
        SWAP_RATES.put("GBP_JPY",  new double[]{ 0.89, -2.07});   // GBP >> JPY (carry)
        SWAP_RATES.put("EUR_GBP",  new double[]{-0.53,  0.04});   // EUR < GBP
        SWAP_RATES.put("AUD_JPY",  new double[]{ 0.73, -1.35});   // AUD >> JPY (carry)
        SWAP_RATES.put("NZD_JPY",  new double[]{ 0.13, -0.65});   // NZD > JPY
        SWAP_RATES.put("EUR_JPY",  new double[]{ 0.16, -1.15});   // EUR > JPY
        SWAP_RATES.put("XAU_USD",  new double[]{-25.9,  14.7});   // gold: financing ≈ −5.7 %/yr long, at 2010-2025 avg mid 1664 (NOT today's 4182)
    }

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final int ROLLOVER_HOUR = 17; // 5:00 PM ET

    // --- Research override (probes only) -------------------------------------
    // Permet de re-tester une fenêtre sous plusieurs hypothèses de swap
    // (signe inversé, taux plat, taux nul) sans toucher la table de référence.
    private static volatile String overrideSymbol = null;
    private static volatile double[] overrideRates = null;
    // Table par ANNÉE : year -> {longSwap, shortSwap} en pips/lot/jour. Utilisée pour
    // modéliser des swaps VARIABLES dans le temps (différentiel de taux réel) au lieu
    // des constantes 2024-2026 appliquées à 2006-2026 (artefact documenté).
    private static volatile java.util.Map<Integer, double[]> yearlyOverride = null;

    /** Remplace les taux d'UNE paire pour la durée du process (probes de recherche). */
    public static void setRateOverride(String symbol, double longSwap, double shortSwap) {
        overrideSymbol = symbol;
        overrideRates = new double[]{longSwap, shortSwap};
        yearlyOverride = null;
    }

    /**
     * Remplace les taux d'une paire PAR ANNÉE (probes de recherche).
     * La clé {@code -1} sert de repli pour les années absentes de la table.
     */
    public static void setYearlyRateOverride(String symbol, java.util.Map<Integer, double[]> byYear) {
        overrideSymbol = symbol;
        overrideRates = null;
        yearlyOverride = byYear;
    }

    public static void clearRateOverride() {
        overrideSymbol = null;
        overrideRates = null;
        yearlyOverride = null;
    }

    /** Normalise une paire : "USD_CAD" et "USDCAD" désignent la même clé. */
    private static boolean samePair(String a, String b) {
        return a != null && b != null && a.replace("_", "").equalsIgnoreCase(b.replace("_", ""));
    }

    /**
     * Lookup tolérant aux underscores. Historiquement la table contient la clé
     * "USDCAD" (sans underscore) alors que les runners passent "USD_CAD" ⇒ swap
     * silencieusement à 0 (net = PnL prix pur, artefact involontaire).
     */
    private static double[] ratesFor(String symbol) {
        if (symbol == null) return null;
        if (overrideSymbol != null && overrideRates != null && samePair(overrideSymbol, symbol)) {
            return overrideRates;
        }
        double[] rates = SWAP_RATES.get(symbol);
        if (rates == null) {
            // Tolerate underscore-less keys (e.g. "USDCAD") by matching against the
            // underscore-normalized table via the existing samePair() helper.
            for (Map.Entry<String, double[]> e : SWAP_RATES.entrySet()) {
                if (samePair(e.getKey(), symbol)) {
                    return e.getValue();
                }
            }
        }
        return rates;
    }

    /** True si un taux de swap existe réellement pour cette paire. */
    public static boolean hasRates(String symbol) {
        return ratesFor(symbol) != null;
    }

    /**
     * Calcule le swap pour une position overnight.
     *
     * @param symbol    paire forex (e.g. "EUR_USD")
     * @param side      BUY ou SELL
     * @param quantity  taille de la position en units
     * @param openTime  heure d'ouverture du trade
     * @param closeTime heure de fermeture du trade
     * @return swap total en USD (négatif = coût, positif = crédit)
     */
    public static double calculateSwap(
            String symbol, Order.Side side, double quantity,
            Instant openTime, Instant closeTime) {
        return calculateSwap(symbol, side, quantity, openTime, closeTime,
            com.martinfou.trading.core.ForexPnL.DEFAULT_USD_JPY);
    }

    /**
     * Calcule le swap pour une position overnight.
     *
     * @param usdJpyRate taux USD/JPY utilisé pour convertir la valeur du pip
     *                   des paires cotées en JPY en USD (défaut 150)
     */
    public static double calculateSwap(
            String symbol, Order.Side side, double quantity,
            Instant openTime, Instant closeTime, double usdJpyRate) {

        double[] rates = ratesFor(symbol);
        boolean useYearly = yearlyOverride != null && samePair(overrideSymbol, symbol);
        if (rates == null && !useYearly) return 0.0;

        // Convert from standard lot pip rate to actual position.
        // The pip value is denominated in the instrument's QUOTE currency; convert it to USD.
        //   - JPY-quoted pairs (USD_JPY, GBP_JPY, EUR_JPY, …): pip value in JPY → ÷ USD_JPY.
        //   - Other non-USD quotes (USD_CAD→CAD, USD_CHF→CHF, EUR_GBP→GBP): the raw quote-currency
        //     amount used to be treated as USD (overstated ~1.42× on CAD, ~1.20× on CHF, ~1.32× on GBP);
        //     now converted via RealCostModel.usdPerQuoteUnit (broker mids).
        double pipSize = RealCostModel.pipSize(symbol);
        double pipValueInUSD;
        if (symbol != null && symbol.toUpperCase().contains("JPY")) {
            double rate = usdJpyRate > 0 ? usdJpyRate : com.martinfou.trading.core.ForexPnL.DEFAULT_USD_JPY;
            pipValueInUSD = quantity * pipSize / rate;
        } else {
            pipValueInUSD = quantity * pipSize * RealCostModel.usdPerQuoteUnit(symbol);
        }

        // Swaps VARIABLES dans le temps : un taux par année, accumulé jour par jour
        // (le taux appliqué est celui de l'année du rollover, pas de la date d'entrée).
        if (useYearly) {
            return yearlySwap(side, pipValueInUSD, openTime, closeTime, yearlyOverride);
        }

        double pipRate = side == Order.Side.BUY ? rates[0] : rates[1];
        if (pipRate == 0) return 0.0;
        double dailySwapUSD = pipRate * pipValueInUSD;

        // Count rollover days between open and close
        int rolloverDays = countRollovers(openTime, closeTime);

        return dailySwapUSD * rolloverDays;
    }

    /**
     * Accumule le swap jour de rollover par jour de rollover avec un taux par ANNÉE.
     * Poids 3 le mercredi (triple swap), comme {@link #countRollovers}.
     */
    private static double yearlySwap(Order.Side side, double pipValueInUSD,
            Instant openTime, Instant closeTime, java.util.Map<Integer, double[]> byYear) {

        if (openTime == null || closeTime == null || closeTime.isBefore(openTime)) return 0.0;

        ZonedDateTime fromNY = openTime.atZone(NY);
        ZonedDateTime toNY = closeTime.atZone(NY);

        ZonedDateTime current = fromNY.withHour(ROLLOVER_HOUR).withMinute(0).withSecond(0);
        if (current.isBefore(fromNY)) current = current.plusDays(1);

        double total = 0.0;
        while (current.isBefore(toNY)) {
            int weight = current.getDayOfWeek() == DayOfWeek.WEDNESDAY ? 3 : 1;
            double[] r = byYear.get(current.getYear());
            if (r == null) r = byYear.get(-1);           // repli : dernière année connue
            if (r != null) {
                double pipRate = side == Order.Side.BUY ? r[0] : r[1];
                total += pipRate * pipValueInUSD * weight;
            }
            current = current.plusDays(1);
        }
        return total;
    }

    /**
     * Compte le nombre de rollovers (passages à 17:00 ET) entre deux dates.
     * Mercredi = 3× swap (donc 1 rollover le mercredi = 3 jours comptés)
     */
    static int countRollovers(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) return 0;

        ZonedDateTime fromNY = from.atZone(NY);
        ZonedDateTime toNY = to.atZone(NY);

        int totalDays = 0;

        // Start from the day after open
        ZonedDateTime current = fromNY.withHour(ROLLOVER_HOUR).withMinute(0).withSecond(0);
        if (current.isBefore(fromNY)) current = current.plusDays(1);

        while (current.isBefore(toNY)) {
            DayOfWeek dow = current.getDayOfWeek();
            // Wednesday = triple swap
            if (dow == DayOfWeek.WEDNESDAY) {
                totalDays += 3;
            } else {
                totalDays += 1;
            }
            current = current.plusDays(1);
        }

        return totalDays;
    }

    /**
     * Retourne les taux de swap pour une paire donnée.
     * Utile pour les stratégies carry trade.
     */
    public static double getLongSwap(String symbol) {
        double[] rates = ratesFor(symbol);
        return rates != null ? rates[0] : 0;
    }

    public static double getShortSwap(String symbol) {
        double[] rates = ratesFor(symbol);
        return rates != null ? rates[1] : 0;
    }

    /**
     * Retourne le taux annualisé approximatif pour une position long.
     * Positif = carry positive (vous êtes payé pour tenir la position).
     */
    public static double getAnnualCarry(String symbol, Order.Side side) {
        double[] rates = ratesFor(symbol);
        if (rates == null) return 0;
        double pipRate = side == Order.Side.BUY ? rates[0] : rates[1];
        // Approx: 365 days × pip rate (in pips) / current price
        return pipRate * 365 / 100; // very rough percentage
    }

    public static Set<String> getSupportedPairs() {
        return SWAP_RATES.keySet();
    }
}
