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
import java.time.Instant;
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
    private static final String LATCHED_EMPTY_STRAT = "StateExportLatchedEmpty";
    private static final String LATCHED_OPEN_STRAT = "StateExportLatchedOpen";
    private static final String FLAT_RESUME_STRAT = "StateExportFlatResume";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveStrategyRunner runner(String shortName, ConsecutiveBarExhaustionStrategy strategy) {
        return new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, shortName, "H1", 60);
    }

    private static Path statePath(String shortName) {
        return Paths.get("/tmp/live-strategy-state-" + shortName + ".json");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String s : List.of(FLAT_STRAT, OPEN_STRAT, STALE_STRAT,
                LATCHED_EMPTY_STRAT, LATCHED_OPEN_STRAT, FLAT_RESUME_STRAT)) {
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

    /** An in-trade strategy keeps its side across save then resume, when the runner's book holds the trade. */
    @Test
    void inTradeSideSurvivesSaveThenResumeWhenBookBacksIt() throws Exception {
        Files.deleteIfExists(statePath(OPEN_STRAT));
        // The name carries a recognizable pair so the runner can resolve the instrument (and thus
        // match the book entry below) without live-config.json.
        ConsecutiveBarExhaustionStrategy strategy =
            new ConsecutiveBarExhaustionStrategy("ConsecBarExhaustion GBP_JPY");
        strategy.restoreState(2, 288, true, Order.Side.SELL, 3);
        LiveStrategyRunner r = runner(OPEN_STRAT, strategy);
        // A genuine open trade: the runner's own book must hold the position, or the restore would
        // (correctly) flatten a latched flag that nothing in the book backs.
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "101", "GBP_JPY", "SELL", 188.25, 1000.0, 187.9, 189.0, Instant.parse("2026-10-22T14:00:00Z")));
        r.saveStateNow();

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

    /**
     * AC1 — the production defect on 2026-10-22: a state file that claims
     * {@code strat_inTrade=true} <b>with</b> a textual side, while {@code activeTrades} is empty.
     * The runner's own book decides: with no open trade on the instrument, the strategy must be
     * restored FLAT, never latched. A textual side is exactly the case the previous guard
     * ({@code dir == null}) let through.
     */
    @Test
    void latchedInTradeWithEmptyBookRestoresFlat() throws Exception {
        Files.writeString(statePath(LATCHED_EMPTY_STRAT), """
            {
              "strategy" : "StateExportLatchedEmpty",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 13,
              "totalExits" : 12,
              "pnlAccountingVersion" : 3,
              "savedAt" : "2026-10-22T14:44:10Z",
              "strat_tradesToday" : 1,
              "strat_cooldownBars" : 0,
              "strat_inTrade" : true,
              "strat_tradeDirection" : "BUY",
              "strat_lastTradeDay" : 289,
              "activeTrades" : [ ],
              "pendingStops" : [ ]
            }
            """);

        ConsecutiveBarExhaustionStrategy resumed = new ConsecutiveBarExhaustionStrategy();
        runner(LATCHED_EMPTY_STRAT, resumed).resumeState();

        assertFalse(resumed.isInTrade(),
            "a latched inTrade flag with an empty book must restore flat, not keep the strategy stuck");
        assertNull(resumed.getTradeDirection(),
            "no side may be invented for a trade the book does not hold");
        assertEquals(1, resumed.getTradesToday(), "the rest of the state must still be restored");
    }

    /**
     * AC2 — no regression for a genuinely open trade: the same latched state, but the book holds
     * one open trade on the SAME instrument. The latch must be kept as saved.
     */
    @Test
    void latchedInTradeWithMatchingBookEntryKeepsLatch() throws Exception {
        Files.writeString(statePath(LATCHED_OPEN_STRAT), """
            {
              "strategy" : "StateExportLatchedOpen",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 13,
              "totalExits" : 12,
              "pnlAccountingVersion" : 3,
              "savedAt" : "2026-10-22T14:44:10Z",
              "strat_tradesToday" : 1,
              "strat_cooldownBars" : 0,
              "strat_inTrade" : true,
              "strat_tradeDirection" : "BUY",
              "strat_lastTradeDay" : 289,
              "activeTrades" : [ {
                "tradeId" : "101",
                "symbol" : "GBP_JPY",
                "side" : "BUY",
                "entryPrice" : 188.25,
                "quantity" : 1000.0,
                "stopLoss" : 187.9,
                "takeProfit" : 189.0,
                "entryTime" : "2026-10-22T14:00:00Z",
                "reconciliationStatus" : "CONFIRMED"
              } ],
              "pendingStops" : [ ]
            }
            """);

        ConsecutiveBarExhaustionStrategy resumed = new ConsecutiveBarExhaustionStrategy();
        runner(LATCHED_OPEN_STRAT, resumed).resumeState();

        assertTrue(resumed.isInTrade(),
            "a latched inTrade flag backed by an open trade on the book must be kept");
        assertEquals(Order.Side.BUY, resumed.getTradeDirection(),
            "the saved side must survive when the book confirms the trade");
    }

    /**
     * AC3 — a flat state stays flat: no correction, no error, and no invented side.
     */
    @Test
    void flatStateRestoresFlatWithoutError() throws Exception {
        Files.writeString(statePath(FLAT_RESUME_STRAT), """
            {
              "strategy" : "StateExportFlatResume",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 13,
              "totalExits" : 13,
              "pnlAccountingVersion" : 3,
              "savedAt" : "2026-10-22T14:44:10Z",
              "strat_tradesToday" : 0,
              "strat_cooldownBars" : 4,
              "strat_inTrade" : false,
              "strat_tradeDirection" : null,
              "strat_lastTradeDay" : 289,
              "activeTrades" : [ ],
              "pendingStops" : [ ]
            }
            """);

        ConsecutiveBarExhaustionStrategy resumed = new ConsecutiveBarExhaustionStrategy();
        runner(FLAT_RESUME_STRAT, resumed).resumeState();

        assertFalse(resumed.isInTrade(), "a flat state must stay flat");
        assertNull(resumed.getTradeDirection(), "a flat state has no side to invent");
        assertEquals(4, resumed.getCooldownBars(), "the cooldown must survive the round trip");
    }
}
