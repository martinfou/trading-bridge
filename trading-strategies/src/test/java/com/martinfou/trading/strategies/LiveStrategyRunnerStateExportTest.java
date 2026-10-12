package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reflective state export is the trader's crash memory: {@code activeTrades}, {@code pendingStops},
 * the P&amp;L ledger and the strategy's own latch all go through it.
 *
 * <p><b>Defect.</b> The exporter called {@code ((Enum<?>) m.invoke(strategy)).name()} on the trade
 * direction. {@code Strategy.syncPosition(null, 0, 0, 0)} — the flat notification the runner sends when
 * the VENUE closed a position (a stop-loss fill) — clears that field, so the getter returns null and the
 * call threw a NullPointerException. Because the reflection block sits inside the write's single
 * try/catch, one null aborted the ENTIRE state write: {@code LiveStrategyRunner} kept trading with no
 * persisted state at all.
 *
 * <p><b>Measured on the window, 2026-10-12.</b> {@code consecbar} and {@code vwpreversion} were stopped
 * out by the broker (04:16 and 09:22 UTC); from that instant every save failed, 1507 warnings in 24h,
 * and both state files froze at the moment of the stop-out while the runner kept trading. A restart in
 * that condition would have restored an in-trade latch with no tracked trade behind it.
 *
 * <p><b>Rules asserted here.</b> A null direction is a state (flat), not an error: the write must still
 * happen. A saved null direction is restored as flat, never invented as a side. And a strategy saved
 * in-trade while the runner tracks nothing comes back flat, because the runner's book is the authority
 * on whether a trade is open.
 */
class LiveStrategyRunnerStateExportTest {

    private static final String FLAT_STRAT = "StateExportFlat";
    private static final String TRACKED_STRAT = "StateExportTracked";
    private static final String UNKNOWN_DIR_STRAT = "StateExportUnknownDirection";
    private static final String BOXED_STRAT = "StateExportBoxedNull";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Records what {@code restoreState} received and can report a null direction, which is the shape a
     * strategy has after the venue flattened it.
     */
    private static class RecordingStrategy implements Strategy {

        private final boolean nullDirection;
        private boolean reportedInTrade;

        boolean restoreCalled;
        int restoredTradesToday = Integer.MIN_VALUE;
        int restoredLastTradeDay = Integer.MIN_VALUE;
        Boolean restoredInTrade;
        Order.Side restoredDirection;
        Integer restoredCooldown;

        RecordingStrategy(boolean nullDirection) {
            this.nullDirection = nullDirection;
        }

        @Override public String name() { return "Recording"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
        @Override public void reset() { }

        public int getTradesToday() { return 4; }
        public int getCooldownBars() { return 7; }
        public boolean isInTrade() { return reportedInTrade; }
        public Order.Side getTradeDirection() { return nullDirection ? null : Order.Side.SELL; }
        public int getLastTradeDay() { return 291; }

        public void restoreState(int tradesToday, int lastTradeDay, boolean inTrade,
                                 Order.Side tradeDirection, int cooldownBars) {
            this.restoreCalled = true;
            this.restoredTradesToday = tradesToday;
            this.restoredLastTradeDay = lastTradeDay;
            this.restoredInTrade = inTrade;
            this.restoredDirection = tradeDirection;
            this.restoredCooldown = cooldownBars;
        }
    }

    /**
     * The same defect class as the null direction, on a different getter: a boxed null that the export
     * unboxed used to cost the whole state file. The write must survive it and fall back to a default.
     */
    private static class BoxedNullStrategy implements Strategy {

        @Override public String name() { return "BoxedNull"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
        @Override public void reset() { }

        public Integer getTradesToday() { return null; }   // boxed null, not a primitive int
        public int getCooldownBars() { return 1; }
        public boolean isInTrade() { return false; }
        public Order.Side getTradeDirection() { return Order.Side.BUY; }
        public int getLastTradeDay() { return -1; }
        public void restoreState(int a, int b, boolean c, Order.Side d, int e) { }
    }

    private static Path statePath(String shortName) {
        return Paths.get("/tmp/live-strategy-state-" + shortName + ".json");
    }

    private static LiveStrategyRunner runner(String shortName, Strategy strategy) {
        return new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, shortName, "H1", 60);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String s : List.of(FLAT_STRAT, TRACKED_STRAT, UNKNOWN_DIR_STRAT, BOXED_STRAT)) {
            Files.deleteIfExists(statePath(s));
            Files.deleteIfExists(Paths.get("/tmp/paper-status-" + s + ".json"));
        }
    }

    /**
     * The regression: a strategy that has no direction (the venue just closed its position) used to
     * make the whole state write fail, silently, on every pass.
     */
    @Test
    void aStrategyWithNoTradeDirectionStillPersistsItsState() throws Exception {
        RecordingStrategy strategy = new RecordingStrategy(true); // flat: getTradeDirection() -> null
        LiveStrategyRunner r = runner(FLAT_STRAT, strategy);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "USD_CHF", "SELL", 0.8327, 6100, 0.8400, 0.8200, Instant.now()));

        assertDoesNotThrow(r::saveStateNow, "a null direction is a state, not an error");

        assertTrue(Files.exists(statePath(FLAT_STRAT)), "the state file must still be written");
        JsonNode saved = MAPPER.readTree(Files.readString(statePath(FLAT_STRAT)));
        assertTrue(saved.has("strat_tradeDirection"), "the direction field stays present");
        assertTrue(saved.get("strat_tradeDirection").isNull(), "flat is saved as an explicit null");
        assertEquals(1, saved.get("activeTrades").size(),
            "the write completed past the direction getter: the tracked trade is persisted");
        assertEquals(291, saved.get("strat_lastTradeDay").asInt(),
            "the export ran to its last getter instead of aborting");
    }

    /**
     * Every getter is read defensively, not just the direction one: a boxed null here used to abort the
     * whole write through the same unboxing NPE.
     */
    @Test
    void aGetterReturningABoxedNullDoesNotAbortTheWrite() throws Exception {
        LiveStrategyRunner r = runner(BOXED_STRAT, new BoxedNullStrategy());
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "USD_CHF", "BUY", 0.8327, 6100, 0.8400, 0.8200, Instant.now()));

        assertDoesNotThrow(r::saveStateNow, "a boxed null getter is not an error");

        JsonNode saved = MAPPER.readTree(Files.readString(statePath(BOXED_STRAT)));
        assertEquals(0, saved.get("strat_tradesToday").asInt(), "the fallback is used, not a crash");
        assertEquals(1, saved.get("activeTrades").size(),
            "and the rest of the state is still written");
        assertEquals("BUY", saved.get("strat_tradeDirection").asText());
    }

    /** A strategy the venue flattened is restored flat, never seeded with a side nobody chose. */
    @Test
    void restoreTreatsANullDirectionAsFlat() throws Exception {
        Files.writeString(statePath(FLAT_STRAT), """
            {
              "strategy" : "%s",
              "instrument" : "USD_CHF",
              "granularity" : "H1",
              "intervalSec" : 60,
              "pnlAccountingVersion" : %d,
              "inTrade" : false,
              "activeTrades" : [],
              "pendingStops" : [],
              "strat_tradesToday" : 1,
              "strat_lastTradeDay" : 290,
              "strat_inTrade" : true,
              "strat_tradeDirection" : null,
              "strat_cooldownBars" : 0,
              "savedAt" : "2026-10-12T09:22:53Z"
            }
            """.formatted(FLAT_STRAT, LiveStrategyRunner.PNL_ACCOUNTING_VERSION));

        RecordingStrategy strategy = new RecordingStrategy(true);
        runner(FLAT_STRAT, strategy).resumeState();

        assertTrue(strategy.restoreCalled, "the strategy state is restored, not skipped");
        assertNull(strategy.restoredDirection, "a null direction stays null, never BUY");
        assertEquals(Boolean.FALSE, strategy.restoredInTrade,
            "in-trade with no tracked trade comes back flat: the runner's book is authoritative");
        assertEquals(1, strategy.restoredTradesToday, "the counters still ride along");
    }

    /** A genuinely tracked trade keeps the strategy latched. */
    @Test
    void restoreKeepsInTradeWhenATradeIsTracked() throws Exception {
        Files.writeString(statePath(TRACKED_STRAT), """
            {
              "strategy" : "%s",
              "instrument" : "USD_CHF",
              "granularity" : "H1",
              "intervalSec" : 60,
              "pnlAccountingVersion" : %d,
              "inTrade" : true,
              "activeTrades" : [ { "tradeId" : "t1", "symbol" : "USD_CHF", "side" : "SELL",
                "entryPrice" : 0.8327, "quantity" : 6100, "stopLoss" : 0.84, "takeProfit" : 0.82,
                "entryTime" : "2026-10-12T08:00:00Z", "reconciliationStatus" : "CONFIRMED" } ],
              "pendingStops" : [],
              "strat_tradesToday" : 2,
              "strat_lastTradeDay" : 290,
              "strat_inTrade" : true,
              "strat_tradeDirection" : "SELL",
              "strat_cooldownBars" : 3,
              "savedAt" : "2026-10-12T08:30:00Z"
            }
            """.formatted(TRACKED_STRAT, LiveStrategyRunner.PNL_ACCOUNTING_VERSION));

        RecordingStrategy strategy = new RecordingStrategy(false);
        LiveStrategyRunner r = runner(TRACKED_STRAT, strategy);
        r.resumeState();

        assertEquals(1, r.getActiveTrades().size(), "the tracked trade is restored");
        assertEquals(Boolean.TRUE, strategy.restoredInTrade, "a tracked trade keeps the latch");
        assertEquals(Order.Side.SELL, strategy.restoredDirection);
        assertEquals(3, strategy.restoredCooldown);
    }

    /**
     * An unreadable side must not abort the whole resume: the same method also restores the tracked
     * trades, so a bad enum would cost the position, not just the direction.
     */
    @Test
    void restoreSurvivesAnUnknownDirection() throws Exception {
        Files.writeString(statePath(UNKNOWN_DIR_STRAT), """
            {
              "strategy" : "%s",
              "instrument" : "USD_CHF",
              "granularity" : "H1",
              "intervalSec" : 60,
              "pnlAccountingVersion" : %d,
              "inTrade" : false,
              "activeTrades" : [],
              "pendingStops" : [],
              "strat_tradesToday" : 0,
              "strat_lastTradeDay" : -1,
              "strat_inTrade" : false,
              "strat_tradeDirection" : "SIDEWAYS",
              "strat_cooldownBars" : 0,
              "savedAt" : "2026-10-12T09:22:53Z"
            }
            """.formatted(UNKNOWN_DIR_STRAT, LiveStrategyRunner.PNL_ACCOUNTING_VERSION));

        RecordingStrategy strategy = new RecordingStrategy(false);
        runner(UNKNOWN_DIR_STRAT, strategy).resumeState();

        assertTrue(strategy.restoreCalled, "an unknown side does not abort the restore");
        assertNull(strategy.restoredDirection, "an unreadable side is restored as flat");
    }
}
