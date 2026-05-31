package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Documented numeric thresholds for promote gates (Story 15.5 / PS-GR3).
 *
 * <p>Load precedence: explicit path → {@code TRADING_BRIDGE_PROMOTE_GATES} env →
 * {@code data/runtime/promote-gates.json} under repo root → {@link #DEFAULT}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromoteGateThresholds(
    int minTrades,
    double maxDrawdownPct,
    double minReturnPct,
    double goldenReturnTolerancePct,
    int paperDaysBeforeLive,
    boolean validationModuleEnabled
) {

    public static final PromoteGateThresholds DEFAULT = new PromoteGateThresholds(
        1,
        15.0,
        -50.0,
        1.0,
        30,
        false);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PromoteGateThresholds loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_PROMOTE_GATES");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/promote-gates.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT;
    }

    public static PromoteGateThresholds load(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), PromoteGateThresholds.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load promote gate thresholds from " + path, e);
        }
    }
}
