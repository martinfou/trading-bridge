package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The weekly-news entries of the runner's strategy map (week of 2026-10-12).
 *
 * <p>Why this test exists: {@code getStrategyMap()} builds every entry inside ONE try, so a single
 * unresolvable class name makes it throw for ALL strategies and the runner refuses to start. The
 * news strategies are reached through {@code Class.forName} on inner-class names containing
 * {@code $}, which nothing else exercises. This is also the last line of defence before a deploy:
 * it proves the live-config instrument each strategy pins matches the one the runner resolves, the
 * same check that otherwise refuses to start on the container.
 */
class NewsWeekStrategyMapTest {

    private static final List<String> NEWS_WEEK = List.of(
        "newsoct12cpi", "newsoct12ppi", "newsoct12chinacpi", "newsoct12fade");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("every news-week entry resolves to an instantiable strategy")
    void newsWeekEntriesResolveAndInstantiate() throws Exception {
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : NEWS_WEEK) {
            Class<? extends Strategy> clazz = map.get(name);
            assertNotNull(clazz, name + " is missing from the strategy map");
            Strategy strategy = clazz.getDeclaredConstructor().newInstance();
            assertNotNull(strategy.name(), name + " instantiates");
        }
    }

    @Test
    @DisplayName("each news-week strategy resolves to the instrument its live-config entry declares")
    void newsWeekStrategiesMatchTheirConfiguredInstrument() throws Exception {
        JsonNode config = MAPPER.readTree(Files.readString(locateLiveConfig()));
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : NEWS_WEEK) {
            Strategy strategy = map.get(name).getDeclaredConstructor().newInstance();

            var resolution = LiveStrategyRunner.resolveInstrument(name, strategy, config);
            assertDoesNotThrow(() ->
                    LiveStrategyRunner.verifyInstrumentConsistency(name, resolution.instrument,
                        resolution.source, config),
                name + " would refuse to start: config and resolved instrument disagree");

            JsonNode configured = config.get("strategies").get(name);
            assertNotNull(configured, "no strategies." + name + " entry in live-config.json");
            assertEquals(configured.get("instrument").asText(), resolution.instrument,
                name + ": the pinned symbol and the config instrument must be the same pair");
            assertTrue(configured.get("computedRiskPct").asDouble() > 0,
                name + " has no usable risk %");
        }
    }

    private static Path locateLiveConfig() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10; i++) {
            Path candidate = dir.resolve("config/live-config.json");
            if (Files.exists(candidate)) return candidate;
            if (dir.getParent() == null) break;
            dir = dir.getParent();
        }
        throw new AssertionError("config/live-config.json not found from " + Paths.get("").toAbsolutePath());
    }
}
