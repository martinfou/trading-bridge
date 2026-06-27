package com.martinfou.trading.backtest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Service to serialize and hash backtest parameters and automatically persist
 * run metrics to the local SQLite database.
 */
public final class BacktestPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(BacktestPersistenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private BacktestPersistenceService() {}

    /**
     * Resolves the default database path using standard env var and directory precedence rules.
     */
    public static Path resolveDefaultDbPath() {
        String sysProp = System.getProperty("TRADING_BRIDGE_EVENT_STORE");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp).toAbsolutePath().normalize();
        }

        String explicitFile = System.getenv("TRADING_BRIDGE_EVENT_STORE");
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile).toAbsolutePath().normalize();
        }

        String dataDir = System.getenv("TRADING_BRIDGE_DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir).toAbsolutePath().normalize().resolve("events.db");
        }

        Path repoRoot = findRepoRoot();
        if (repoRoot != null) {
            return repoRoot.resolve("data/runtime/events.db").toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), ".trading-bridge", "events.db").toAbsolutePath().normalize();
    }

    private static Path findRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            try {
                String content = Files.readString(pom);
                if (content.contains("<artifactId>trading-bridge</artifactId>")
                    && content.contains("<packaging>pom</packaging>")) {
                    return dir;
                }
            } catch (Exception ignored) {
                // try parent
            }
        }
        return null;
    }

    /**
     * Extracts parameters from a strategy using reflection.
     */
    public static Map<String, Object> extractParameters(Strategy strategy) {
        Map<String, Object> params = new HashMap<>();
        if (strategy == null) {
            return params;
        }

        Strategy actualStrategy = strategy;
        if (strategy.getClass().getSimpleName().equals("FixedQuantityStrategy")) {
            try {
                java.lang.reflect.Field qtyField = strategy.getClass().getDeclaredField("quantity");
                qtyField.setAccessible(true);
                double quantity = ((Number) qtyField.get(strategy)).doubleValue();
                params.put("quantity", quantity);
                params.put("lotSize", quantity / 100_000.0);
                
                java.lang.reflect.Field delegateField = strategy.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                actualStrategy = (Strategy) delegateField.get(strategy);
            } catch (Exception ignored) {
            }
        }

        // If it is SqInterpretedStrategy, we extract configuration variables
        if (actualStrategy.getClass().getSimpleName().equals("SqInterpretedStrategy")) {
            try {
                java.lang.reflect.Field configField = actualStrategy.getClass().getDeclaredField("config");
                configField.setAccessible(true);
                Object config = configField.get(actualStrategy);
                if (config != null) {
                    java.lang.reflect.Method varsMethod = config.getClass().getMethod("variables");
                    Map<?, ?> vars = (Map<?, ?>) varsMethod.invoke(config);
                    if (vars != null) {
                        for (Map.Entry<?, ?> entry : vars.entrySet()) {
                            params.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fallback to reflection scan
            }
        }

        // Inspect strategy fields for parameters (primitives and wrapper types)
        Class<?> clazz = actualStrategy.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                if (name.equals("history") || name.equals("pending") || name.equals("pendingOrders")
                    || name.equals("document") || name.equals("config") || name.equals("log")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object val = field.get(actualStrategy);
                    if (val != null) {
                        if (field.getType().isPrimitive() || val instanceof Number || val instanceof String || val instanceof Boolean) {
                            params.put(name, val);
                        }
                    }
                } catch (Exception ignored) {
                    // skip fields that throw or are inaccessible
                }
            }
            clazz = clazz.getSuperclass();
        }
        return params;
    }

    /**
     * Serializes parameters and calculates a deterministic SHA-256 parameter hash.
     */
    public static String computeParameterHash(Map<String, Object> parameters) {
        if (parameters == null) {
            parameters = new HashMap<>();
        }
        try {
            Map<String, Object> sorted = new TreeMap<>(parameters);
            String json = MAPPER.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to calculate parameter hash", e);
        }
    }

    /**
     * Saves a backtest result using the metadata from its RunContext.
     */
    public static void save(String runId, RunContext context, BacktestResult result) {
        try {
            Path dbPath = resolveDefaultDbPath();
            Map<String, Object> params = extractParameters(context.strategy());
            String paramsJson = MAPPER.writeValueAsString(new TreeMap<>(params));
            String paramHash = computeParameterHash(params);
            String equityCurveJson = MAPPER.writeValueAsString(result.equityCurve());

            String strategyId = context.strategyId() != null ? context.strategyId() : context.strategy().name();
            BacktestRunDetails run = new BacktestRunDetails(
                runId,
                strategyId,
                context.symbol(),
                result.periodStart() != null ? result.periodStart() : Instant.now(),
                result.periodEnd() != null ? result.periodEnd() : Instant.now(),
                paramsJson,
                paramHash,
                result.initialCapital(),
                result.finalEquity(),
                result.totalPnl(),
                result.totalReturnPct(),
                result.totalTrades(),
                result.winningTrades(),
                result.losingTrades(),
                result.winRatePct(),
                result.maxDrawdownPct(),
                result.avgTradePnl(),
                result.sharpeRatio(),
                result.sortinoRatio(),
                result.profitFactor(),
                result.calmarRatio(),
                result.totalCommission(),
                result.totalSlippage(),
                equityCurveJson,
                Instant.now()
            );

            try (SqliteBacktestRunStore store = new SqliteBacktestRunStore(dbPath)) {
                store.insert(run);
                log.info("[BacktestPersistence] Saved run {} for strategy {} (hash: {})",
                    runId, strategyId, paramHash);
            }
            try (SqliteTradeStore tradeStore = new SqliteTradeStore(dbPath)) {
                tradeStore.deleteForRun(runId);
                tradeStore.insertAll(runId, result.trades());
                log.info("[BacktestPersistence] Saved {} trades for run {}", result.trades().size(), runId);
            }
        } catch (Exception e) {
            log.error("[BacktestPersistence] Failed to persist backtest run {}: {}", runId, e.getMessage(), e);
        }
    }
}
