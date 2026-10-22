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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpIbkrGatewayClientTest {

    private final TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
        new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));

    // ------------------------------------------------------------------
    // Fail-closed contract (no live Gateway in unit tests)
    // ------------------------------------------------------------------

    @Test
    void placeMarketOrder_refusesWithoutTouchingTheWire() {
        // The order tripwire fires before the not-connected check: in a test runtime no order may
        // be attempted at all, so placeMarketOrder must throw rather than return a failure.
        assertThrows(IllegalStateException.class,
            () -> client.placeMarketOrder("MES", 1.0, Order.Side.BUY, "tag"));
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
    void buildContract_mesFuturesCme() {
        Contract contract = TcpIbkrGatewayClient.buildContract("MES");
        assertEquals("MES", contract.symbol());
        assertEquals("FUT", contract.secType().toString());
        assertEquals("CME", contract.exchange());
        assertEquals("USD", contract.currency());
        assertNotNull(contract.lastTradeDateOrContractMonth());
        assertEquals(8, contract.lastTradeDateOrContractMonth().length()); // YYYYMMDD
    }

    @Test
    void toSnapshot_keepsShortPositionVisible() {
        Contract mes = TcpIbkrGatewayClient.buildContract("MES");
        IbkrPositionSnapshot shortRow = TcpIbkrGatewayClient.toSnapshot(mes, -2.0, 38833.11);
        assertEquals("MES", shortRow.symbol());
        assertEquals(Order.Side.SELL, shortRow.side());
        assertEquals(2.0, shortRow.quantity(), 1e-9);
    }

    @Test
    void toSnapshot_longUsesBuySide() {
        Contract mes = TcpIbkrGatewayClient.buildContract("MES");
        IbkrPositionSnapshot row = TcpIbkrGatewayClient.toSnapshot(mes, 1.0, 38833.11);
        assertEquals(Order.Side.BUY, row.side());
        assertEquals(1.0, row.quantity(), 1e-9);
    }

    @Test
    void toSnapshot_dividesOutTheFuturesMultiplier() {
        Contract mes = TcpIbkrGatewayClient.buildContract("MES");
        // Measured on the paper Gateway: 1 MES filled at 7766.5, position avgCost came back 38833.11.
        IbkrPositionSnapshot row = TcpIbkrGatewayClient.toSnapshot(mes, 1.0, 38833.11);
        assertEquals(7766.622, row.averagePrice(), 1e-6);
    }

    @Test
    void toSnapshot_fallsBackToTheRegistryWhenTheContractCarriesNoMultiplier() {
        // IBKR's position callback does not always populate Contract.multiplier(); without the
        // fallback the avgCost stays 5x too large (measured 38833.11 for a 7766.5 fill).
        Contract bare = new Contract();
        bare.symbol("MES");
        IbkrPositionSnapshot row = TcpIbkrGatewayClient.toSnapshot(bare, 1.0, 38833.11);
        assertEquals(7766.622, row.averagePrice(), 1e-6);
    }

    @Test
    void normalizeAveragePrice_leavesNonDerivativesAlone() {
        assertEquals(1.105, TcpIbkrGatewayClient.normalizeAveragePrice(1.105, "1"), 1e-9);
        assertEquals(1.105, TcpIbkrGatewayClient.normalizeAveragePrice(1.105, null), 1e-9);
        assertEquals(1.105, TcpIbkrGatewayClient.normalizeAveragePrice(1.105, ""), 1e-9);
        assertEquals(1.105, TcpIbkrGatewayClient.normalizeAveragePrice(1.105, "not-a-number"), 1e-9);
    }

    @Test
    void buildContract_mnqUsesMicroMultiplier() {
        Contract contract = TcpIbkrGatewayClient.buildContract("MNQ");
        assertEquals("MNQ", contract.symbol());
        assertEquals("2", contract.multiplier());
    }

    @Test
    void buildMarketOrder_carriesTheOrderRef() {
        // Le nom de la strategie doit voyager jusqu'aux fills, sinon la lane P&L le compte comme un leak.
        com.ib.client.Order order = TcpIbkrGatewayClient.buildMarketOrder(1235, 1, Order.Side.SELL, "DU12345",
                "mesbreakout");
        org.junit.jupiter.api.Assertions.assertEquals("mesbreakout", order.orderRef());
    }

    @Test
    void buildMarketOrder_withoutOrderRefLeavesItEmpty() {
        com.ib.client.Order order = TcpIbkrGatewayClient.buildMarketOrder(1236, 1, Order.Side.BUY, "DU12345");
        org.junit.jupiter.api.Assertions.assertTrue(order.orderRef() == null || order.orderRef().isEmpty());
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

    // ------------------------------------------------------------------
    // orderStatus fills: IBKR repeats the status, a fill must be counted once
    // ------------------------------------------------------------------

    /** The wrapper is a private inner class created in the constructor: reach it by reflection. */
    private Object gatewayWrapper() throws Exception {
        java.lang.reflect.Field field = TcpIbkrGatewayClient.class.getDeclaredField("wrapper");
        field.setAccessible(true);
        return field.get(client);
    }

    private void fireOrderStatus(Object wrapper, int orderId, String status, double filled,
                                 double avgFillPrice) throws Exception {
        java.lang.reflect.Method method = wrapper.getClass().getDeclaredMethod("orderStatus",
            int.class, String.class, com.ib.client.Decimal.class, com.ib.client.Decimal.class,
            double.class, long.class, int.class, double.class, int.class, String.class, double.class);
        method.setAccessible(true);
        com.ib.client.Decimal filledDec = com.ib.client.Decimal.get(java.math.BigDecimal.valueOf(filled));
        com.ib.client.Decimal remainingDec = com.ib.client.Decimal.get(java.math.BigDecimal.ZERO);
        method.invoke(wrapper, orderId, status, filledDec, remainingDec, avgFillPrice, 0L, 0, avgFillPrice, 1,
            null, 0.0);
    }

    @Test
    void orderStatus_repeatedFilledIsPublishedOnce() throws Exception {
        List<IbkrExecution> seen = new java.util.ArrayList<>();
        client.addExecutionListener(seen::add);
        Object wrapper = gatewayWrapper();

        fireOrderStatus(wrapper, 77, "Filled", 2.0, 5000.5);
        fireOrderStatus(wrapper, 77, "Filled", 2.0, 5000.5);

        assertEquals(1, seen.size(), "un statut Filled repete ne doit produire qu'une seule execution");
        assertTrue(seen.get(0).isFill());
        assertEquals(2.0, seen.get(0).quantity(), 1e-9);
        assertEquals(5000.5, seen.get(0).fillPrice(), 1e-9);
    }

    @Test
    void orderStatus_partialFillIsNotPublishedAsACompleteFill() throws Exception {
        List<IbkrExecution> seen = new java.util.ArrayList<>();
        client.addExecutionListener(seen::add);
        Object wrapper = gatewayWrapper();

        // Un statut partiel ne doit pas passer pour un fill complet: les fills partiels
        // arrivent par execDetails, ou ils sont conserves un par un.
        fireOrderStatus(wrapper, 78, "Partially filled", 1.0, 4999.0);

        assertTrue(seen.isEmpty(), "un partiel ne doit pas etre publie comme un fill complet");
    }
}
