package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Regression tests for the realized-P&L counter (bugfix 2026-09-29).
 *
 * <p><b>Defect.</b> The strategy-signal exit path added a local estimate computed in the
 * instrument's <em>quote</em> currency (JPY for GBP_JPY, USD for EUR_USD) to the same accumulator
 * that later receives the broker's <em>account-currency</em> realizedPL. On GBP_JPY that overstated
 * the counter by ~108x (0.35 × 1000 units = "350.50" instead of ≈1.09 CAD), the trade was dropped
 * from tracking before any reconciliation could correct it, and the value was double-counted
 * whenever the async reconciliation completed the same trade.
 *
 * <p><b>Rule asserted here.</b> The realized-P&L ledger is account currency, broker-sourced, added
 * exactly once per trade id — and a pre-v3 accumulator is discarded, never carried over.
 */
class LiveStrategyRunnerPnlAccountingTest {

    private static final String LEGACY_STRAT = "PnlAccountingLegacy";
    private static final String CURRENT_STRAT = "PnlAccountingCurrent";
    private static final String EDGE_STRAT = "PnlAccountingEdge";
    private static final String DUP_STRAT = "PnlAccountingDuplicate";
    private static final String ROUNDTRIP_STRAT = "PnlAccountingRoundtrip";
    private static final String V2_STRAT = "PnlAccountingV2";
    private static final String FALLBACK_STRAT = "PnlAccountingFallback";
    private static final String BOUNDED_STRAT = "PnlAccountingBounded";
    private static final String M2_STRAT = "PnlAccountingNoRealizedPl";
    private static final String M4_STRAT = "PnlAccountingExitOnce";
    private static final String UNREC_STRAT = "PnlAccountingUnreconciled";
    private static final String OPEN_STRAT = "PnlAccountingOpenRevert";
    private static final String STOPS_STRAT = "PnlAccountingStops";

    private static class NoopStrategy implements Strategy {
        @Override public String name() { return "Noop"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
        @Override public void reset() { }
    }

    private LiveStrategyRunner runner(String shortName) {
        return new LiveStrategyRunner("dummy-key", "dummy-acc", new NoopStrategy(), shortName, "H1", 60);
    }

    private static Path statePath(String shortName) {
        return Paths.get("/tmp/live-strategy-state-" + shortName + ".json");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String s : List.of(LEGACY_STRAT, CURRENT_STRAT, EDGE_STRAT,
                DUP_STRAT, ROUNDTRIP_STRAT, V2_STRAT, FALLBACK_STRAT,
                BOUNDED_STRAT, M2_STRAT, M4_STRAT, UNREC_STRAT, OPEN_STRAT, STOPS_STRAT)) {
            Files.deleteIfExists(statePath(s));
            Files.deleteIfExists(Paths.get("/tmp/paper-status-" + s + ".json"));
        }
    }

    /** A saved counter written before the fix (no pnlAccountingVersion) must not be carried over. */
    @Test
    void legacyQuoteCurrencyPnlIsDiscardedOnResume() throws Exception {
        Files.writeString(statePath(LEGACY_STRAT), """
            {
              "strategy" : "%s",
              "displayName" : "Noop",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 6,
              "totalExits" : 6,
              "totalPnl" : 350.50,
              "savedAt" : "2026-09-29T20:00:00Z"
            }
            """.formatted(LEGACY_STRAT));

        LiveStrategyRunner r = runner(LEGACY_STRAT);
        r.resumeState();

        assertEquals(0.0, r.getTotalPnl(), 1e-9,
            "a legacy totalPnl (quote-currency contaminated) must be discarded, not carried over");
    }

    /** A ledger written by the corrected (v3) accounting survives a restart. */
    @Test
    void currentVersionLedgerIsKeptOnResume() throws Exception {
        Files.writeString(statePath(CURRENT_STRAT), """
            {
              "strategy" : "%s",
              "displayName" : "Noop",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 6,
              "totalExits" : 6,
              "pnlAccountingVersion" : %d,
              "pnlLedger" : { "t9" : 12.34 },
              "pnlLedgerTrades" : 1,
              "ignoredLocalEstimates" : 0,
              "savedAt" : "2026-09-29T20:00:00Z"
            }
            """.formatted(CURRENT_STRAT, LiveStrategyRunner.PNL_ACCOUNTING_VERSION));

        LiveStrategyRunner r = runner(CURRENT_STRAT);
        r.resumeState();

        assertEquals(12.34, r.getTotalPnl(), 1e-9);
        assertEquals(1, r.getPnlLedgerTrades(), "the trade-id-keyed ledger is restored, not a scalar");
    }

    /**
     * A signal-driven exit must add nothing locally: the trade stays tracked, flagged for
     * reconciliation, and only the broker value (account currency) reaches the counter — once.
     */
    @Test
    void signalExitDefersPnlToBrokerRealizedPnl() {
        LiveStrategyRunner r = runner(CURRENT_STRAT);
        var trade = new LiveStrategyRunner.ActiveTrade(
            "t1", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now());
        r.getActiveTrades().add(trade);

        List<String> registered = r.markClosableTradesForReconciliation("GBP_JPY");

        assertEquals(List.of("t1"), registered);
        assertEquals("UNCONFIRMED_RECONCILIATION", trade.reconciliationStatus);
        assertEquals(1, r.getActiveTrades().size(), "trade stays tracked until the broker answers");
        assertEquals(0.0, r.getTotalPnl(), 1e-9,
            "no local quote-currency estimate may enter the realized-P&L counter");
        assertEquals(0, r.getTotalExits(), "the exit is counted when it is reconciled, not before");

        // OANDA realizedPL for the same trade: account currency (CAD).
        r.completeReconciliation("t1", 1.0934);

        assertEquals(1.0934, r.getTotalPnl(), 1e-9,
            "broker value counted exactly once, in account currency");
        assertEquals(1, r.getTotalExits());
        assertTrue(r.getActiveTrades().isEmpty());
    }

    /** A trade already awaiting reconciliation is not registered (and not closed) a second time. */
    @Test
    void alreadyClosingTradeIsNotRegisteredTwice() {
        LiveStrategyRunner r = runner(CURRENT_STRAT);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t2", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));

        assertEquals(List.of("t2"), r.markClosableTradesForReconciliation("GBP_JPY"));
        assertTrue(r.markClosableTradesForReconciliation("GBP_JPY").isEmpty(),
            "a trade pending reconciliation must not be flagged again");
        assertEquals(1, r.getActiveTrades().size());
    }

    /** A duplicate reconciliation for the same trade id leaves the total at the FIRST value only. */
    @Test
    void duplicateBrokerPnlIsCountedOnce() {
        LiveStrategyRunner r = runner(DUP_STRAT);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t1", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));

        r.completeReconciliation("t1", 1.0934);
        r.completeReconciliation("t1", 99.99); // overlapping async reconciliation, different value

        assertEquals(1.0934, r.getTotalPnl(), 1e-9,
            "the first broker value wins; the duplicate must neither double-count nor overwrite");
        assertEquals(1, r.getPnlLedgerTrades(), "one entry per trade id");
        assertEquals(1, r.getTotalExits(), "the exit is counted only once");
    }

    /** Save → resume restores the same ledger total and trade count. */
    @Test
    void stateRoundTripRestoresLedger() throws Exception {
        LiveStrategyRunner r1 = runner(ROUNDTRIP_STRAT);
        r1.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t9", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));
        r1.completeReconciliation("t9", 1.0934); // records + persists via saveStateNow()

        LiveStrategyRunner r2 = runner(ROUNDTRIP_STRAT);
        r2.resumeState();

        assertEquals(1.0934, r2.getTotalPnl(), 1e-9,
            "ledger total survives a save/resume round-trip");
        assertEquals(1, r2.getPnlLedgerTrades(),
            "ledger trade count survives a save/resume round-trip");
    }

    /** A v2 state file (scalar totalPnl, pnlAccountingVersion: 2) is discarded, not carried over. */
    @Test
    void v2ScalarPnlIsDiscardedOnResume() throws Exception {
        Files.writeString(statePath(V2_STRAT), """
            {
              "strategy" : "%s",
              "displayName" : "Noop",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 6,
              "totalExits" : 6,
              "pnlAccountingVersion" : 2,
              "totalPnl" : 350.50,
              "savedAt" : "2026-09-29T20:00:00Z"
            }
            """.formatted(V2_STRAT));

        LiveStrategyRunner r = runner(V2_STRAT);
        r.resumeState();

        assertEquals(0.0, r.getTotalPnl(), 1e-9,
            "a v2 scalar totalPnl must be discarded, not carried into the v3 ledger");
        assertEquals(0, r.getPnlLedgerTrades());
    }

    /** The fallback never moves the total: the local value is quote currency and stays UNKNOWN. */
    @Test
    void fallbackReconciliationLeavesTotalAtZero() {
        LiveStrategyRunner r = runner(FALLBACK_STRAT);
        var trade = new LiveStrategyRunner.ActiveTrade(
            "t5", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now());
        trade.unrealizedPnl = 350.50; // local quote-currency estimate (JPY)
        r.getActiveTrades().add(trade);

        r.reconcileTradeFallback("t5");

        assertEquals(0.0, r.getTotalPnl(), 1e-9,
            "the local quote-currency value must never enter the realized-P&L total");
        assertEquals(1, r.getPnlIgnoredLocalEstimates(), "the ignored local estimate is counted");
        assertEquals(1, r.getTotalExits(), "the exit is still counted");
        assertTrue(r.getActiveTrades().isEmpty(), "the trade is removed so entries are not blocked");
    }

    // ========================================================================
    // Bounded ledger (M5) + new findings (N5, N7, N8)
    // ========================================================================

    /** Recording more than RECENT_WINDOW values keeps the exact total and caps the window. */
    @Test
    void ledgerRollsUpToBoundedWindowKeepingExactTotal() {
        RealizedPnlLedger ledger = new RealizedPnlLedger();
        int n = RealizedPnlLedger.RECENT_WINDOW + 25;
        double expected = 0.0;
        for (int i = 1; i <= n; i++) {
            assertTrue(ledger.recordBrokerPnl("t" + i, (double) i));
            expected += i;
        }
        assertEquals(expected, ledger.total(), 1e-9,
            "total must be the exact sum of ALL recorded values after rollup");
        assertEquals(RealizedPnlLedger.RECENT_WINDOW, ledger.recentSize(),
            "recent window is capped at RECENT_WINDOW");
        assertEquals(n, ledger.trades(), "dedupe set tracks every distinct id");
        assertFalse(ledger.snapshot().containsKey("t1"), "oldest id rolled out of the window");
        double rolled = n - RealizedPnlLedger.RECENT_WINDOW;
        assertEquals(rolled * (rolled + 1) / 2.0, ledger.confirmedTotal(), 1e-9,
            "confirmedTotal holds the exact sum of entries that left the window");
    }

    /** Dedupe survives rollup: re-recording a rolled-up id (within the id cap) is still rejected. */
    @Test
    void dedupeSurvivesRollup() {
        RealizedPnlLedger ledger = new RealizedPnlLedger();
        int n = RealizedPnlLedger.RECENT_WINDOW + 10;
        for (int i = 1; i <= n; i++) {
            assertTrue(ledger.recordBrokerPnl("t" + i, 1.0));
        }
        double before = ledger.total();
        assertFalse(ledger.recordBrokerPnl("t" + n, 999.0),
            "an id still inside the recent window must be rejected");
        assertFalse(ledger.recordBrokerPnl("t1", 999.0),
            "an id evicted to the rolled-up region (still within the id cap) must be rejected");
        assertEquals(before, ledger.total(), 1e-9, "re-recording must not change the total");
        assertEquals(n, ledger.trades(), "dedupe set size unchanged");
    }

    /** Save → resume restores the exact total, the recent window, and the recorded-id dedupe. */
    @Test
    void boundedLedgerSurvivesStateRoundTrip() {
        LiveStrategyRunner r1 = runner(BOUNDED_STRAT);
        int n = RealizedPnlLedger.RECENT_WINDOW + 20;
        double expected = 0.0;
        for (int i = 1; i <= n; i++) {
            r1.getPnlLedger().recordBrokerPnl("t" + i, (double) i);
            expected += i;
        }
        r1.saveStateNow();

        LiveStrategyRunner r2 = runner(BOUNDED_STRAT);
        r2.resumeState();

        assertEquals(expected, r2.getTotalPnl(), 1e-9,
            "exact total (confirmed + recent) survives round-trip");
        assertEquals(RealizedPnlLedger.RECENT_WINDOW, r2.getPnlLedgerRecentSize(),
            "recent window size survives round-trip");
        assertEquals(n, r2.getPnlLedgerTrades(), "recorded-id dedupe count survives round-trip");
        assertFalse(r2.getPnlLedger().recordBrokerPnl("t1", 999.0),
            "an id evicted to the rolled-up region is still deduped after resume");
        assertEquals(expected, r2.getTotalPnl(), 1e-9);
    }

    /** A signal exit is counted once at reconciliation, not at registration (M4). */
    @Test
    void signalExitIsCountedOnceAtReconciliationNotAtRegistration() {
        LiveStrategyRunner r = runner(M4_STRAT);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "a1", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now()));
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "a2", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now()));

        List<String> registered = r.markClosableTradesForReconciliation("GBP_JPY");

        assertEquals(2, registered.size(), "both trades are registered for reconciliation");
        assertEquals(0, r.getTotalExits(), "registration must NOT count the exit");

        r.completeReconciliation("a1", 1.50);
        r.completeReconciliation("a2", -0.50);

        assertEquals(2, r.getTotalExits(), "each trade's exit is counted exactly once, at reconciliation");
        assertEquals(1.00, r.getTotalPnl(), 1e-9, "broker P&L summed exactly once per trade");
    }

    /** A CLOSED trade with no realizedPL is postponed, never recorded as 0 (M2). */
    @Test
    void closedTradeWithoutRealizedPlIsNotRecordedAsZero() {
        LiveStrategyRunner r = runner(M2_STRAT);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t7", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now()));

        ObjectNode tNode = new ObjectMapper().createObjectNode();
        tNode.put("state", "CLOSED");
        // deliberately no "realizedPL" field

        r.reconcileClosedTrade("t7", tNode);

        assertEquals(0.0, r.getTotalPnl(), 1e-9,
            "a CLOSED trade without realizedPL must not be recorded as 0");
        assertEquals(0, r.getPnlLedgerTrades());
        // CORRECTED after the independent review of 2026-09-30: the first version of this test
        // asserted the trade "stays pending" in activeTrades. That is the bug, not the rule — a
        // trade the broker reports CLOSED must be evicted (otherwise the 60s sweep finds it missing
        // from the broker's open trades and re-flags it UNCONFIRMED forever, and later opposite
        // signals are misread as closes). The EXIT happened; only its P&L is unknown.
        assertTrue(r.getActiveTrades().isEmpty(),
            "a broker-CLOSED trade must be evicted from tracking, not left pending");
        assertEquals(1, r.getTotalExits(), "the exit happened even if the P&L is unknown");
        assertTrue(r.getUnreconciledTrades().stream().anyMatch(u -> "t7".equals(u.tradeId)),
            "the trade must be parked so the watchdog re-attempts the broker value");
    }

    /** The fallback records the unreconciled trade so it is observable and re-attemptable (M3). */
    @Test
    void fallbackPersistsUnreconciledTradeAndCount() {
        LiveStrategyRunner r = runner(UNREC_STRAT);
        var trade = new LiveStrategyRunner.ActiveTrade(
            "t8", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now());
        trade.unrealizedPnl = 350.50;
        r.getActiveTrades().add(trade);

        r.reconcileTradeFallback("t8");

        assertEquals(1, r.getUnreconciledTrades().size(), "the fallback trade is recorded as unreconciled");
        assertEquals("t8", r.getUnreconciledTrades().get(0).tradeId);
        assertEquals("GBP_JPY", r.getUnreconciledTrades().get(0).symbol);

        LiveStrategyRunner r2 = runner(UNREC_STRAT);
        r2.resumeState();
        assertEquals(1, r2.getUnreconciledTrades().size(), "unreconciled trades survive save/resume");
        assertEquals("t8", r2.getUnreconciledTrades().get(0).tradeId);
    }

    /** A still-open trade reverts to CONFIRMED so it is tracked, never a zombie UNCONFIRMED (N5). */
    @Test
    void openTradeReconciliationRevertsToConfirmedNotZombie() {
        LiveStrategyRunner r = runner(OPEN_STRAT);
        var trade = new LiveStrategyRunner.ActiveTrade(
            "t6", "GBP_JPY", "BUY", 208.000, 1000, 0, 0, Instant.now());
        trade.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
        r.getActiveTrades().add(trade);

        r.reconcileOpenTrade("t6");

        assertEquals("CONFIRMED", trade.reconciliationStatus,
            "a still-open trade must revert to CONFIRMED so the position keeps being tracked");
        assertFalse(r.hasUnconfirmedReconciliation(),
            "no trade may stay UNCONFIRMED_RECONCILIATION forever while still open at the broker");
    }

    /** NaN / Infinity broker values must never enter the total (N7). */
    @Test
    void nonFiniteBrokerValueIsRejected() {
        RealizedPnlLedger ledger = new RealizedPnlLedger();
        assertFalse(ledger.recordBrokerPnl("bad1", Double.NaN), "NaN must not enter the total");
        assertFalse(ledger.recordBrokerPnl("bad2", Double.POSITIVE_INFINITY), "Infinity must not enter the total");
        assertFalse(ledger.recordBrokerPnl("bad3", Double.NEGATIVE_INFINITY), "-Infinity must not enter the total");
        assertEquals(0.0, ledger.total(), 1e-9, "total stays clean");
        assertEquals(0, ledger.trades());
    }

    /** Pending stops serialize correctly through the synchronized snapshot path (N8). */
    @Test
    void pendingStopsSurviveStateRoundTrip() {
        LiveStrategyRunner r1 = runner(STOPS_STRAT);
        r1.getPendingStops().add(new LiveStrategyRunner.PendingStop(
            "o1", "GBP_JPY", "SELL", 207.5, 1000, 208.5, 206.0));
        r1.saveStateNow();

        LiveStrategyRunner r2 = runner(STOPS_STRAT);
        r2.resumeState();

        assertEquals(1, r2.getPendingStops().size(), "pending stop survives save/resume");
        assertEquals("o1", r2.getPendingStops().get(0).orderId);
    }

    /**
     * A trade the broker reports CLOSED with no usable realizedPL carries an UNKNOWN P&L. It must
     * not be recorded as 0, it must be EVICTED from tracking (keeping it would let the 60s sweep
     * re-flag it UNCONFIRMED forever and make later opposite signals look like closes), and it must
     * be parked for a broker re-attempt. Asserting the eviction is the point: an earlier version of
     * this test only asserted a status change and passed while the re-block loop was live.
     */
    @Test
    void closedTradeWithoutUsableRealizedPlIsEvictedAndParked() {
        LiveStrategyRunner r = runner(CURRENT_STRAT);
        var trade = new LiveStrategyRunner.ActiveTrade(
            "t9", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now());
        trade.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
        r.getActiveTrades().add(trade);
        assertTrue(r.hasUnconfirmedReconciliation(), "precondition: entries are blocked");

        var closedWithoutPnl = new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode().put("state", "CLOSED");
        r.reconcileClosedTrade("t9", closedWithoutPnl);

        assertEquals(0.0, r.getTotalPnl(), 1e-9, "an unknown P&L is not a zero P&L");
        assertFalse(r.hasUnconfirmedReconciliation(), "the strategy must not stay blocked");
        assertTrue(r.getActiveTrades().isEmpty(),
            "a broker-CLOSED trade must be evicted from tracking");
        assertTrue(r.getUnreconciledTrades().stream().anyMatch(u -> "t9".equals(u.tradeId)),
            "the trade must be parked for a broker re-attempt");
    }

    /** An explicit JSON null must read as "no usable P&L", not as 0.0 (has() is true for null). */
    @Test
    void jsonNullRealizedPlIsNotRecordedAsZero() {
        LiveStrategyRunner r = runner(CURRENT_STRAT);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "t10", "GBP_JPY", "SELL", 208.000, 1000, 0, 0, Instant.now()));

        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("state", "CLOSED");
        node.putNull("realizedPL");
        r.reconcileClosedTrade("t10", node);

        assertEquals(0.0, r.getTotalPnl(), 1e-9, "a JSON null P&L is unknown, not zero");
        assertTrue(r.getActiveTrades().isEmpty(), "the closed trade must not stay tracked");
        assertTrue(r.getUnreconciledTrades().stream().anyMatch(u -> "t10".equals(u.tradeId)),
            "the trade must be parked rather than written off at zero");
    }
}
