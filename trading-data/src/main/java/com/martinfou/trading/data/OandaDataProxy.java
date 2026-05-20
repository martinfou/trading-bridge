package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import java.util.List;

/**
 * Data proxy that fetches candles from the OANDA REST API.
 * Used by the live runner — same {@link DataProxy} interface as backtesting,
 * but reads from OANDA instead of local .bars files.
 *
 * <p>Credentials are read from environment variables:
 * {@code OANDA_API_KEY} and {@code OANDA_ACCOUNT_ID}.</p>
 */
public class OandaDataProxy implements DataProxy {

    private final OandaPriceClient client;
    private final String accountId;

    public OandaDataProxy(String apiKey, String accountId, boolean isPractice) {
        this.client = new OandaPriceClient(apiKey, accountId, isPractice);
        this.accountId = accountId;
    }

    /**
     * Creates proxy reading credentials from environment.
     *
     * @throws IllegalArgumentException if env vars are missing
     */
    public static OandaDataProxy fromEnv(boolean isPractice) {
        String apiKey = System.getenv("OANDA_API_KEY");
        String accountId = System.getenv("OANDA_ACCOUNT_ID");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                "OANDA_API_KEY not set. Export it or source trading-dashboard/.env");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("OANDA_ACCOUNT_ID not set.");
        }
        return new OandaDataProxy(apiKey, accountId, isPractice);
    }

    @Override
    public List<Bar> getCandles(String instrument, String granularity, int count) throws Exception {
        // Normalize: "GBP/JPY" → "GBP_JPY", "EURUSD" → "EUR_USD"
        String norm = instrument.replace('/', '_');
        if (!norm.contains("_")) {
            // "GBPJPY" → "GBP_JPY"
            for (int i = 3; i < norm.length(); i++) {
                if (Character.isUpperCase(norm.charAt(i))) {
                    norm = norm.substring(0, i) + "_" + norm.substring(i);
                    break;
                }
            }
        }
        return client.getCandles(norm, granularity, count);
    }

    @Override
    public String tag() {
        return "oanda → account " + accountId;
    }
}
