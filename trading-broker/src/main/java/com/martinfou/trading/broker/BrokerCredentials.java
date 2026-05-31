package com.martinfou.trading.broker;

import java.util.Optional;

/**
 * Broker credentials loaded from environment or local config only (NFR2).
 * Never hardcode tokens — OANDA integration (Story 16.3) consumes this.
 */
public record BrokerCredentials(
    String provider,
    String accountId,
    String apiToken,
    String restUrl
) {

    public static final String ENV_OANDA_TOKEN = "OANDA_API_TOKEN";
    public static final String ENV_OANDA_ACCOUNT = "OANDA_ACCOUNT_ID";
    public static final String ENV_OANDA_REST_URL = "OANDA_REST_URL";

    public static Optional<BrokerCredentials> oandaFromEnvironment() {
        String token = firstNonBlank(
            System.getenv(ENV_OANDA_TOKEN),
            System.getenv(ENV_OANDA_API_KEY));
        String account = firstNonBlank(
            System.getenv(ENV_OANDA_ACCOUNT),
            System.getenv("OANDA_ACCOUNT_ID"));
        if (token == null || account == null) {
            return Optional.empty();
        }
        String restUrl = System.getenv(ENV_OANDA_REST_URL);
        if (restUrl == null || restUrl.isBlank()) {
            restUrl = "https://api-fxpractice.oanda.com";
        }
        return Optional.of(new BrokerCredentials("OANDA", account, token, restUrl));
    }

    public static final String ENV_OANDA_API_KEY = "OANDA_API_KEY";

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
