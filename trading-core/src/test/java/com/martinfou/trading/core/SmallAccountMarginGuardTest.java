package com.martinfou.trading.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SmallAccountMarginGuard Unit Tests (Story 46.5)")
class SmallAccountMarginGuardTest {

    @Test
    @DisplayName("Should approve single 1-micro order within 50% margin ceiling")
    void testApproveSingleMicroOrder() {
        SmallAccountMarginGuard guard = new SmallAccountMarginGuard(0.50, 2, 5000.0);
        Order order = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0);

        SmallAccountMarginGuard.ValidationResult result = guard.validateOrder(order, 5000.0, List.of());

        assertTrue(result.allowed());
        assertEquals(1.0, result.approvedQuantity());
        assertEquals(1200.0, result.requiredMarginUsd());
        assertEquals(0.24, result.marginUtilizationPct(), 0.01);
    }

    @Test
    @DisplayName("Should reject order when 2 concurrent positions are already open")
    void testRejectWhenConcurrencyLimitReached() {
        SmallAccountMarginGuard guard = new SmallAccountMarginGuard(0.50, 2, 5000.0);
        Order order = new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2500.0);

        List<Position> active = List.of(
            new Position("MES", Order.Side.BUY, 1.0, 5000.0),
            new Position("MNQ", Order.Side.BUY, 1.0, 18000.0)
        );

        SmallAccountMarginGuard.ValidationResult result = guard.validateOrder(order, 10000.0, active);

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("CONCURRENT_POSITION_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("Should reject order when projected margin exceeds 50% equity threshold")
    void testRejectWhenMarginShieldExceeded() {
        SmallAccountMarginGuard guard = new SmallAccountMarginGuard(0.50, 2, 5000.0);
        // MNQ requires $1800 initial margin
        Order order = new Order("MNQ", Order.Side.BUY, Order.Type.MARKET, 1.0, 18000.0);

        // Account with only $3,000 equity -> $1800 is 60% of equity (exceeds 50% max)
        SmallAccountMarginGuard.ValidationResult result = guard.validateOrder(order, 3000.0, List.of());

        assertFalse(result.allowed());
        assertTrue(result.reason().contains("MARGIN_SHIELD_EXCEEDED"));
    }

    @Test
    @DisplayName("Close-only orders should always be approved regardless of margin")
    void testCloseOnlyAlwaysApproved() {
        SmallAccountMarginGuard guard = new SmallAccountMarginGuard(0.50, 2, 5000.0);
        Order closeOrder = new Order("MES", Order.Side.SELL, Order.Type.MARKET, 1.0, 5000.0).asCloseOnly();

        SmallAccountMarginGuard.ValidationResult result = guard.validateOrder(closeOrder, 500.0, List.of());

        assertTrue(result.allowed());
        assertEquals("APPROVED", result.reason());
    }
}
