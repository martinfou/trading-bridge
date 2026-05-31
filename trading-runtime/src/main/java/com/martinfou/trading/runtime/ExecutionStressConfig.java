package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.martinfou.trading.backtest.BacktestExecutionCost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Degraded execution stress profile for promote validation (Story 19.5 / PS-GR6). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionStressConfig(
    boolean enabled,
    double slippageMultiplier,
    double commissionMultiplier,
    double defaultSlippagePct,
    double defaultCommissionPerTrade,
    double maxDrawdownPct,
    double minReturnPct
) {

    public static final ExecutionStressConfig DEFAULT = new ExecutionStressConfig(
        false,
        3.0,
        2.0,
        0.0001,
        5.0,
        25.0,
        -40.0);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ExecutionStressConfig loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_EXECUTION_STRESS");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/execution-stress.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT;
    }

    public static ExecutionStressConfig load(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), ExecutionStressConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load execution stress config from " + path, e);
        }
    }

    /** Applies stress multipliers on top of run baseline costs (Story 13.9 model). */
    public BacktestExecutionCost stressCost(BacktestExecutionCost baseline) {
        BacktestExecutionCost base = baseline != null ? baseline : BacktestExecutionCost.ZERO;
        double baseSlippage = base.slippagePct() != 0.0 ? base.slippagePct() : defaultSlippagePct;
        double baseCommission = base.commissionPerTrade() != 0.0
            ? base.commissionPerTrade()
            : defaultCommissionPerTrade;
        return BacktestExecutionCost.ofCommissionAndSlippage(
            baseCommission * commissionMultiplier,
            baseSlippage * slippageMultiplier);
    }

    public Map<String, Object> stressCostMap(BacktestExecutionCost baseline) {
        return stressCost(baseline).toMap();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("slippageMultiplier", slippageMultiplier);
        map.put("commissionMultiplier", commissionMultiplier);
        map.put("defaultSlippagePct", defaultSlippagePct);
        map.put("defaultCommissionPerTrade", defaultCommissionPerTrade);
        map.put("maxDrawdownPct", maxDrawdownPct);
        map.put("minReturnPct", minReturnPct);
        return Map.copyOf(map);
    }
}
