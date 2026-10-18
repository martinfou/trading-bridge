package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strategy-state export must survive a <em>flat</em> strategy.
 *
 * <p><b>Defect (measured in production, 2026-10-12 → 2026-10-18).</b> {@code exportState} wrote the
 * side with {@code ((Enum<?>) m.invoke(strategy)).name()}. A flat strategy answers {@code null}
 * there, so the export threw a NullPointerException whose message is the string {@code "null"}, the
 * generic catch logged {@code "Failed to save state for 'consecbar': null"} and <b>the whole save was
 * aborted</b>. 15 189 occurrences in six days; the state files of {@code consecbar} and
 * {@code vwpreversion} were frozen at their last in-trade snapshot, so a deployment restoring them
 * would have resumed an {@code inTrade=true} flag two days old with no active trade.
 *
 * <p><b>Rules asserted here.</b> A flat strategy saves ({@code strat_tradeDirection} explicit null),
 * an in-trade strategy keeps its side across a save/resume round trip, and a state file that claims
 * {@code inTrade} without a side restores as flat instead of aborting the resume.
 */
class LiveStrategyRunnerStateExportTest {

    private static final String FLAT_STRAT = "StateExportFlat";
    private static final String OPEN_STRAT = "StateExportOpen";
    private static final String STALE_STRAT = "StateExportStale";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveStrategyRunner runner(String shortName, ConsecutiveBarExhaustionStrategy strategy) {
        return new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, shortName, "H1", 60);
    }

    private static Path statePath(String shortName) {
        return Paths.get("/tmp/live-strategy-state-" + shortName + ".json");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String s : List.of(FLAT_STRAT, OPEN_STRAT, STALE_STRAT)) {
            Files.deleteIfExists(statePath(s));
            Files.deleteIfExists(Paths.get("/tmp/paper-status-" + s + ".json"));
        }
    }

    /** A flat strategy must save its state — the null side is a value, not a failure. */
    @Test
    void flatStrategyStateIsSavedWithExplicitNullSide() throws Exception {
        Files.deleteIfExists(statePath(FLAT_STRAT));
        ConsecutiveBarExhaustionStrategy strategy = new ConsecutiveBarExhaustionStrategy();
        // The shape the production export met: the strategy is flat and carries no side (a restored
        // flat state, and the way the deployed image reports a strategy that never entered). Reading
        // null here must not abort the save.
        strategy.restoreState(0, -1, false, null, 0);
        LiveStrategyRunner r = runner(FLAT_STRAT, strategy);

        r.saveStateNow();

        assertTrue(Files.exists(statePath(FLAT_STRAT)),
            "a flat strategy must still write its state file (the null side aborted the save before the fix)");
        JsonNode root = MAPPER.readTree(statePath(FLAT_STRAT).toFile());
        assertTrue(root.has("strat_tradeDirection"), "the key must be present so the round trip is observable");
        assertTrue(root.get("strat_tradeDirection").isNull(), "a flat strategy has no side to record");
        assertFalse(root.get("strat_inTrade").asBoolean(), "a strategy that never traded is not inTrade");
        assertNotNull(root.get("savedAt"), "the save must be timestamped, not inherited from an older file");
    }

    /** An in-trade strategy keeps its side across save then resume. */
    @Test
    void inTradeSideSurvivesSaveThenResume() throws Exception {
        Files.deleteIfExists(statePath(OPEN_STRAT));
        ConsecutiveBarExhaustionStrategy strategy = new ConsecutiveBarExhaustionStrategy();
        strategy.restoreState(2, 288, true, Order.Side.SELL, 3);
        runner(OPEN_STRAT, strategy).saveStateNow();

        JsonNode root = MAPPER.readTree(statePath(OPEN_STRAT).toFile());
        assertEquals("SELL", root.get("strat_tradeDirection").asText(),
            "the side of a live trade must reach the state file");

        ConsecutiveBarExhaustionStrategy resumed = new ConsecutiveBarExhaustionStrategy();
        runner(OPEN_STRAT, resumed).resumeState();

        assertTrue(resumed.isInTrade(), "an in-trade strategy must resume in trade");
        assertEquals(Order.Side.SELL, resumed.getTradeDirection(), "the side must survive the round trip");
        assertEquals(2, resumed.getTradesToday(), "the daily counter must survive the round trip");
        assertEquals(3, resumed.getCooldownBars(), "the cooldown must survive the round trip");
    }

    /**
     * The stale artifact this defect produced: {@code inTrade=true} with no side (and no active
     * trade). It must resume as flat, not abort the whole resume.
     */
    @Test
    void staleInTradeWithoutSideRestoresAsFlat() throws Exception {
        Files.writeString(statePath(STALE_STRAT), """
            {
              "strategy" : "StateExportStale",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 11,
              "totalExits" : 9,
              "pnlAccountingVersion" : 3,
              "inTrade" : false,
              "strat_tradesToday" : 1,
              "strat_cooldownBars" : 0,
              "strat_inTrade" : true,
              "strat_tradeDirection" : null,
              "strat_lastTradeDay" : 289,
              "activeTrades" : [ ],
              "pendingStops" : [ ]
            }
            """);

        ConsecutiveBarExhaustionStrategy resumed = new ConsecutiveBarExhaustionStrategy();
        runner(STALE_STRAT, resumed).resumeState();

        assertFalse(resumed.isInTrade(),
            "a flag with no side cannot describe a live trade: it must restore as flat");
        assertNull(resumed.getTradeDirection(), "no side must be invented");
        assertEquals(1, resumed.getTradesToday(), "the rest of the state must still be restored");
    }
}
