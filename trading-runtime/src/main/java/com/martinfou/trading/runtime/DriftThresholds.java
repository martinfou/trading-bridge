package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Configurable drift thresholds (FR-15 / Story 17.5). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DriftThresholds(
    int minObservationDays,
    int minTradesBeforeSignal,
    int rollingWindowDays,
    double drawdownReviewMultiplier,
    double drawdownPauseMultiplier,
    double winRateReviewDeltaPts,
    double winRatePauseDeltaPts,
    double tradeVolumeReviewRatio
) {

    public static final DriftThresholds DEFAULT = new DriftThresholds(
        14,
        20,
        30,
        1.5,
        2.0,
        15.0,
        25.0,
        0.5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static DriftThresholds loadDefault() {
        String env = System.getenv("TRADING_BRIDGE_DRIFT_THRESHOLDS");
        if (env != null && !env.isBlank()) {
            return load(Path.of(env));
        }
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            Path file = repoRoot.resolve("data/runtime/drift-thresholds.json");
            if (Files.isRegularFile(file)) {
                return load(file);
            }
        }
        return DEFAULT.normalized();
    }

    public static DriftThresholds load(Path path) {
        try {
            DriftThresholds loaded = MAPPER.readValue(path.toFile(), DriftThresholds.class);
            return loaded.normalized();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load drift thresholds from " + path, e);
        }
    }

    /** Rejects invalid config; falls back to defaults for non-positive rolling window. */
    public DriftThresholds normalized() {
        int window = rollingWindowDays > 0 ? rollingWindowDays : DEFAULT.rollingWindowDays();
        if (window == rollingWindowDays) {
            return this;
        }
        return new DriftThresholds(
            minObservationDays,
            minTradesBeforeSignal,
            window,
            drawdownReviewMultiplier,
            drawdownPauseMultiplier,
            winRateReviewDeltaPts,
            winRatePauseDeltaPts,
            tradeVolumeReviewRatio);
    }
}
