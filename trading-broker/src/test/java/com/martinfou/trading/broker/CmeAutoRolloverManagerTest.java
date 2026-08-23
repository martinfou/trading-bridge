package com.martinfou.trading.broker;

import com.martinfou.trading.core.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CmeAutoRolloverManager Unit Tests (Story 46.4)")
class CmeAutoRolloverManagerTest {

    @Test
    @DisplayName("Should trigger rollover only after 2 consecutive volume crossover bars during roll window")
    void testRolloverCrossoverLogic() {
        CmeAutoRolloverManager manager = new CmeAutoRolloverManager();
        Position pos = new Position("MES", com.martinfou.trading.core.Order.Side.BUY, 1.0, 5000.0);

        // March 15, 2026 is inside roll window (March 20 expiry)
        LocalDate rollDate = LocalDate.of(2026, Month.MARCH, 15);

        // Bar 1: Next volume (15,000) > Front volume (10,000) -> 1st bar of crossover
        CmeAutoRolloverManager.RolloverPlan plan1 = manager.evaluateRollover(
            pos, rollDate, 10_000, 15_000, 1.25, "MESM26"
        );
        assertFalse(plan1.shouldRoll());
        assertEquals("AWAITING_VOLUME_CROSSOVER_CONFIRMATION", plan1.reason());

        // Bar 2: Next volume (18,000) > Front volume (9,000) -> 2nd consecutive bar -> ROLLOVER TRIGGERED
        CmeAutoRolloverManager.RolloverPlan plan2 = manager.evaluateRollover(
            pos, rollDate, 9_000, 18_000, 1.25, "MESM26"
        );
        assertTrue(plan2.shouldRoll());
        assertEquals("MESM26", plan2.targetSymbol());
        assertEquals(5001.25, plan2.adjustedEntryPrice(), 0.001);
        assertEquals("VOLUME_CROSSOVER_CONFIRMED", plan2.reason());
    }

    @Test
    @DisplayName("Should reject rollover when outside roll window")
    void testRejectOutsideRollWindow() {
        CmeAutoRolloverManager manager = new CmeAutoRolloverManager();
        Position pos = new Position("MES", com.martinfou.trading.core.Order.Side.BUY, 1.0, 5000.0);

        // February 15, 2026 is outside March roll window
        LocalDate outsideDate = LocalDate.of(2026, Month.FEBRUARY, 15);

        CmeAutoRolloverManager.RolloverPlan plan = manager.evaluateRollover(
            pos, outsideDate, 1_000, 10_000, 1.25, "MESM26"
        );
        assertFalse(plan.shouldRoll());
        assertEquals("OUTSIDE_ROLL_WINDOW", plan.reason());
    }
}
