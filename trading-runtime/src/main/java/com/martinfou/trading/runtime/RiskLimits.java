package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Documented pre-trade risk limits for broker execution (Story 16.8 / PS-GR7).
 *
 * <p>Load precedence: {@code TRADING_BRIDGE_RISK_LIMITS} env →
 * {@code data/runtime/risk-limits.json} under repo root → {@link #DEFAULT}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskLimits(
    double maxPositionSize,
    double maxOpenExposure,
    Double maxDailyDrawdownPct,
    Double dailyLossLimitPct,
    Double weeklyLossLimitPct
) {

    public static final RiskLimits DEFAULT = new RiskLimits(1_000_000, 2_000_000, 5.0, 5.0, 10.0);

    public RiskLimits(double maxPositionSize, double maxOpenExposure) {
        this(maxPositionSize, maxOpenExposure, 5.0, 5.0, 10.0);
    }

    public RiskLimits(double maxPositionSize, double maxOpenExposure, Double maxDailyDrawdownPct) {
        this(maxPositionSize, maxOpenExposure, maxDailyDrawdownPct, 5.0, 10.0);
    }

    public RiskLimits {
        if (maxDailyDrawdownPct == null) {
            maxDailyDrawdownPct = 5.0;
        }
        if (dailyLossLimitPct == null) {
            dailyLossLimitPct = 5.0;
        }
        if (weeklyLossLimitPct == null) {
            weeklyLossLimitPct = 10.0;
        }
    }

    /** Disabled when {@code <= 0}. */
    public boolean dailyDrawdownGuardEnabled() {
        return maxDailyDrawdownPct > 0;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RiskLimits loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_RISK_LIMITS");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/risk-limits.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT;
    }

    public static RiskLimits load(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), RiskLimits.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load risk limits from " + path, e);
        }
    }
}
