package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import static org.junit.jupiter.api.Assertions.*;

class MarketSessionResolverTest {

    private static final ZoneId EASTERN = ZoneId.of("America/Toronto");

    @Test
    void testClassification() {
        assertEquals(MarketSessionResolver.MarketType.FOREX, MarketSessionResolver.classify("EUR_USD"));
        assertEquals(MarketSessionResolver.MarketType.FOREX, MarketSessionResolver.classify("GBPUSD"));
        assertEquals(MarketSessionResolver.MarketType.FUTURES, MarketSessionResolver.classify("/ES"));
        assertEquals(MarketSessionResolver.MarketType.FUTURES, MarketSessionResolver.classify("ESM6"));
        assertEquals(MarketSessionResolver.MarketType.STOCKS, MarketSessionResolver.classify("AAPL"));
        assertEquals(MarketSessionResolver.MarketType.STOCKS, MarketSessionResolver.classify("MSFT"));
    }

    @Test
    void testForexWeekendClose() {
        // Forex is closed: Friday 17:00 EST to Sunday 17:00 EST
        
        // Friday 16:59 EST -> Open (not closed)
        Instant friOpen = ZonedDateTime.of(2026, 6, 19, 16, 59, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("EUR_USD", "PAPER_OANDA", friOpen));

        // Friday 17:00 EST -> Closed
        Instant friClose = ZonedDateTime.of(2026, 6, 19, 17, 0, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("EUR_USD", "PAPER_OANDA", friClose));

        // Saturday 12:00 EST -> Closed
        Instant sat = ZonedDateTime.of(2026, 6, 20, 12, 0, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("EUR_USD", "PAPER_OANDA", sat));

        // Sunday 16:59 EST -> Closed
        Instant sunClose = ZonedDateTime.of(2026, 6, 21, 16, 59, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("EUR_USD", "PAPER_OANDA", sunClose));

        // Sunday 17:00 EST -> Open (not closed)
        Instant sunOpen = ZonedDateTime.of(2026, 6, 21, 17, 0, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("EUR_USD", "PAPER_OANDA", sunOpen));
    }

    @Test
    void testStocksClose() {
        // Stocks open Monday-Friday 09:30 EST to 16:00 EST. Closed otherwise.
        
        // Monday 09:29 EST -> Closed
        Instant monBeforeOpen = ZonedDateTime.of(2026, 6, 22, 9, 29, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("AAPL", "PAPER_IBKR", monBeforeOpen));

        // Monday 09:30 EST -> Open
        Instant monOpen = ZonedDateTime.of(2026, 6, 22, 9, 30, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("AAPL", "PAPER_IBKR", monOpen));

        // Monday 15:59 EST -> Open
        Instant monNearClose = ZonedDateTime.of(2026, 6, 22, 15, 59, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("AAPL", "PAPER_IBKR", monNearClose));

        // Monday 16:00 EST -> Closed
        Instant monClosed = ZonedDateTime.of(2026, 6, 22, 16, 0, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("AAPL", "PAPER_IBKR", monClosed));

        // Saturday 12:00 EST -> Closed
        Instant sat = ZonedDateTime.of(2026, 6, 20, 12, 0, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("AAPL", "PAPER_IBKR", sat));
    }

    @Test
    void testFuturesCloseAndMaintenance() {
        // Futures open: Sunday 18:00 EST to Friday 17:00 EST, excluding daily 17:00-18:00 EST
        
        // Friday 16:59 EST -> Open
        Instant friOpen = ZonedDateTime.of(2026, 6, 19, 16, 59, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", friOpen));

        // Friday 17:00 EST -> Closed
        Instant friClose = ZonedDateTime.of(2026, 6, 19, 17, 0, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", friClose));

        // Sunday 17:59 EST -> Closed
        Instant sunClose = ZonedDateTime.of(2026, 6, 21, 17, 59, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", sunClose));

        // Sunday 18:00 EST -> Open
        Instant sunOpen = ZonedDateTime.of(2026, 6, 21, 18, 0, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", sunOpen));

        // Monday 17:30 EST -> Closed (Maintenance hour)
        Instant monMaint = ZonedDateTime.of(2026, 6, 22, 17, 30, 0, 0, EASTERN).toInstant();
        assertTrue(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", monMaint));

        // Monday 18:00 EST -> Open
        Instant monAfterMaint = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, EASTERN).toInstant();
        assertFalse(MarketSessionResolver.isClosed("/ES", "PAPER_IBKR", monAfterMaint));
    }
}
