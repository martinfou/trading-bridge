package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Order;
import java.time.*;
import java.util.*;

/**
 * SwapCalculator — Calcule les frais de swap (rollover) overnight pour les positions forex.
 *
 * Swap rates vary by broker and change over time. These are approximate rates
 * based on typical interbank rates for standard lot (100k units).
 * For mini lots (1k units), divide by 100.
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
        // Format: { longSwap, shortSwap } in pips per standard lot
        // Source: approximate interbank rates as of 2024-2026
        SWAP_RATES.put("EUR_USD",  new double[]{-3.5,  1.2});   // EUR lower rate than USD
        SWAP_RATES.put("GBP_USD",  new double[]{-1.8,  -0.5});  // Both relatively close
        SWAP_RATES.put("USD_JPY",  new double[]{ 5.2,  -8.5});  // USD >> JPY
        SWAP_RATES.put("AUD_USD",  new double[]{ 3.8,  -6.2});  // AUD > USD
        SWAP_RATES.put("NZD_USD",  new double[]{ 4.0,  -6.5});  // NZD > USD
        SWAP_RATES.put("USDCAD",   new double[]{-2.5,  1.0});   // CAD lower than USD (usually)
        SWAP_RATES.put("USD_CHF",  new double[]{ 2.0,  -4.5});  // USD > CHF
        SWAP_RATES.put("GBP_JPY",  new double[]{-4.5,  0.8});   // Complex cross
        SWAP_RATES.put("EUR_GBP",  new double[]{-1.2,  0.5});   // EUR < GBP
        SWAP_RATES.put("AUD_JPY",  new double[]{ 6.5, -10.0});  // AUD >> JPY (high carry!)
        SWAP_RATES.put("NZD_JPY",  new double[]{ 7.0, -11.0});  // NZD >> JPY (high carry!)
        SWAP_RATES.put("EUR_JPY",  new double[]{ 2.0,  -4.0});  // EUR > JPY
        SWAP_RATES.put("XAU_USD",  new double[]{-2.0,  0.5});   // Gold storage costs
    }

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final int ROLLOVER_HOUR = 17; // 5:00 PM ET

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

        double[] rates = SWAP_RATES.get(symbol);
        if (rates == null) return 0.0;

        double pipRate = side == Order.Side.BUY ? rates[0] : rates[1];
        if (pipRate == 0) return 0.0;

        // Convert from standard lot pip rate to actual position
        // For EUR/USD 1k units: 1 pip = $0.10. So -3.5 pips = -$0.35/day
        double pipSize = symbol.contains("JPY") ? 0.01 : 0.0001;
        // For JPY-quoted pairs the pip value is in JPY; convert to USD (e.g. ÷150).
        // Without this, GBP_JPY/EUR_JPY/USD_JPY swaps are overstated ~150x.
        double pipValueInUSD = symbol.contains("JPY")
            ? quantity * pipSize / (usdJpyRate > 0 ? usdJpyRate : com.martinfou.trading.core.ForexPnL.DEFAULT_USD_JPY)
            : quantity * pipSize;
        double dailySwapUSD = pipRate * pipValueInUSD;

        // Count rollover days between open and close
        int rolloverDays = countRollovers(openTime, closeTime);

        return dailySwapUSD * rolloverDays;
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
        double[] rates = SWAP_RATES.get(symbol);
        return rates != null ? rates[0] : 0;
    }

    public static double getShortSwap(String symbol) {
        double[] rates = SWAP_RATES.get(symbol);
        return rates != null ? rates[1] : 0;
    }

    /**
     * Retourne le taux annualisé approximatif pour une position long.
     * Positif = carry positive (vous êtes payé pour tenir la position).
     */
    public static double getAnnualCarry(String symbol, Order.Side side) {
        double[] rates = SWAP_RATES.get(symbol);
        if (rates == null) return 0;
        double pipRate = side == Order.Side.BUY ? rates[0] : rates[1];
        // Approx: 365 days × pip rate (in pips) / current price
        return pipRate * 365 / 100; // very rough percentage
    }

    public static Set<String> getSupportedPairs() {
        return SWAP_RATES.keySet();
    }
}
