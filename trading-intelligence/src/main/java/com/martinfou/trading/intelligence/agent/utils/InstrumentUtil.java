package com.martinfou.trading.intelligence.agent.utils;

/**
 * Utility class for instrument name normalization.
 */
public final class InstrumentUtil {

    private InstrumentUtil() {}

    /**
     * Normalizes asset names like "EUR_USD" or "EURUSD" to "EUR/USD".
     *
     * @param asset the asset string to normalize
     * @return the normalized instrument string
     */
    public static String normalizeInstrument(String asset) {
        if (asset == null) {
            return null;
        }
        String normalized = asset.replace('_', '/').toUpperCase();
        if (!normalized.contains("/") && normalized.length() == 6) {
            normalized = normalized.substring(0, 3) + "/" + normalized.substring(3);
        }
        return normalized;
    }
}
