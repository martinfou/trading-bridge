package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy;
import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy;
import com.martinfou.trading.strategies.creative.NfpWeekStrategy;
import com.martinfou.trading.strategies.creative.VWPReversionStrategy;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the 2026-10-01 instrument-resolution defect: the runner resolved the OANDA
 * instrument from the strategy's DISPLAY NAME and silently fell through to {@code GBP_JPY}, so
 * {@code vwpreversion} traded GBP_JPY while its config declared USD_CHF.
 *
 * <p>These tests pin stories 1.1 (instrument from an explicit source), 1.2 (startup guard) and 1.9
 * (config completion). They fail on the pre-fix code: {@code resolveInstrument} did not exist and
 * {@code toOandaSymbol(Strategy)} returned GBP_JPY at the end of the name table.
 */
class LiveStrategyRunnerInstrumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode config(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode emptyConfig() {
        return config("{\"strategies\":{}}");
    }

    // ========================================================================
    // Story 1.1 — the instrument comes from an explicit source (config key)
    // ========================================================================

    @Test
    @DisplayName("vwpreversion resolves USD_CHF from config, not GBP_JPY from the name")
    void vwpreversionResolvesUsdChfFromConfig() {
        JsonNode cfg = config("{\"strategies\":{\"vwpreversion\":{\"instrument\":\"USD_CHF\"}}}");

        LiveStrategyRunner.InstrumentResolution r =
            LiveStrategyRunner.resolveInstrument("vwpreversion", new VWPReversionStrategy(), cfg);

        assertEquals("USD_CHF", r.instrument,
            "vwpreversion must trade the pair its config declares, not the GBP_JPY name-table default");
        assertEquals(LiveStrategyRunner.SOURCE_CONFIG + "vwpreversion.instrument", r.source);
    }

    @Test
    @DisplayName("consecbar stays on GBP_JPY — non-regression")
    void consecbarStaysOnGbpJpy() {
        JsonNode cfg = config("{\"strategies\":{\"consecbar\":{\"instrument\":\"GBP_JPY\"}}}");

        LiveStrategyRunner.InstrumentResolution r =
            LiveStrategyRunner.resolveInstrument("consecbar", new ConsecutiveBarExhaustionStrategy(), cfg);

        assertEquals("GBP_JPY", r.instrument);
        assertEquals(LiveStrategyRunner.SOURCE_CONFIG + "consecbar.instrument", r.source);
    }

    @Test
    @DisplayName("the four newly-configured strategies resolve their configured pair")
    void newlyConfiguredStrategiesResolveTheirPair() {
        JsonNode cfg = config("{"
            + "\"strategies\":{"
            + "\"compmomentum\":{\"instrument\":\"USD_JPY\"},"
            + "\"monthweekphase\":{\"instrument\":\"USD_JPY\"},"
            + "\"ltrsi3\":{\"instrument\":\"EUR_USD\"},"
            + "\"nfpweek\":{\"instrument\":\"EUR_USD\"}"
            + "}}");

        Map<String, Strategy> strategies = Map.of(
            "compmomentum", new CompositeMomentumRankingStrategy(),
            "monthweekphase", new MonthWeekPhaseStrategy(),
            "ltrsi3", new LtRSI3Momentum(),
            "nfpweek", new NfpWeekStrategy());
        Map<String, String> expected = Map.of(
            "compmomentum", "USD_JPY",
            "monthweekphase", "USD_JPY",
            "ltrsi3", "EUR_USD",
            "nfpweek", "EUR_USD");

        for (Map.Entry<String, String> e : expected.entrySet()) {
            LiveStrategyRunner.InstrumentResolution r =
                LiveStrategyRunner.resolveInstrument(e.getKey(), strategies.get(e.getKey()), cfg);
            assertEquals(e.getValue(), r.instrument, e.getKey());
        }
    }

    @Test
    @DisplayName("no config entry and no name match fails loudly — never a silent GBP_JPY")
    void vwpreversionWithoutConfigFailsLoudlyInsteadOfDefaulting() {
        // "🔁 VWAP Reversion" does not contain "VWPREVERSION", so the name table cannot match it either:
        // the old code silently returned GBP_JPY here; the new code must throw.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LiveStrategyRunner.resolveInstrument("vwpreversion", new VWPReversionStrategy(), emptyConfig()));

        assertTrue(ex.getMessage().contains("vwpreversion"), "the refusal must name the strategy");
        assertTrue(ex.getMessage().contains("GBP_JPY") == false,
            "the refusal must NOT return a silent GBP_JPY");
    }

    // ========================================================================
    // Story 1.2 — startup guard: config vs resolved
    // ========================================================================

    @Test
    @DisplayName("a strategy absent from config refuses to start")
    void absentFromConfigRefusesToStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LiveStrategyRunner.verifyInstrumentConsistency("nfpweek", "EUR_USD",
                LiveStrategyRunner.SOURCE_CONFIG + "nfpweek.instrument", emptyConfig()));

        assertTrue(ex.getMessage().contains("nfpweek"));
        assertTrue(ex.getMessage().contains("no strategies.nfpweek entry"));
    }

    @Test
    @DisplayName("a resolved-vs-config mismatch refuses to start and names all four facts")
    void inconsistentConfigRefusesToStart() {
        JsonNode cfg = config("{\"strategies\":{\"consecbar\":{\"instrument\":\"USD_JPY\"}}}");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LiveStrategyRunner.verifyInstrumentConsistency("consecbar", "GBP_JPY",
                LiveStrategyRunner.SOURCE_DISPLAY_NAME, cfg));

        assertTrue(ex.getMessage().contains("consecbar"), "names the strategy");
        assertTrue(ex.getMessage().contains("USD_JPY"), "names the expected pair");
        assertTrue(ex.getMessage().contains("GBP_JPY"), "names the resolved pair");
        assertTrue(ex.getMessage().contains(LiveStrategyRunner.SOURCE_DISPLAY_NAME), "names the source");
    }

    @Test
    @DisplayName("a consistent config does not refuse startup")
    void consistentConfigStarts() {
        JsonNode cfg = config("{\"strategies\":{\"vwpreversion\":{\"instrument\":\"USD_CHF\"}}}");

        assertDoesNotThrow(() ->
            LiveStrategyRunner.verifyInstrumentConsistency("vwpreversion", "USD_CHF",
                LiveStrategyRunner.SOURCE_CONFIG + "vwpreversion.instrument", cfg));
    }

    // ========================================================================
    // Story 1.9 — the live config declares all deployed strategies, risk sum <= 3%
    // ========================================================================

    /** The six strategies that actually run in containers (docker-compose.yml). The 7 {@code 2_*} keys are not deployed. */
    static final List<String> DEPLOYED = List.of(
        "vwpreversion", "consecbar", "compmomentum", "monthweekphase", "ltrsi3", "nfpweek");

    /**
     * The account ceiling is 3% of simultaneous NAV again: D24 raised to 4% by D42 for the week-of-12-
     * October news lane, and restored to 3% by D68 (2026-10-21) in the same edit that retired the lane.
     * Nothing here reopens the 4% envelope; a future lane has to win its own decision.
     */
    private static final double ACCOUNT_RISK_CAP_PCT = 3.0;

    @Test
    @DisplayName("docker-compose.yml runs exactly the DEPLOYED strategies, so a new service cannot slip under the cap")
    void composeServicesMatchTheDeployedList() throws IOException {
        java.util.List<String> fromCompose = composeStrategyKeys();
        assertEquals(DEPLOYED.size(), fromCompose.size(),
            "docker-compose.yml and the DEPLOYED list disagree: a service that is not in DEPLOYED is trading "
                + "unaccounted risk (measured 2026-10-21: the news lane ran nine days outside every check)");
        assertEquals(new java.util.TreeSet<>(DEPLOYED), new java.util.TreeSet<>(fromCompose),
            "docker-compose.yml and the DEPLOYED list disagree");
    }

    /**
     * Every strategy key a compose service would start, in file order and with duplicates kept.
     *
     * <p>Two defects this must not have, both caught in review: a {@code Set} hides a SECOND service running a
     * key already deployed (its risk would be summed once), and a parser that silently skips a service written
     * in another YAML shape reads as "no extra service". So the scan tracks the enclosing service, accepts list
     * and map forms, and fails when any service yields no key.
     */
    private static java.util.List<String> composeStrategyKeys() throws IOException {
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.regex.Pattern strategyLine = java.util.regex.Pattern.compile(
            "^\\s*-?\\s*[A-Za-z0-9_]*STRATEGY\\s*[=:]\\s*(.+)$");
        java.util.regex.Pattern defaulted = java.util.regex.Pattern.compile("^\\$\\{[^:}]+:-(.*)}$");
        java.util.regex.Pattern serviceLine = java.util.regex.Pattern.compile("^  ([a-z][a-z0-9-]*):\\s*$");

        java.util.Map<String, Integer> strategyLinesPerService = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> commandPassesStrategy = new java.util.LinkedHashMap<>();
        String service = null;
        for (String line : Files.readAllLines(locateCompose())) {
            if (line.startsWith("#")) continue;              // a commented service is not a deployment
            if (line.equals("volumes:") || line.equals("networks:")) break;   // the service list is over
            var svc = serviceLine.matcher(line);
            if (svc.matches()) {
                service = svc.group(1);
                strategyLinesPerService.put(service, 0);
                commandPassesStrategy.put(service, Boolean.FALSE);
                continue;
            }
            if (service == null) continue;
            if (line.trim().startsWith("command:")) {
                // The runner reads its strategy list from argv[2], so what the CAP must count is what the
                // command actually passes, not only what environment: happens to declare.
                commandPassesStrategy.put(service, line.contains("$${STRATEGY}"));
            }
            var m = strategyLine.matcher(line);
            if (!m.matches()) continue;
            String value = m.group(1).trim().replaceAll("^[\"']|[\"']$", "");
            var d = defaulted.matcher(value);
            if (d.matches()) value = d.group(1).trim();
            if (value.isEmpty()) continue;
            strategyLinesPerService.merge(service, 1, Integer::sum);
            for (String key : value.split("\\s+")) {
                if (!key.isEmpty()) keys.add(key);
            }
        }
        assertFalse(strategyLinesPerService.isEmpty(), "no service parsed out of docker-compose.yml");
        strategyLinesPerService.forEach((name, count) ->
            assertEquals(1, count, "service " + name + " declares " + count + " STRATEGY line(s): the cap test "
                + "reads exactly one per service, so an unreadable service must fail here, not pass as absent"));
        commandPassesStrategy.forEach((name, passes) ->
            assertTrue(passes, "service " + name + " does not pass STRATEGY on its command line: the strategy "
                + "that starts is not the one the cap test just counted"));
        return keys;
    }

    private static Path locateCompose() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10; i++) {
            Path candidate = dir.resolve("docker-compose.yml");
            if (Files.exists(candidate)) return candidate;
            if (dir.getParent() == null) break;
            dir = dir.getParent();
        }
        throw new AssertionError("docker-compose.yml not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    @DisplayName("live-config.json declares every deployed strategy with an instrument and a risk")
    void liveConfigDeclaresEveryDeployedStrategy() throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(locateLiveConfig()));
        JsonNode strategies = root.get("strategies");

        for (String key : DEPLOYED) {
            JsonNode entry = strategies.get(key);
            assertTrue(entry != null && !entry.isNull(), "missing strategies." + key);
            assertTrue(entry.hasNonNull("instrument"), key + " has no instrument");
            assertTrue(entry.hasNonNull("computedRiskPct"), key + " has no computedRiskPct");
            assertTrue(entry.get("computedRiskPct").asDouble() > 0.0,
                key + " declares a non-positive computedRiskPct: it would lower the sum it is meant to occupy");
            assertTrue(entry.hasNonNull("granularity"), key + " has no granularity");
            assertTrue(entry.hasNonNull("positionSizeUnits"), key + " has no positionSizeUnits");
            assertTrue(entry.hasNonNull("backtestMetrics"), key + " has no backtestMetrics");
        }
    }

    @Test
    @DisplayName("the sum of computedRiskPct over deployed strategies stays under the 3% account cap (D24, restored by D68)")
    void deployedRiskSumStaysUnderCap() throws IOException {
        JsonNode strategies = MAPPER.readTree(Files.readString(locateLiveConfig())).get("strategies");

        double sum = 0.0;
        for (String key : composeStrategyKeys()) {
            sum += strategies.get(key).get("computedRiskPct").asDouble();
        }

        assertTrue(sum <= ACCOUNT_RISK_CAP_PCT,
            "deployed risk sum " + sum + "% exceeds the " + ACCOUNT_RISK_CAP_PCT
                + "% account cap (D24; the 4% envelope of D42 closed with the news lane on 2026-10-21)");
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
