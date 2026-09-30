package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.Execution;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpIbkrGatewayClientTest {

    private final TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
        new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));

    // ------------------------------------------------------------------
    // Fail-closed contract (no live Gateway in unit tests)
    // ------------------------------------------------------------------

    @Test
    void placeMarketOrder_notConnected_returnsFailure() {
        var result = client.placeMarketOrder("MES", 1.0, Order.Side.BUY, "tag");
        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void fetchAccountSummary_notConnected_failClosedNotFabricated() {
        var snapshot = client.fetchAccountSummary();
        // Fail-closed: zeroed account, NOT a fabricated $100k healthy account.
        assertNotNull(snapshot);
        assertEquals(0.0, snapshot.balance(), 1e-9);
        assertEquals(0.0, snapshot.equity(), 1e-9);
    }

    @Test
    void fetchOpenPositions_notConnected_returnsEmpty() {
        List<IbkrPositionSnapshot> positions = client.fetchOpenPositions();
        assertTrue(positions.isEmpty());
    }

    @Test
    void isConnected_falseWithoutGateway() {
        assertFalse(client.isConnected());
    }

    // ------------------------------------------------------------------
    // Test seams — contract construction (no network)
    // ------------------------------------------------------------------

    @Test
    void buildContract_mesFuturesGlobex() {
        Contract contract = TcpIbkrGatewayClient.buildContract("MES");
        assertEquals("MES", contract.symbol());
        assertEquals("FUT", contract.secType().toString());
        assertEquals("GLOBEX", contract.exchange());
        assertEquals("USD", contract.currency());
        assertNotNull(contract.lastTradeDateOrContractMonth());
        assertEquals(8, contract.lastTradeDateOrContractMonth().length()); // YYYYMMDD
    }

    @Test
    void buildContract_mnqUsesMicroMultiplier() {
        Contract contract = TcpIbkrGatewayClient.buildContract("MNQ");
        assertEquals("MNQ", contract.symbol());
        assertEquals("2", contract.multiplier());
    }

    @Test
    void buildMarketOrder_buyMktTransmit() {
        com.ib.client.Order order = TcpIbkrGatewayClient.buildMarketOrder(1234, 1.5, Order.Side.BUY, "DU12345");
        assertEquals(1234, order.orderId());
        assertEquals("BUY", order.action().toString());
        assertEquals("MKT", order.orderType().toString());
        assertTrue(order.transmit());
        assertEquals("DU12345", order.account());
        assertEquals(1.5, order.totalQuantity().value().doubleValue(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Order correlation + execution mapping (no network)
    // ------------------------------------------------------------------

    @Test
    void trackOrder_mapsOrderIdToClientTag() {
        client.trackOrder(42, "app-order-1");
        assertEquals("app-order-1", client.clientTagForOrderId(42));
        assertNull(client.clientTagForOrderId(999));
    }

    @Test
    void mapExecution_botSideBecomesBuy() {
        Execution execution = new Execution();
        execution.orderId(42);
        execution.execId("E-42");
        execution.side("BOT");
        execution.shares(com.ib.client.Decimal.get(1));
        execution.price(5000.25);

        IbkrExecution mapped = TcpIbkrGatewayClient.mapExecution(execution, "MES", "app-order-1");
        assertTrue(mapped.isFill());
        assertEquals("42", mapped.orderId());
        assertEquals("E-42", mapped.execId());
        assertEquals("app-order-1", mapped.clientTag());
        assertEquals("MES", mapped.symbol());
        assertEquals(Order.Side.BUY, mapped.side());
        assertEquals(1.0, mapped.quantity(), 1e-9);
        assertEquals(5000.25, mapped.fillPrice(), 1e-9);
    }

    @Test
    void mapExecution_sldSideBecomesSell() {
        Execution execution = new Execution();
        execution.orderId(43);
        execution.execId("E-43");
        execution.side("SLD");
        execution.shares(com.ib.client.Decimal.get(2));
        execution.price(4999.75);

        IbkrExecution mapped = TcpIbkrGatewayClient.mapExecution(execution, "MES", null);
        assertEquals(Order.Side.SELL, mapped.side());
        assertEquals(2.0, mapped.quantity(), 1e-9);
    }

    @Test
    void formatMultiplier_integralAndFractional() {
        assertEquals("5", TcpIbkrGatewayClient.formatMultiplier(5.0));
        assertEquals("50", TcpIbkrGatewayClient.formatMultiplier(50.0));
        assertEquals("0.1", TcpIbkrGatewayClient.formatMultiplier(0.1));
    }
}
