package com.martinfou.trading.data.ibkr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class IbkrContractResolverTest {

    @Test
    void testResolveCmeFuturesContracts() {
        LocalDate date = LocalDate.of(2024, 2, 1);
        var mesDetails = IbkrContractResolver.resolve("MES", date);

        assertEquals("MES", mesDetails.symbol());
        assertEquals(IbkrContractResolver.SecType.FUT, mesDetails.secType());
        assertEquals("CME", mesDetails.exchange());
        assertEquals("USD", mesDetails.currency());
        assertEquals(5.0, mesDetails.multiplier(), 1e-6);
        assertEquals("202403", mesDetails.lastTradeDateOrContractMonth()); // March 2024 front month

        var m2kDetails = IbkrContractResolver.resolve("M2K", date);
        assertEquals("M2K", m2kDetails.symbol());
        assertEquals(5.0, m2kDetails.multiplier(), 1e-6);

        var emdDetails = IbkrContractResolver.resolve("EMD", date);
        assertEquals("EMD", emdDetails.symbol());
        assertEquals(100.0, emdDetails.multiplier(), 1e-6);
    }

    @Test
    void testResolveUsEquities() {
        var iwmDetails = IbkrContractResolver.resolve("IWM");
        assertEquals("IWM", iwmDetails.symbol());
        assertEquals(IbkrContractResolver.SecType.STK, iwmDetails.secType());
        assertEquals("SMART", iwmDetails.exchange());
        assertEquals("USD", iwmDetails.currency());
        assertEquals(1.0, iwmDetails.multiplier(), 1e-6);
        assertNull(iwmDetails.lastTradeDateOrContractMonth());

        var mdyDetails = IbkrContractResolver.resolve("MDY");
        assertEquals("MDY", mdyDetails.symbol());
        assertEquals(IbkrContractResolver.SecType.STK, mdyDetails.secType());

        var aaplDetails = IbkrContractResolver.resolve("AAPL");
        assertEquals("AAPL", aaplDetails.symbol());
        assertEquals(IbkrContractResolver.SecType.STK, aaplDetails.secType());
    }

    @Test
    void testResolveForex() {
        var eurUsd = IbkrContractResolver.resolve("EUR_USD");
        assertEquals("EUR", eurUsd.symbol());
        assertEquals(IbkrContractResolver.SecType.CASH, eurUsd.secType());
        assertEquals("IDEALPRO", eurUsd.exchange());
        assertEquals("USD", eurUsd.currency());
    }

    @Test
    void stocksHaveNoHardcodedPrimaryExchange() {
        // A wrong primary exchange (e.g. ISLAND for ARCA-listed SPY) makes IB return "No security definition".
        assertNull(IbkrContractResolver.resolve("SPY").primaryExchange());
        assertNull(IbkrContractResolver.resolve("AAPL").primaryExchange());
    }

    @Test
    void explicitPrefixesOverrideHeuristics() {
        var brk = IbkrContractResolver.resolve("STK:BRK B");
        assertEquals(IbkrContractResolver.SecType.STK, brk.secType());
        assertEquals("BRK B", brk.symbol());

        var fut = IbkrContractResolver.resolve("FUT:MES", java.time.LocalDate.of(2024, 1, 15));
        assertEquals(IbkrContractResolver.SecType.FUT, fut.secType());

        var fx = IbkrContractResolver.resolve("CASH:EUR_USD");
        assertEquals(IbkrContractResolver.SecType.CASH, fx.secType());

        assertThrows(IllegalArgumentException.class, () -> IbkrContractResolver.resolve("FUT:NOPE"));
    }

    @Test
    void toIbContractAndBackRoundTrips() {
        var mes = IbkrContractResolver.resolve("MES", java.time.LocalDate.of(2024, 1, 15));
        var c = IbkrContractResolver.toIbContract(mes);
        assertEquals("MES", c.symbol());
        assertEquals("FUT", c.getSecType());
        assertEquals("CME", c.exchange());
        assertEquals("202403", c.lastTradeDateOrContractMonth());
        assertEquals("5", c.multiplier());
        assertEquals("MES", IbkrContractResolver.toTradingSymbol(c));
        assertEquals(5000.0, IbkrContractResolver.perUnitPrice(c, 25000.0), 1e-9);

        var eur = IbkrContractResolver.toIbContract(IbkrContractResolver.resolve("EUR_USD"));
        assertEquals("EUR_USD", IbkrContractResolver.toTradingSymbol(eur));

        var spy = IbkrContractResolver.toIbContract(IbkrContractResolver.resolve("SPY"));
        assertEquals("SMART", spy.exchange());
        assertEquals(410.0, IbkrContractResolver.perUnitPrice(spy, 410.0), 1e-9);
    }
}
