package com.martinfou.trading.strategies;

import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.3 — the saved instrument is validated before activeTrades are restored. A state
 * file that now outlives the container also outlives a STRATEGY_PAIR change: a position on the OLD
 * pair must never be restored and managed as if it were on the NEW pair.
 */
class ResumeStateInstrumentTest {

    private static final String STATE_TEMPLATE = """
        {
          "strategy" : "%s",
          "instrument" : "%s",
          "granularity" : "H1",
          "intervalSec" : 60,
          "totalEntries" : 4,
          "totalExits" : 2,
          "pnlAccountingVersion" : 3,
          "pnlLedger" : { "t9" : 5.0 },
          "pnlLedgerTrades" : 1,
          "ignoredLocalEstimates" : 0,
          "activeTrades" : [ {
            "tradeId" : "504",
            "symbol" : "USD_CHF",
            "side" : "SELL",
            "entryPrice" : 0.82689,
            "quantity" : 4600.0,
            "stopLoss" : 0.82832,
            "takeProfit" : 0.82482,
            "entryTime" : "2026-10-26T10:00:00Z",
            "reconciliationStatus" : "CONFIRMED"
          } ],
          "pendingStops" : [ ]
        }
        """;

    /** The runner resolves GBP_JPY from the strategy's name (no live-config in tests). */
    private LiveStrategyRunner runner(String shortName, Path stateDir) {
        return new LiveStrategyRunner("k", "acc",
            new ConsecutiveBarExhaustionStrategy("ConsecBarExhaustion GBP_JPY"),
            shortName, "H1", 60, stateDir);
    }

    @Test
    void differentInstrumentIsNotRestoredAcrossPairs(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("live-strategy-state-rsi3test.json"),
            STATE_TEMPLATE.formatted("rsi3test", "USD_CHF"));

        LiveStrategyRunner r = runner("rsi3test", stateDir);
        r.resumeState();

        assertEquals("stale-instrument", r.getStateSource());
        assertTrue(r.getActiveTrades().isEmpty(), "no cross-pair trade may be restored");
        assertEquals(4, r.getTotalEntries(), "counters are kept");
        assertEquals(2, r.getTotalExits(), "counters are kept");
        assertEquals(5.0, r.getTotalPnl(), 1e-9, "the realized-P&L ledger is kept");
    }

    @Test
    void absentSavedInstrumentIsTreatedAsStale(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("live-strategy-state-absenttest.json"), """
            {
              "strategy" : "absenttest",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 3,
              "totalExits" : 1,
              "pnlAccountingVersion" : 3,
              "pnlLedger" : { },
              "ignoredLocalEstimates" : 0,
              "activeTrades" : [ {
                "tradeId" : "600",
                "symbol" : "EUR_USD",
                "side" : "BUY",
                "entryPrice" : 1.0850,
                "quantity" : 1000.0,
                "stopLoss" : 1.0800,
                "takeProfit" : 1.0900,
                "entryTime" : "2026-10-26T10:00:00Z",
                "reconciliationStatus" : "CONFIRMED"
              } ],
              "pendingStops" : [ ]
            }
            """);

        LiveStrategyRunner r = runner("absenttest", stateDir);
        r.resumeState();

        assertEquals("stale-instrument", r.getStateSource(),
            "a file with no saved instrument cannot be trusted to restore cross-pair positions");
        assertTrue(r.getActiveTrades().isEmpty());
    }

    @Test
    void staleInstrumentAlsoDropsPendingStops(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("live-strategy-state-stoptest.json"), """
            {
              "strategy" : "stoptest",
              "instrument" : "USD_CHF",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 1,
              "totalExits" : 0,
              "pnlAccountingVersion" : 3,
              "pnlLedger" : { },
              "ignoredLocalEstimates" : 0,
              "activeTrades" : [ ],
              "pendingStops" : [ {
                "orderId" : "900",
                "symbol" : "USD_CHF",
                "side" : "SELL",
                "price" : 0.82900,
                "quantity" : 4600.0,
                "stopLoss" : 0.82832,
                "takeProfit" : 0.82482
              } ],
              "unreconciledTrades" : [ {
                "tradeId" : "504",
                "symbol" : "USD_CHF",
                "reason" : "broker timeout",
                "timestamp" : "2026-10-26T10:00:00Z"
              } ]
            }
            """);

        LiveStrategyRunner r = runner("stoptest", stateDir);
        r.resumeState();

        assertEquals("stale-instrument", r.getStateSource());
        assertTrue(r.getPendingStops().isEmpty(),
            "pending stops belong to the OLD pair: restoring them would rewrite ghost stops into the "
                + "new state file on the next save (review blocker 2026-10-26)");
        assertTrue(r.getUnreconciledTrades().isEmpty(),
            "unreconciled trades are position state too and must follow the same guard");
    }

    @Test
    void matchingInstrumentRestoresTradesAndSetsResumed(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("live-strategy-state-matchtest.json"), """
            {
              "strategy" : "matchtest",
              "instrument" : "GBP_JPY",
              "granularity" : "H1",
              "intervalSec" : 60,
              "totalEntries" : 1,
              "totalExits" : 0,
              "pnlAccountingVersion" : 3,
              "pnlLedger" : { },
              "ignoredLocalEstimates" : 0,
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

        LiveStrategyRunner r = runner("matchtest", stateDir);
        r.resumeState();

        assertEquals("resumed", r.getStateSource());
        assertEquals(1, r.getActiveTrades().size(), "a matching instrument must restore the trade");
        assertEquals("101", r.getActiveTrades().get(0).tradeId);
    }
}
