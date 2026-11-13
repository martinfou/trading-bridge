package com.martinfou.trading.strategies.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.builtin.DlrMeanReversion;
import com.martinfou.trading.strategies.builtin.LondonOpenRangeBreakoutStrategy;
import com.martinfou.trading.strategies.builtin.LtCrossMomentum;
import com.martinfou.trading.strategies.builtin.LtDoubleMA;
import com.martinfou.trading.strategies.builtin.MondayReversionStrategy;
import com.martinfou.trading.strategies.builtin.MonthWeekPhaseStrategy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("londonorb resolves EUR_USD from config, not GBP_JPY from the name")
    void vwpreversionResolvesUsdChfFromConfig() {
        JsonNode cfg = config("{\"strategies\":{\"londonorb\":{\"instrument\":\"EUR_USD\"}}}");

        LiveStrategyRunner.InstrumentResolution r =
            LiveStrategyRunner.resolveInstrument("londonorb", new LondonOpenRangeBreakoutStrategy(), cfg);

        assertEquals("EUR_USD", r.instrument,
            "londonorb must trade the pair its config declares, not the GBP_JPY name-table default");
        assertEquals(LiveStrategyRunner.SOURCE_CONFIG + "londonorb.instrument", r.source);
    }

    @Test
    @DisplayName("monthweekphase stays on GBP_JPY — non-regression")
    void consecbarStaysOnGbpJpy() {
        JsonNode cfg = config("{\"strategies\":{\"monthweekphase\":{\"instrument\":\"GBP_JPY\"}}}");

        LiveStrategyRunner.InstrumentResolution r =
            LiveStrategyRunner.resolveInstrument("monthweekphase", new MonthWeekPhaseStrategy(), cfg);

        assertEquals("GBP_JPY", r.instrument);
        assertEquals(LiveStrategyRunner.SOURCE_CONFIG + "monthweekphase.instrument", r.source);
    }

    @Test
    @DisplayName("the four newly-configured strategies resolve their configured pair")
    void newlyConfiguredStrategiesResolveTheirPair() {
        JsonNode cfg = config("{"
            + "\"strategies\":{"
            + "\"mondayrev\":{\"instrument\":\"EUR_USD\"},"
            + "\"monthweekphase\":{\"instrument\":\"USD_JPY\"},"
            + "\"ltdoublema\":{\"instrument\":\"EUR_USD\"},"
            + "\"ltcrossmom\":{\"instrument\":\"EUR_USD\"}"
            + "}}");

        Map<String, Strategy> strategies = Map.of(
            "mondayrev", new MondayReversionStrategy(),
            "monthweekphase", new MonthWeekPhaseStrategy(),
            "ltdoublema", new LtDoubleMA(),
            "ltcrossmom", new LtCrossMomentum());
        Map<String, String> expected = Map.of(
            "mondayrev", "EUR_USD",
            "monthweekphase", "USD_JPY",
            "ltdoublema", "EUR_USD",
            "ltcrossmom", "EUR_USD");

        for (Map.Entry<String, String> e : expected.entrySet()) {
            LiveStrategyRunner.InstrumentResolution r =
                LiveStrategyRunner.resolveInstrument(e.getKey(), strategies.get(e.getKey()), cfg);
            assertEquals(e.getValue(), r.instrument, e.getKey());
        }
    }

    @Test
    @DisplayName("no config entry and no name match fails loudly — never a silent GBP_JPY")
    void vwpreversionWithoutConfigFailsLoudlyInsteadOfDefaulting() {
        // "🔁 VWAP Reversion" does not contain any pair token, so the name table cannot match it either:
        // the old code silently returned GBP_JPY here; the new code must throw.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LiveStrategyRunner.resolveInstrument("vwpreversion",
                new DlrMeanReversion("🔁 VWAP Reversion", "USD_CHF"), emptyConfig()));

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

    /**
     * The strategies that actually run in {@code oanda-paper}, and on {@code oanda-live} as well
     * (both services in docker-compose.yml declare the same audited set). {@code monthweekphase} left the
     * deployment on 2026-11-07 together with its {@code live-config.json} entry; keeping it in the list would
     * re-arm the "no silent fallback" startup guard against a config that no longer has the key.
     */
    static final List<String> DEPLOYED = List.of(
        "londonorb", "mondayrev", "ltdoublema", "ltcrossmom");

    /**
     * The account ceiling is 3% of simultaneous NAV again: D24 raised to 4% by D42 for the week-of-12-
     * October news lane, and restored to 3% by D68 (2026-10-21) in the same edit that retired the lane.
     * Nothing here reopens the 4% envelope; a future lane has to win its own decision.
     */
    private static final double ACCOUNT_RISK_CAP_PCT = 3.0;

    @Test
    @DisplayName("docker-compose.yml runs exactly the DEPLOYED strategies, so a new service cannot slip under the cap")
    void composeServicesMatchTheDeployedList() throws IOException {
        java.util.Map<String, java.util.List<String>> byService = composeStrategyKeysByService();
        for (var e : byService.entrySet()) {
            String service = e.getKey();
            java.util.List<String> keys = e.getValue();
            // Each service is its own account, so each one must carry the audited set exactly once: a duplicate
            // key inside one service would run the same risk twice under a single risk budget.
            assertEquals(DEPLOYED.size(), keys.size(), "service " + service + " declares " + keys.size()
                + " strategy key(s) for " + DEPLOYED.size() + " in DEPLOYED");
            assertEquals(new java.util.TreeSet<>(DEPLOYED), new java.util.TreeSet<>(keys),
                "service " + service + " and the DEPLOYED list disagree: a strategy that is not in DEPLOYED is "
                    + "trading unaccounted risk (measured 2026-10-21: the news lane ran nine days outside every check)");
        }
    }

    /**
     * Every strategy key each compose service would start, in file order and with duplicates kept.
     *
     * <p>Why per service: {@code docker-compose.yml} holds a paper and a live instance of the same audited set,
     * on two separate accounts. Flattening them double-counted every key and read as ten deployed strategies;
     * the risk cap is per account, so the comparison has to be per service.
     *
     * <p>Two defects this must not have, both caught in review: a {@code Set} hides a SECOND service running a
     * key already deployed (its risk would be summed once), and a parser that silently skips a service written
     * in another YAML shape reads as "no extra service". So the scan tracks the enclosing service, accepts list
     * and map forms, and fails when any service yields no key.
     */
    private static java.util.Map<String, java.util.List<String>> composeStrategyKeysByService() throws IOException {
        java.util.Map<String, java.util.List<String>> keysPerService = new java.util.LinkedHashMap<>();
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
                keysPerService.put(service, new java.util.ArrayList<>());
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
                if (!key.isEmpty()) keysPerService.get(service).add(key);
            }
        }
        assertFalse(strategyLinesPerService.isEmpty(), "no service parsed out of docker-compose.yml");
        strategyLinesPerService.forEach((name, count) ->
            assertEquals(1, count, "service " + name + " declares " + count + " STRATEGY line(s): the cap test "
                + "reads exactly one per service, so an unreadable service must fail here, not pass as absent"));
        commandPassesStrategy.forEach((name, passes) ->
            assertTrue(passes, "service " + name + " does not pass STRATEGY on its command line: the strategy "
                + "that starts is not the one the cap test just counted"));
        return keysPerService;
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

        // Per account: every service (paper, live) runs the same audited set against its own NAV, so the cap is
        // the sum over DEPLOYED once, not over each compose service.
        double sum = 0.0;
        for (String key : DEPLOYED) {
            JsonNode entry = strategies.get(key);
            assertNotNull(entry, "missing strategies." + key + " while summing deployed risk");
            sum += entry.get("computedRiskPct").asDouble();
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

    @Test
    @DisplayName("circuit breaker trips on 5 consecutive losses and blocks new entries")
    void circuitBreakerTripsOnConsecutiveLosses() throws IOException {
        Path tmpState = Files.createTempDirectory("cb-test");
        LiveStrategyRunner runner = new LiveStrategyRunner(
            "dummy-key", "101-002-4729622-014", new MonthWeekPhaseStrategy("MonthWeekPhase GBP_JPY", "GBP_JPY"),
            "monthweekphase", "H1", 60, tmpState);

        assertFalse(runner.isKilled());
        for (int i = 1; i <= 4; i++) {
            runner.completeReconciliation("t-" + i, -5.0);
            assertFalse(runner.isKilled(), "should not trip before 5th loss");
        }
        runner.completeReconciliation("t-5", -5.0);
        assertTrue(runner.isKilled(), "5th consecutive loss must trip circuit breaker");
        assertTrue(runner.getKillReason().contains("CONSECUTIVE_LOSSES_BREACH"));

        runner.resumeStrategy();
        assertFalse(runner.isKilled());
        assertEquals(0, runner.getConsecutiveLosses());
    }
}
