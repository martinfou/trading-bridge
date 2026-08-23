package com.martinfou.trading.data.ibkr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

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
    void testTcpGatewayConnectivity() throws IOException {
        try (MockTcpGatewayServer server = new MockTcpGatewayServer()) {
            IbkrConnectionConfig config = new IbkrConnectionConfig("127.0.0.1", server.port(), 1, "DU12345");
            TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(config);

            client.connect();
            assertTrue(client.isConnected());

            // The mock server increments its connection counter on a separate acceptLoop thread,
            // so poll briefly instead of asserting synchronously (race condition).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.connectionCount() == 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for mock server", ie);
                }
            }
            assertEquals(1, server.connectionCount());

            client.disconnect();
            assertFalse(client.isConnected());
        }
    }
}
