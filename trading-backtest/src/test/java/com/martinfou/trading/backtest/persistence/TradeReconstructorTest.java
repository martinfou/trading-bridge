package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeReconstructorTest {

    @Test
    void testReconstructSimpleTrade() {
        Instant t1 = Instant.parse("2026-06-27T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-27T11:00:00Z");

        RunEvent fill1 = new RunEvent(1, RunEventType.FILL, t1, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.1000, "orderId", "o1"));
        RunEvent fill2 = new RunEvent(1, RunEventType.FILL, t2, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "SELL", "quantity", 1000.0, "price", 1.1050, "orderId", "o2"));

        List<Trade> trades = TradeReconstructor.reconstruct(List.of(fill1, fill2));
        assertEquals(1, trades.size());

        Trade trade = trades.get(0);
        assertEquals("EUR_USD", trade.symbol());
        assertEquals(Order.Side.BUY, trade.side());
        assertEquals(1.1000, trade.entryPrice(), 0.00001);
        assertEquals(1.1050, trade.exitPrice(), 0.00001);
        assertEquals(1000.0, trade.quantity(), 0.00001);
        assertEquals(t1, trade.entryTime());
        assertEquals(t2, trade.exitTime());
        assertEquals(5.0, trade.pnl(), 0.0001); // 0.0050 * 1000
    }

    @Test
    void testPartialFillMerging() {
        Instant t1 = Instant.parse("2026-06-27T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-27T10:00:05Z");
        Instant t3 = Instant.parse("2026-06-27T11:00:00Z");

        // Partial entry: 300 @ 1.1000 and 700 @ 1.1010 sharing the same orderId "o1"
        RunEvent fill1 = new RunEvent(1, RunEventType.FILL, t1, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 300.0, "price", 1.1000, "orderId", "o1"));
        RunEvent fill2 = new RunEvent(1, RunEventType.FILL, t2, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 700.0, "price", 1.1010, "orderId", "o1"));

        // Exit: 1000 @ 1.1050
        RunEvent fill3 = new RunEvent(1, RunEventType.FILL, t3, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "SELL", "quantity", 1000.0, "price", 1.1050, "orderId", "o2"));

        List<Trade> trades = TradeReconstructor.reconstruct(List.of(fill1, fill2, fill3));
        assertEquals(1, trades.size());

        Trade trade = trades.get(0);
        // Weighted average entry price should be (300*1.1000 + 700*1.1010)/1000 = 1.1007
        assertEquals(1.1007, trade.entryPrice(), 0.00001);
        assertEquals(1.1050, trade.exitPrice(), 0.00001);
        assertEquals(1000.0, trade.quantity(), 0.00001);
        assertEquals(t1, trade.entryTime()); // Matches the first fill's timestamp
        assertEquals(t3, trade.exitTime());
        assertEquals(4.3, trade.pnl(), 0.0001); // (1.1050 - 1.1007) * 1000 = 4.3 USD
    }

    @Test
    void testFinancingFiltering() {
        Instant t1 = Instant.parse("2026-06-27T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-27T11:00:00Z");

        RunEvent fill1 = new RunEvent(1, RunEventType.FILL, t1, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.1000, "orderId", "o1"));
        
        // Financing event mapped to FILL in streaming executor
        RunEvent financing = new RunEvent(1, RunEventType.FILL, t1.plusSeconds(30), "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("type", "FINANCING", "quantity", -0.15, "message", "Daily Financing"));

        RunEvent fill2 = new RunEvent(1, RunEventType.FILL, t2, "run_1", "strat_1", "EUR_USD", "PAPER",
            Map.of("symbol", "EUR_USD", "side", "SELL", "quantity", 1000.0, "price", 1.1050, "orderId", "o2"));

        List<Trade> trades = TradeReconstructor.reconstruct(List.of(fill1, financing, fill2));
        assertEquals(1, trades.size()); // Financing is filtered out
    }
}
