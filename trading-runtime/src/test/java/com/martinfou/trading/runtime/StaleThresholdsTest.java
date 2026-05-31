package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaleThresholdsTest {

    @Test
    void normalized_rejectsNonPositiveThreshold() {
        assertEquals(120, new StaleThresholds(0).normalized().runningStaleThresholdSeconds());
        assertEquals(120, new StaleThresholds(-5).normalized().runningStaleThresholdSeconds());
    }

    @Test
    void load_readsJsonFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("stale-thresholds.json");
        Files.writeString(file, """
            {"runningStaleThresholdSeconds": 300}
            """);

        assertEquals(300, StaleThresholds.load(file).runningStaleThresholdSeconds());
    }
}
