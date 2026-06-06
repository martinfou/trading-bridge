package com.martinfou.trading.strategies.llmweekly;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoTradeWeekStrategyTest {

    @Test
    void onBarNeverEmitsOrders() {
        NoTradeWeekStrategy strategy = new NoTradeWeekStrategy("LLM_WEEKLY_T8", "contradictions");
        Bar bar = new Bar("EUR_USD", Instant.parse("2026-06-09T08:00:00Z"), 1.1, 1.2, 1.0, 1.15, 1000);
        strategy.onBar(bar);
        List<Order> orders = strategy.getPendingOrders();
        assertTrue(orders.isEmpty());
    }
}
