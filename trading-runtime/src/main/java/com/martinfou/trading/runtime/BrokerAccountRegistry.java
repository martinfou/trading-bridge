package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerCredentials;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves broker credentials by account id from local config + environment (Story 16.9).
 *
 * <p>Load precedence: {@code TRADING_BRIDGE_BROKER_ACCOUNTS} env →
 * {@code data/runtime/broker-accounts.json} → synthetic {@link #DEFAULT_ID} account.
 */
public final class BrokerAccountRegistry {

    public static final String DEFAULT_ID = "default";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountEntry(
        String id,
        String provider,
        String tokenEnv,
        String accountIdEnv,
        String restUrlEnv,
        String defaultRestUrl,
        String hostEnv,
        String portEnv,
        String clientIdEnv,
        Integer defaultPortPaper,
        Integer defaultPortLive,
        String token,
        String accountId,
        String host,
        Integer port
    ) {
        public AccountEntry {
            if (provider == null || provider.isBlank()) {
                provider = "OANDA";
            }
            if (tokenEnv == null || tokenEnv.isBlank()) {
                tokenEnv = BrokerCredentials.ENV_OANDA_TOKEN;
            }
            if (accountIdEnv == null || accountIdEnv.isBlank()) {
                accountIdEnv = BrokerCredentials.ENV_OANDA_ACCOUNT;
            }
            if (restUrlEnv == null || restUrlEnv.isBlank()) {
                restUrlEnv = BrokerCredentials.ENV_OANDA_REST_URL;
            }
            if (defaultRestUrl == null || defaultRestUrl.isBlank()) {
                defaultRestUrl = "https://api-fxpractice.oanda.com";
            }
            if (hostEnv == null || hostEnv.isBlank()) {
                hostEnv = IbkrConnectionConfig.ENV_HOST;
            }
            if (portEnv == null || portEnv.isBlank()) {
                portEnv = IbkrConnectionConfig.ENV_PORT;
            }
            if (clientIdEnv == null || clientIdEnv.isBlank()) {
                clientIdEnv = IbkrConnectionConfig.ENV_CLIENT_ID;
            }
            if (defaultPortPaper == null) {
                defaultPortPaper = IbkrConnectionConfig.DEFAULT_PAPER_PORT;
            }
            if (defaultPortLive == null) {
                defaultPortLive = IbkrConnectionConfig.DEFAULT_LIVE_PORT;
            }
        }

        public AccountEntry(
            String id,
            String provider,
            String tokenEnv,
            String accountIdEnv,
            String restUrlEnv,
            String defaultRestUrl,
            String hostEnv,
            String portEnv,
            String clientIdEnv,
            Integer defaultPortPaper,
            Integer defaultPortLive
        ) {
            this(id, provider, tokenEnv, accountIdEnv, restUrlEnv, defaultRestUrl,
                 hostEnv, portEnv, clientIdEnv, defaultPortPaper, defaultPortLive,
                 null, null, null, null);
        }

        boolean isIbkr() {
            return "IBKR".equalsIgnoreCase(provider);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfigFile(List<AccountEntry> accounts) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, AccountEntry> accountsById;

    BrokerAccountRegistry(Map<String, AccountEntry> accountsById) {
        this.accountsById = new java.util.concurrent.ConcurrentHashMap<>(accountsById);
    }

    public void updateAccounts(List<AccountEntry> entries) {
        accountsById.clear();
        for (AccountEntry entry : entries) {
            if (entry.id() == null || entry.id().isBlank()) {
                throw new IllegalArgumentException("Broker account id is required");
            }
            accountsById.put(entry.id(), entry);
        }
    }

    public static BrokerAccountRegistry loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_BROKER_ACCOUNTS");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/broker-accounts.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return syntheticDefault();
    }

    public static BrokerAccountRegistry load(Path path) {
        try {
            ConfigFile config = MAPPER.readValue(path.toFile(), ConfigFile.class);
            Map<String, AccountEntry> map = new LinkedHashMap<>();
            if (config.accounts() != null) {
                for (AccountEntry entry : config.accounts()) {
                    if (entry.id() == null || entry.id().isBlank()) {
                        throw new IllegalStateException("Broker account id is required in " + path);
                    }
                    map.put(entry.id(), entry);
                }
            }
            if (map.isEmpty()) {
                return syntheticDefault();
            }
            return new BrokerAccountRegistry(map);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load broker accounts from " + path, e);
        }
    }

    static BrokerAccountRegistry ofEntries(AccountEntry... entries) {
        Map<String, AccountEntry> map = new LinkedHashMap<>();
        for (AccountEntry entry : entries) {
            map.put(entry.id(), entry);
        }
        return new BrokerAccountRegistry(map);
    }

    private static BrokerAccountRegistry syntheticDefault() {
        return new BrokerAccountRegistry(Map.of(
            DEFAULT_ID,
            new AccountEntry(
                DEFAULT_ID,
                "OANDA",
                BrokerCredentials.ENV_OANDA_TOKEN,
                BrokerCredentials.ENV_OANDA_ACCOUNT,
                BrokerCredentials.ENV_OANDA_REST_URL,
                "https://api-fxpractice.oanda.com",
                null,
                null,
                null,
                null,
                null)));
    }

    public AccountEntry getRawAccount(String id) {
        return accountsById.get(resolveId(id));
    }

    public List<BrokerAccount> listMasked() {
        return accountsById.values().stream()
            .map(this::toMaskedView)
            .toList();
    }

    public boolean contains(String accountId) {
        return resolveId(accountId) != null && accountsById.containsKey(resolveId(accountId));
    }

    public boolean credentialsConfigured(String accountId) {
        AccountEntry entry = accountsById.get(resolveId(accountId));
        if (entry == null) {
            return false;
        }
        if (entry.isIbkr()) {
            return ibkrConnection(accountId).isPresent();
        }
        return credentials(accountId).isPresent();
    }

    public Optional<IbkrConnectionConfig> ibkrConnection(String accountId) {
        String id = resolveId(accountId);
        AccountEntry entry = accountsById.get(id);
        if (entry == null || !entry.isIbkr()) {
            return Optional.empty();
        }
        String account = entry.accountId() != null && !entry.accountId().isBlank()
            ? entry.accountId()
            : System.getenv(entry.accountIdEnv());
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        String host = entry.host() != null && !entry.host().isBlank()
            ? entry.host()
            : System.getenv(entry.hostEnv());
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        int defaultPort = entry.port() != null ? entry.port() : entry.defaultPortPaper();
        int port = parsePort(System.getenv(entry.portEnv()), defaultPort);
        int clientId = parseClientId(System.getenv(entry.clientIdEnv()));
        return Optional.of(new IbkrConnectionConfig(host, port, clientId, account));
    }

    public Optional<IbkrConnectionConfig> ibkrConnection(String accountId, boolean paper) {
        return ibkrConnection(accountId).map(cfg -> {
            AccountEntry entry = accountsById.get(resolveId(accountId));
            int defaultPort = paper ? entry.defaultPortPaper() : entry.defaultPortLive();
            if (entry.port() != null) {
                defaultPort = entry.port();
            }
            int port = parsePort(System.getenv(entry.portEnv()), defaultPort);
            return new IbkrConnectionConfig(cfg.host(), port, cfg.clientId(), cfg.accountId());
        });
    }

    public Optional<BrokerCredentials> credentials(String accountId) {
        String id = resolveId(accountId);
        AccountEntry entry = accountsById.get(id);
        if (entry == null || entry.isIbkr()) {
            return Optional.empty();
        }
        String token = entry.token() != null && !entry.token().isBlank()
            ? entry.token()
            : firstNonBlank(System.getenv(entry.tokenEnv()), System.getenv(BrokerCredentials.ENV_OANDA_API_KEY));
        String account = entry.accountId() != null && !entry.accountId().isBlank()
            ? entry.accountId()
            : System.getenv(entry.accountIdEnv());
        if (token == null || account == null) {
            return Optional.empty();
        }
        String restUrl = System.getenv(entry.restUrlEnv());
        if (restUrl == null || restUrl.isBlank()) {
            restUrl = entry.defaultRestUrl();
        }
        return Optional.of(new BrokerCredentials(entry.provider(), account, token, restUrl));
    }

    public Optional<Broker> broker(String accountId) {
        return broker(accountId, ExecutionLabel.PAPER_OANDA);
    }

    public Optional<Broker> broker(String accountId, ExecutionLabel label) {
        String id = resolveId(accountId);
        AccountEntry entry = accountsById.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isIbkr()) {
            boolean paper = label == ExecutionLabel.PAPER_IBKR;
            return ibkrConnection(id, paper).map(BrokerProvider::ibkrBroker);
        }
        return credentials(id).map(BrokerProvider::oandaBroker);
    }

    public void requireKnown(String accountId) {
        if (!contains(accountId)) {
            throw new IllegalArgumentException("Unknown broker account: " + accountId);
        }
    }

    public void requireConfigured(String accountId) {
        requireKnown(accountId);
        if (!credentialsConfigured(accountId)) {
            throw new IllegalArgumentException(
                "Broker account " + resolveId(accountId) + " credentials not configured in environment");
        }
    }

    static String resolveId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return DEFAULT_ID;
        }
        return accountId.trim();
    }

    static void assertRoutingAllowed(
        String requestedAccountId,
        String deploymentAccountId
    ) {
        String requested = resolveId(requestedAccountId);
        String deployed = deploymentAccountId != null && !deploymentAccountId.isBlank()
            ? deploymentAccountId.trim()
            : null;
        if (deployed != null && !deployed.equals(requested)) {
            throw new IllegalArgumentException(
                "Cross-account routing blocked: deployment uses " + deployed + " but request specified " + requested);
        }
    }

    private BrokerAccount toMaskedView(AccountEntry entry) {
        if (entry.isIbkr()) {
            Optional<IbkrConnectionConfig> cfg = ibkrConnection(entry.id());
            String masked = cfg.map(c -> maskAccountId(c.accountId())).orElse("****");
            String host = entry.host() != null && !entry.host().isBlank() ? entry.host() : "127.0.0.1";
            Integer port = entry.port() != null ? entry.port() : entry.defaultPortPaper();
            return new BrokerAccount(entry.id(), entry.provider(), masked, cfg.isPresent(), host, port);
        }
        Optional<BrokerCredentials> creds = credentials(entry.id());
        String masked = creds.map(c -> maskAccountId(c.accountId())).orElse("****");
        return new BrokerAccount(entry.id(), entry.provider(), masked, creds.isPresent(), null, null);
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

    static String maskAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "****";
        }
        if (accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }

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
