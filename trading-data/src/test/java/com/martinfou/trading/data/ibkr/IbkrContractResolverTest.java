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
        assertEquals("GLOBEX", mesDetails.exchange());
        assertNull(mesDetails.primaryExchange());
        assertEquals("USD", mesDetails.currency());
        assertEquals(5.0, mesDetails.multiplier(), 1e-6);
        assertEquals("20240315", mesDetails.lastTradeDateOrContractMonth()); // March 2024 front month (3rd Friday)

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

            // The mock server accepts TCP but does NOT speak the IBKR v1045 protocol: no
            // connectAck will arrive. The client must fail-loud (never a half-connected state).
            assertThrows(IllegalStateException.class, client::connect);
            assertFalse(client.isConnected());
        }
    }

    @Test
    void testTcpGatewayNotConnected_failClosed() {
        // No Gateway running: account/positions must be fail-closed (zeroed), never fabricated.
        TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
            new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));
        assertFalse(client.isConnected());
        var account = client.fetchAccountSummary();
        assertEquals(0.0, account.balance(), 1e-9);
        assertEquals(0.0, account.equity(), 1e-9);
        assertTrue(client.fetchOpenPositions().isEmpty());
    }
}
