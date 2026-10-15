package com.martinfou.trading.data.ibkr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbkrAccountCacheTest {

    @Test
    void snapshotDefaultsAreFailClosed() {
        // A cache that never received account data must NOT fabricate a healthy $100k account.
        IbkrAccountCache cache = new IbkrAccountCache("DU12345");

        IbkrAccountCache.AccountSummary s = cache.snapshot();

        assertEquals(0.0, s.netLiquidation(), 1e-9);
        assertEquals(0.0, s.totalCashBalance(), 1e-9);
        assertEquals(0.0, s.buyingPower(), 1e-9);
        assertEquals(0.0, s.availableFunds(), 1e-9);
        assertEquals(0, s.dayTradesRemaining());
        // With zero equity and zero day-trades, the account must be treated as PDT-restricted
        // (fail closed) rather than unrestricted.
        assertTrue(s.pdtRestricted());
        assertFalse(cache.isFresh(java.time.Duration.ofSeconds(1)));
    }

    @Test
    void snapshotReflectsReceivedValues() {
        IbkrAccountCache cache = new IbkrAccountCache("DU12345");
        cache.updateValue("NetLiquidation", 50_000.0);
        cache.updateValue("TotalCashBalance", 48_000.0);
        cache.updateValue("BuyingPower", 120_000.0);
        cache.updateValue("DayTradesRemaining", 3.0);

        IbkrAccountCache.AccountSummary s = cache.snapshot();

        assertEquals(50_000.0, s.netLiquidation(), 1e-9);
        assertEquals(48_000.0, s.totalCashBalance(), 1e-9);
        assertEquals(120_000.0, s.buyingPower(), 1e-9);
        assertEquals(3, s.dayTradesRemaining());
        assertFalse(s.pdtRestricted());
        assertTrue(cache.isFresh(java.time.Duration.ofSeconds(1)));
    }
}
