package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.OandaPriceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D66 — the order path treats a submitted-but-not-filled order as "no entry".
 *
 * <p>The pre-fix code returned the sentinel {@code "N/A"} for a cancelled MARKET order, incremented
 * {@code totalEntries} before checking the fill, then crashed on {@code Double.parseDouble("N/A")}.
 * The fix makes the absence of a fill explicit ({@code OrderResult.isFilled()}), counts the entry only
 * after a confirmed fill, and skips cleanly with the broker's cancel reason and real status.
 */
class LiveStrategyRunnerNoFillTest {

    private static final Strategy QUIET = new Strategy() {
        @Override public String name() { return "quiet"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    };

    /** A MARKET order that is created then cancelled (e.g. a market halt): no fill transaction. */
    private static class CancellingExecutor extends OandaExecutor {
        int submissions = 0;
        CancellingExecutor() { super("k", "acc", true); }
        @Override
        public OrderResult placeMarketOrder(String instrument, String units, String tag, boolean reduceOnly,
                                            String stopLossOnFill, String takeProfitOnFill) {
            submissions++;
            return new OrderResult("order-1", null, null, "CANCELLED", "MARKET_HALTED");
        }
    }

    /** A MARKET order that fills normally. */
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
            return 0.0139; // CHF → CAD (account currency), a plausible paper value
        }
    }

    private static LiveStrategyRunner runner(OandaExecutor executor) {
        return new LiveStrategyRunner("k", "acc", QUIET, "consecbar", "M5", 60,
            executor, new StubPriceClient());
    }

    @Test
    @DisplayName("a cancelled no-fill entry does not count, does not throw, and leaves the strategy flat")
    void cancelledNoFillEntryIsSkippedCleanly() {
        CancellingExecutor executor = new CancellingExecutor();
        LiveStrategyRunner r = runner(executor);

        // A SELL entry with the 150-pip backstop, exactly as FridayFadeAudChf would emit it.
        Order entry = new Order("AUD_CHF", Order.Side.SELL, Order.Type.MARKET, 1000, 0)
            .withStopLoss(0.6055);

        assertDoesNotThrow(() -> r.executeTrade(entry, "AUD_CHF", 0.5905),
            "a no-fill order must not surface as an exception");

        assertEquals(1, executor.submissions, "the order was submitted exactly once");
        assertEquals(0, r.getTotalEntries(), "no entry is counted without a confirmed fill");
        assertTrue(r.getActiveTrades().isEmpty(), "no phantom position is tracked");
    }

    @Test
    @DisplayName("a filled entry is counted and tracked exactly once")
    void filledEntryIsCountedAndTracked() {
        LiveStrategyRunner r = runner(new FillingExecutor());

        Order entry = new Order("AUD_CHF", Order.Side.SELL, Order.Type.MARKET, 1000, 0)
            .withStopLoss(0.6055);

        assertDoesNotThrow(() -> r.executeTrade(entry, "AUD_CHF", 0.5905));

        assertEquals(1, r.getTotalEntries(), "a confirmed fill counts exactly one entry");
        assertEquals(1, r.getActiveTrades().size(), "the filled trade is tracked");
        assertEquals("trade-1", r.getActiveTrades().get(0).tradeId);
    }
}
