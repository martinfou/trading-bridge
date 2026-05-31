package com.martinfou.trading.data.ibkr;

import java.util.Optional;

/** Local IB Gateway connection settings (Story 16.10). Credentials via environment only. */
public record IbkrConnectionConfig(
    String host,
    int port,
    int clientId,
    String accountId
) {

    public static final String ENV_HOST = "IBKR_GATEWAY_HOST";
    public static final String ENV_PORT = "IBKR_GATEWAY_PORT";
    public static final String ENV_CLIENT_ID = "IBKR_CLIENT_ID";
    public static final String ENV_ACCOUNT = "IBKR_ACCOUNT_ID";

    public static final int DEFAULT_PAPER_PORT = 7497;
    public static final int DEFAULT_LIVE_PORT = 7496;

    public IbkrConnectionConfig {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (clientId <= 0) {
            clientId = 1;
        }
    }

    public static Optional<IbkrConnectionConfig> fromEnvironment(boolean paper) {
        String account = System.getenv(ENV_ACCOUNT);
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        String host = System.getenv(ENV_HOST);
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        int port = parsePort(System.getenv(ENV_PORT), paper ? DEFAULT_PAPER_PORT : DEFAULT_LIVE_PORT);
        int clientId = parseClientId(System.getenv(ENV_CLIENT_ID));
        return Optional.of(new IbkrConnectionConfig(host, port, clientId, account));
    }

    private static int parsePort(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private static int parseClientId(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
