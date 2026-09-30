package com.martinfou.trading.strategies;

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
 * Regression tests for the realized-P&L counter (bugfix 2026-09-29).
 *
 * <p><b>Defect.</b> The strategy-signal exit path added a local estimate computed in the
 * instrument's <em>quote</em> currency (JPY for GBP_JPY, USD for EUR_USD) to the same accumulator
 * that later receives the broker's <em>account-currency</em> realizedPL. On GBP_JPY that overstated
 * the counter by ~108x (0.35 × 1000 units = "350.50" instead of ≈1.09 CAD), the trade was dropped
 * from tracking before any reconciliation could correct it, and the value was double-counted
 * whenever the async reconciliation completed the same trade.
 *
 * <p><b>Rule asserted here.</b> {@code totalPnl} is account currency, broker-sourced, added
 * exactly once per trade — and a pre-fix accumulator is discarded, never carried over.
 */
class LiveStrategyRunnerPnlAccountingTest {

    private static final String LEGACY_STRAT = "PnlAccountingLegacy";
    private static final String CURRENT_STRAT = "PnlAccountingCurrent";
    private static final String EDGE_STRAT = "PnlAccountingEdge";

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
        for (String s : List.of(LEGACY_STRAT, CURRENT_STRAT, EDGE_STRAT)) {
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

    /** A counter written by the corrected accounting survives a restart. */
    @Test
    void currentVersionPnlIsKeptOnResume() throws Exception {
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
              "totalPnl" : 12.34,
              "savedAt" : "2026-09-29T20:00:00Z"
            }
            """.formatted(CURRENT_STRAT, LiveStrategyRunner.PNL_ACCOUNTING_VERSION));

        LiveStrategyRunner r = runner(CURRENT_STRAT);
        r.resumeState();

        assertEquals(12.34, r.getTotalPnl(), 1e-9);
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
}
