package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.OandaPriceClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.7 — orphan detection is ALERT ONLY. A broker trade tagged with THIS strategy and
 * absent from {@code activeTrades} is an orphan (surfaced + logged ERROR). A trade tagged for
 * ANOTHER strategy is ignored entirely (no cross false-positives: compmomentum and monthweekphase
 * both trade USD_JPY on the same account). An untagged trade is counted without attribution. No
 * entry is ever blocked here.
 */
class OrphanDetectionTest {

    private static final Strategy QUIET = new Strategy() {
        @Override public String name() { return "quiet"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    };

    private static class StubExecutor extends OandaExecutor {
        List<OandaExecutor.TradeView> open = List.of();
        StubExecutor() { super("k", "acc", true); }
        @Override
        public List<OandaExecutor.TradeView> getOpenTradesDetailed() {
            return open;
        }
    }

    private static class StubPriceClient extends OandaPriceClient {
        StubPriceClient() { super("k", "acc", true); }
        @Override public AccountSummary getAccountSummary() {
            return new AccountSummary("acc", 100_000, 100_000, 0);
        }
        @Override public double getQuoteToHomeLossFactor(String instrument) {
            return 0.0139;
        }
    }

    private LiveStrategyRunner runner(StubExecutor executor) {
        return new LiveStrategyRunner("k", "acc", QUIET, "compmomentum", "H1", 60,
            executor, new StubPriceClient());
    }

    @Test
    void ownTaggedTradeAbsentLocallyIsOrphan() {
        StubExecutor executor = new StubExecutor();
        executor.open = List.of(
            new OandaExecutor.TradeView("504", "USD_CHF", "-4600", "compmomentum_USDCHF"));

        LiveStrategyRunner r = runner(executor);
        r.detectOrphans();

        List<OandaExecutor.TradeView> untracked = r.getUntrackedBrokerTrades();
        assertEquals(1, untracked.size(), "a broker trade tagged for us but absent locally is an orphan");
        assertEquals("504", untracked.get(0).id());
        assertEquals("compmomentum_USDCHF", untracked.get(0).tag());
    }

    @Test
    void tradeTaggedForAnotherStrategyIsIgnored() {
        StubExecutor executor = new StubExecutor();
        executor.open = List.of(
            new OandaExecutor.TradeView("505", "USD_JPY", "-1000", "monthweekphase_USDJPY"));

        LiveStrategyRunner r = runner(executor); // compmomentum, not monthweekphase
        r.detectOrphans();

        assertTrue(r.getUntrackedBrokerTrades().isEmpty(),
            "a trade tagged for another strategy must not be attributed to this runner");
    }

    @Test
    void untaggedTradeIsCountedWithoutAttribution() {
        StubExecutor executor = new StubExecutor();
        executor.open = List.of(
            new OandaExecutor.TradeView("506", "USD_JPY", "-1000", null));

        LiveStrategyRunner r = runner(executor);
        r.detectOrphans();

        List<OandaExecutor.TradeView> untracked = r.getUntrackedBrokerTrades();
        assertEquals(1, untracked.size(), "an untagged trade is counted, never classified as an orphan");
        assertNull(untracked.get(0).tag(), "an untagged trade carries no attribution");
    }

    @Test
    void untaggedTradeOnOurInstrumentIsSurfacedNotSilent() throws Exception {
        StubExecutor executor = new StubExecutor();
        executor.open = List.of(
            new OandaExecutor.TradeView("504", "USD_CHF", "-4600", null));

        LiveStrategyRunner r = runner(executor);
        // A live run resolves the instrument before the loop; mirror that here so the untagged
        // branch can decide whether the position sits on OUR pair (the 2026-10-26 shape: trade 504
        // was opened before trade-level tags existed, so it arrives with tag = null).
        var field = LiveStrategyRunner.class.getDeclaredField("resolvedInstrument");
        field.setAccessible(true);
        field.set(r, "USD_CHF");

        r.detectOrphans();

        List<OandaExecutor.TradeView> untracked = r.getUntrackedBrokerTrades();
        assertEquals(1, untracked.size(), "the historical orphan is surfaced, not dropped");
        assertEquals("504", untracked.get(0).id());
        assertNull(untracked.get(0).tag(), "still unattributed: no tag means no strategy claim");
    }

    @Test
    void trackedTradeIsNotAnOrphan() {
        StubExecutor executor = new StubExecutor();
        executor.open = List.of(
            new OandaExecutor.TradeView("507", "USD_CHF", "-4600", "compmomentum_USDCHF"));

        LiveStrategyRunner r = runner(executor);
        r.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "507", "USD_CHF", "SELL", 0.82689, 4600, 0.82832, 0.82482, Instant.now()));
        r.detectOrphans();

        assertTrue(r.getUntrackedBrokerTrades().isEmpty(),
            "a trade present in activeTrades is tracked, not an orphan");
    }
}
