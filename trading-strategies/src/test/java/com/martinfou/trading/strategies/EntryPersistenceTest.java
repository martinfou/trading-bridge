package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.OandaPriceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.6 — a confirmed fill is persisted immediately. The pre-fix code only saved on the
 * 60s periodic timer, so a container recreation in that window lost the entry (the exact window
 * trade 504 fell into on 2026-10-26). This test reads the state file right after the fill, with no
 * wait.
 */
class EntryPersistenceTest {

    private static final Strategy QUIET = new Strategy() {
        @Override public String name() { return "quiet"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    };

    /** A MARKET order that fills and opens a trade. */
    private static class FillingExecutor extends OandaExecutor {
        FillingExecutor() { super("k", "acc", true); }
        @Override
        public OrderResult placeMarketOrder(String instrument, String units, String tag, boolean reduceOnly,
                                            String stopLossOnFill, String takeProfitOnFill) {
            return new OrderResult("order-1", "trade-1", "0.5905", "FILLED", null);
        }
    }

    /** Hermetic price client: fixed balance and a fixed quote→home factor, no network. */
    private static class StubPriceClient extends OandaPriceClient {
        StubPriceClient() { super("k", "acc", true); }
        @Override public AccountSummary getAccountSummary() {
            return new AccountSummary("acc", 100_000, 100_000, 0);
        }
        @Override public double getQuoteToHomeLossFactor(String instrument) {
            return 0.0139;
        }
    }

    @Test
    void filledEntryIsPersistedImmediately(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        LiveStrategyRunner r = new LiveStrategyRunner("k", "acc", QUIET, "entrypersist", "M5", 60,
            stateDir, new FillingExecutor(), new StubPriceClient());

        Order entry = new Order("AUD_CHF", Order.Side.SELL, Order.Type.MARKET, 1000, 0)
            .withStopLoss(0.6055);
        r.executeTrade(entry, "AUD_CHF", 0.5905);

        Path stateFile = stateDir.resolve("live-strategy-state-entrypersist.json");
        assertTrue(Files.exists(stateFile), "the state file must exist immediately after the fill");
        JsonNode root = new ObjectMapper().readTree(stateFile.toFile());
        JsonNode activeTrades = root.get("activeTrades");
        assertEquals(1, activeTrades.size(),
            "the filled trade must be in the file without waiting for the periodic save");
        assertEquals("trade-1", activeTrades.get(0).get("tradeId").asText());
    }
}
