package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PromoteGateThresholdsTest {

    @Test
    void defaults_matchDocumentedMvpValues() {
        PromoteGateThresholds t = PromoteGateThresholds.DEFAULT;
        assertEquals(1, t.minTrades());
        assertEquals(15.0, t.maxDrawdownPct());
        assertEquals(-50.0, t.minReturnPct());
        assertEquals(1.0, t.goldenReturnTolerancePct());
        assertEquals(30, t.paperDaysBeforeLive());
        assertFalse(t.validationModuleEnabled());
    }
}
