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
    @DisplayName("every deployed strategy is pinned on the instrument live-config.json declares, before and after reconciliation")
    void deployedStrategiesActOnTheirConfiguredInstrument() throws Exception {
        JsonNode config = MAPPER.readTree(Files.readString(locateLiveConfig()));
        var map = LiveStrategyRunner.getStrategyMap();

        for (String name : LiveStrategyRunnerInstrumentTest.DEPLOYED) {
            Strategy strategy = map.get(name).getDeclaredConstructor().newInstance();

            var resolution = LiveStrategyRunner.resolveInstrument(name, strategy, config);
            assertNotNull(resolution.instrument, name + " resolves no instrument at all");

            // BEFORE reconciliation: the class's own default. Several strategies declare a FINAL symbol, and
            // reconcileInstrument's reflective write is a documented no-op on a final field, so a class that
            // ships the wrong pair would keep it forever. This is the assertion that catches that.
            assertPinnedPair(name, strategy, resolution.instrument, "before reconciliation", true);

            strategy.reconcileInstrument(resolution.instrument);
            assertPinnedPair(name, strategy, resolution.instrument, "after reconciliation", false);

            assertDoesNotThrow(() ->
                    LiveStrategyRunner.verifyInstrumentConsistency(name, resolution.instrument,
                        resolution.source, config),
                name + " would refuse to start: config and resolved instrument disagree");
        }
    }

    @Test
    @DisplayName("Gold breakout strategy entries resolve and instantiate for all registered aliases")
    void goldBreakoutStrategiesResolveAndInstantiate() throws Exception {
        JsonNode config = MAPPER.readTree(Files.readString(locateLiveConfig()));
        var map = LiveStrategyRunner.getStrategyMap();

        for (String alias : java.util.List.of("sq-gold-m30", "sq_gold_m30", "gold_breakout_m30")) {
            Class<? extends Strategy> clazz = map.get(alias);
            assertNotNull(clazz, alias + " must be present in getStrategyMap()");
            Strategy strategy = clazz.getDeclaredConstructor().newInstance();
            assertEquals("SQ_Gold_Breakout_M30_1_10_110", strategy.name());

            var resolution = LiveStrategyRunner.resolveInstrument(alias, strategy, config);
            assertEquals("MGC", resolution.instrument, alias + " must resolve to MGC");

            assertPinnedPair(alias, strategy, "MGC", "before reconciliation", true);
            strategy.reconcileInstrument(resolution.instrument);
            assertPinnedPair(alias, strategy, "MGC", "after reconciliation", false);

            assertDoesNotThrow(() ->
                LiveStrategyRunner.verifyInstrumentConsistency(alias, resolution.instrument,
                    resolution.source, config));
        }
    }

    /**
     * Assert every instrument field the strategy carries ({@code symbol}, {@code oandaSymbol}) equals the pair
     * the runner resolved. A blank value is a value still to be set, not a mismatch, so it is skipped; the test
     * fails when the class carries NO such field at all, because then this method proves nothing and the
     * strategy could be trading any pair.
     */
    private static void assertPinnedPair(String name, Strategy strategy, String expected, String moment,
                                         boolean onlyFinal) {
        int seen = 0;
        for (String fieldName : new String[] {"symbol", "oandaSymbol"}) {
            java.lang.reflect.Field field = null;
            for (Class<?> c = strategy.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                    // keep walking: the base class may hold it
                }
            }
            if (field == null || field.getType() != String.class) continue;
            // Only a FINAL field is dangerous before reconciliation: the reflective write is a documented
            // no-op there, so a wrong default survives. A mutable one is precisely what reconcileInstrument
            // exists to correct, and the "after" call checks it.
            if (onlyFinal && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                continue;   // a mutable field is corrected by reconcileInstrument, checked by the "after" pass
            }
            field.setAccessible(true);
            try {
                Object pinned = field.get(strategy);
                if (pinned == null || pinned.toString().isBlank()) continue;
                seen++;
                // Same pair, two notations: strategies declare "USD/CHF", the runner resolves "USD_CHF".
                String actual = pinned.toString().trim().replace('/', '_').toUpperCase();
                assertEquals(expected.toUpperCase(), actual,
                    name + " carries " + fieldName + "=" + pinned + " " + moment + ", but the runner resolves "
                        + expected + ": it would drop every bar or trade a stale pair");
            } catch (IllegalAccessException e) {
                fail(name + ": cannot read " + fieldName + " (" + e.getMessage() + ")");
            }
        }
        // The vacuity guard belongs to the "after" pass: the "before" pass skirts every mutable field on
        // purpose, so a strategy with only a mutable pair legitimately shows nothing there.
        if (!onlyFinal) {
            assertTrue(seen > 0, name + " carries no readable symbol/oandaSymbol field: this test would pass "
                + "vacuously, so the class must expose the pair it acts on");
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
