package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locked OOS holdout gate configuration (Story 19.4 / PS-GR5). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OosHoldoutConfig(
    boolean enabled,
    double holdoutPct,
    int minHoldoutBars,
    double maxDrawdownPct,
    double minReturnPct
) {

    public static final OosHoldoutConfig DEFAULT = new OosHoldoutConfig(
        false,
        0.20,
        50,
        20.0,
        -30.0);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static OosHoldoutConfig loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_OOS_HOLDOUT");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/oos-holdout.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT;
    }

    public static OosHoldoutConfig load(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), OosHoldoutConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load OOS holdout config from " + path, e);
        }
    }
}
