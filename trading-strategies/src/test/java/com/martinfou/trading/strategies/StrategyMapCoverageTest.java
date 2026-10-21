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
    @DisplayName("every deployed strategy ends up pinned on the instrument live-config.json declares")
    void deployedStrategiesActOnTheirConfiguredInstrument() throws Exception {
        JsonNode config = MAPPER.readTree(Files.readString(locateLiveConfig()));
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : LiveStrategyRunnerInstrumentTest.DEPLOYED) {
            Strategy strategy = map.get(name).getDeclaredConstructor().newInstance();

            var resolution = LiveStrategyRunner.resolveInstrument(name, strategy, config);
            assertNotNull(resolution.instrument, name + " resolves no instrument at all");
            assertDoesNotThrow(() ->
                    LiveStrategyRunner.verifyInstrumentConsistency(name, resolution.instrument,
                        resolution.source, config),
                name + " would refuse to start: config and resolved instrument disagree");

            // resolveInstrument reads the CONFIG value, so comparing it to the config again proves nothing.
            // What decides behaviour is the strategy's own pinned pair after reconciliation: a class that
            // keeps filtering bars on another pair silently trades the wrong instrument.
            strategy.reconcileInstrument(resolution.instrument);
            java.lang.reflect.Field symbol = null;
            for (Class<?> c = strategy.getClass(); c != null && symbol == null; c = c.getSuperclass()) {
                try {
                    symbol = c.getDeclaredField("symbol");
                } catch (NoSuchFieldException ignored) {
                    // keep walking: strategy classes inherit from a base that may hold the field
                }
            }
            if (symbol == null || symbol.getType() != String.class) continue;   // class pins no pair
            symbol.setAccessible(true);
            Object pinned = symbol.get(strategy);
            if (pinned == null) continue;
            assertEquals(resolution.instrument, pinned.toString(),
                name + " filters on " + pinned + " after reconciliation, but the runner resolves "
                    + resolution.instrument + ": it would drop every bar or trade a stale pair");
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
