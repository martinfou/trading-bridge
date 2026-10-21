package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage of {@code LiveStrategyRunner.getStrategyMap()} over the strategies that actually run.
 *
 * <p>Why this test exists: {@code getStrategyMap()} builds EVERY entry inside one {@code try}, so a single
 * unresolvable class name makes it throw for all strategies and every runner refuses to start. Only a test
 * that walks the map catches that before a deploy. This replaces {@code NewsWeekStrategyMapTest}, which
 * covered the week-of-12-October lane and was deleted with it (D67/D68, 2026-10-21): the mapping mechanism
 * still needs a guard, the expired date-coded strategies do not.
 *
 * <p>{@code DEPLOYED} lives in {@code LiveStrategyRunnerInstrumentTest}, which also proves it equals the set
 * of services declared in {@code docker-compose.yml}, so walking it here covers what containers run.
 */
class StrategyMapCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("every deployed strategy resolves to an instantiable class in the runner's map")
    void everyDeployedEntryResolvesAndInstantiates() throws Exception {
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : LiveStrategyRunnerInstrumentTest.DEPLOYED) {
            Class<? extends Strategy> clazz = map.get(name);
            assertNotNull(clazz, name + " is missing from the strategy map (a service would refuse to start)");
            Strategy strategy = clazz.getDeclaredConstructor().newInstance();
            assertNotNull(strategy.name(), name + " instantiates");
        }
    }

    @Test
    @DisplayName("each deployed strategy resolves to the instrument its live-config.json entry declares")
    void deployedStrategiesMatchTheirConfiguredInstrument() throws Exception {
        JsonNode config = MAPPER.readTree(Files.readString(locateLiveConfig()));
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : LiveStrategyRunnerInstrumentTest.DEPLOYED) {
            Strategy strategy = map.get(name).getDeclaredConstructor().newInstance();

            var resolution = LiveStrategyRunner.resolveInstrument(name, strategy, config);
            assertDoesNotThrow(() ->
                    LiveStrategyRunner.verifyInstrumentConsistency(name, resolution.instrument,
                        resolution.source, config),
                name + " would refuse to start: config and resolved instrument disagree");
            assertNotNull(strategy.name(), name + " has a display name");
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
