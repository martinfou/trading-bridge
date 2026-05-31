package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Configurable stale-run detection thresholds (Epic 13 / Story 13.8). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaleThresholds(long runningStaleThresholdSeconds) {

    public static final StaleThresholds DEFAULT = new StaleThresholds(120);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static StaleThresholds loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_STALE_THRESHOLDS");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/stale-thresholds.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT.normalized();
    }

    public static StaleThresholds load(Path path) {
        try {
            StaleThresholds loaded = MAPPER.readValue(path.toFile(), StaleThresholds.class);
            return loaded.normalized();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load stale thresholds from " + path, e);
        }
    }

    public StaleThresholds normalized() {
        if (runningStaleThresholdSeconds > 0) {
            return this;
        }
        return DEFAULT;
    }
}
