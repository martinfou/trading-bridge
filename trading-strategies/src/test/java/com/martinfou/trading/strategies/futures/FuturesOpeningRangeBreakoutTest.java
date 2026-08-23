package com.martinfou.trading.strategies.futures;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuturesOpeningRangeBreakoutTest {

    private FuturesOpeningRangeBreakout strategy;
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    @BeforeEach
    void setUp() {
        strategy = new FuturesOpeningRangeBreakout("FuturesOpeningRangeBreakout", "MES");
    }

    @Test
    @DisplayName("Strategy instantiates with correct metadata")
    void testMetadata() {
        assertEquals("FuturesOpeningRangeBreakout", strategy.name());
        assertTrue(strategy.getPendingOrders().isEmpty());
    }

    @Test
    @DisplayName("Establishes opening range at 09:00 NY and triggers Buy on upside breakout")
    void testLongBreakout() {
        ZonedDateTime baseTime = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, NY_ZONE);

        // Feed 220 bars of warm-up history at steady 5800.0 price
        for (int i = 0; i < 220; i++) {
            Instant t = baseTime.plusHours(i).toInstant();
            strategy.onBar(new Bar("MES", t, 5800.0, 5805.0, 5795.0, 5800.0, 1000));
        }

        // On next day at 09:00 NY: establish Opening Range [5800, 5820]
        ZonedDateTime orTime = ZonedDateTime.of(2025, 1, 18, 9, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", orTime.toInstant(), 5800.0, 5820.0, 5800.0, 5815.0, 5000));

        // No orders during OR bar
        assertTrue(strategy.getPendingOrders().isEmpty());

        // At 10:00 NY: breakout above OR High (5820) + stretch → enters BUY
        ZonedDateTime breakoutTime = ZonedDateTime.of(2025, 1, 18, 10, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", breakoutTime.toInstant(), 5815.0, 5845.0, 5815.0, 5840.0, 8000));

        List<Order> orders = strategy.getPendingOrders();
        assertFalse(orders.isEmpty(), "Should have generated entry order");
        Order entry = orders.get(0);
        assertEquals("MES", entry.symbol());
        assertEquals(Order.Side.BUY, entry.side());
        assertEquals(5840.0, entry.price());
        assertTrue(entry.stopLoss() < 5840.0, "Stop loss should be below entry price");
        assertTrue(entry.takeProfit() > 5840.0, "Take profit should be above entry price");
    }

    @Test
    @DisplayName("Respects max 1 trade per day limit")
    void testMaxOneTradePerDay() {
        ZonedDateTime baseTime = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, NY_ZONE);

        for (int i = 0; i < 220; i++) {
            Instant t = baseTime.plusHours(i).toInstant();
            strategy.onBar(new Bar("MES", t, 6000.0, 6005.0, 5995.0, 6000.0, 1000));
        }

        // OR established at 09:00 NY
        ZonedDateTime orTime = ZonedDateTime.of(2025, 3, 15, 9, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", orTime.toInstant(), 6000.0, 6020.0, 6000.0, 6010.0, 3000));

        // Entry 1 at 10:00 NY
        ZonedDateTime trade1Time = ZonedDateTime.of(2025, 3, 15, 10, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", trade1Time.toInstant(), 6010.0, 6035.0, 6010.0, 6030.0, 4000));
        assertEquals(1, strategy.getPendingOrders().size());

        // Hit TP at 11:00 NY to exit trade
        ZonedDateTime tpTime = ZonedDateTime.of(2025, 3, 15, 11, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", tpTime.toInstant(), 6030.0, 6100.0, 6030.0, 6090.0, 5000));

        // Try second entry on same day at 13:00 NY
        ZonedDateTime trade2Time = ZonedDateTime.of(2025, 3, 15, 13, 0, 0, 0, NY_ZONE);
        strategy.onBar(new Bar("MES", trade2Time.toInstant(), 6090.0, 6120.0, 6080.0, 6115.0, 6000));
        assertTrue(strategy.getPendingOrders().isEmpty(), "Must not take a second trade on the same day");
    }
}
