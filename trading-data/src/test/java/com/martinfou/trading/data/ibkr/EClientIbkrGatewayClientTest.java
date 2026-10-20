package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.OrderType;
import com.ib.client.Types;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class EClientIbkrGatewayClientTest {

    private static final IbkrConnectionConfig CONFIG = new IbkrConnectionConfig("127.0.0.1", 4002, 17, "DU1234567");

    @Test
    void accountDetailsReadBaseTagsLedgerPnlAndExchangeRatesWithoutInventingZeros() {
        var tags = java.util.Map.of("NetLiquidation", "11285.63", "TotalCashValue", "11285.63", "BuyingPower", "");
        var ledger = java.util.Map.of(
            "ExchangeRate|CAD", "1.00", "ExchangeRate|USD", "1.3812", "ExchangeRate|BASE", "1.00",
            "UnrealizedPnL|BASE", "-12.5", "RealizedPnL|BASE", "30.19", "RealizedPnL|USD", "21.88");

        IbkrAccountDetails d = EClientIbkrGatewayClient.toAccountDetails("DU1234567", "CAD", tags, ledger);

        assertEquals("CAD", d.baseCurrency());
        assertEquals(11285.63, d.value(IbkrAccountDetails.NET_LIQUIDATION));
        assertNull(d.value(IbkrAccountDetails.BUYING_POWER), "blank must stay unknown");
        assertEquals(-12.5, d.value(IbkrAccountDetails.UNREALIZED_PNL));
        assertEquals(30.19, d.value(IbkrAccountDetails.REALIZED_PNL));
        assertEquals(1.3812, d.rateToBase("USD"));
        assertEquals(1.0, d.rateToBase("CAD"));
        assertNull(d.rateToBase("EUR"));
        assertEquals("CAD", EClientIbkrGatewayClient.toAccountDetails("DU1", null, tags, ledger).baseCurrency());
    }

    @Test
    void executionTimeParsesZonedAndUtcFormats() {
        assertEquals(java.time.Instant.parse("2026-10-19T14:12:33Z"),
            EClientIbkrGatewayClient.parseExecutionTime("20261019 10:12:33 America/New_York"));
        assertEquals(java.time.Instant.parse("2026-10-19T14:12:33Z"),
            EClientIbkrGatewayClient.parseExecutionTime("20261019-14:12:33"));
        assertNotNull(EClientIbkrGatewayClient.parseExecutionTime("garbage"));
    }

    @Test
    void marketOrderMapsToSingleTransmittedMkt() {
        var req = IbkrOrderRequest.market("ord-1", "SPY", Order.Side.BUY, 10);
        List<com.ib.client.Order> orders = EClientIbkrGatewayClient.buildOrders(req, 100, "DU1234567", 0.01);

        assertEquals(1, orders.size());
        var o = orders.get(0);
        assertEquals(100, o.orderId());
        assertEquals(Types.Action.BUY, o.action());
        assertEquals(OrderType.MKT, o.orderType());
        assertEquals(10.0, o.totalQuantity().value().doubleValue());
        assertEquals("DU1234567", o.account());
        assertEquals("ord-1", o.orderRef());
        assertTrue(o.transmit());
    }

    @Test
    void limitAndStopPricesAreTickRounded() {
        var lmt = new IbkrOrderRequest("l", "MES", Order.Side.BUY, Order.Type.LIMIT, 1, 5000.13, 0, 0, 0, false);
        assertEquals(5000.25, EClientIbkrGatewayClient.buildOrders(lmt, 1, null, 0.25).get(0).lmtPrice(), 1e-9);
        assertEquals(OrderType.LMT, EClientIbkrGatewayClient.buildOrders(lmt, 1, null, 0.25).get(0).orderType());

        var stp = new IbkrOrderRequest("s", "MES", Order.Side.SELL, Order.Type.STOP, 1, 0, 4999.9, 0, 0, false);
        var stop = EClientIbkrGatewayClient.buildOrders(stp, 1, null, 0.25).get(0);
        assertEquals(OrderType.STP, stop.orderType());
        assertEquals(5000.0, stop.auxPrice(), 1e-9);
        assertEquals(Types.Action.SELL, stop.action());

        assertEquals(1.23457, EClientIbkrGatewayClient.roundToTick(1.234567, 0.00001), 1e-12);
        assertEquals(42.5, EClientIbkrGatewayClient.roundToTick(42.5, 0), 1e-12);
    }

    @Test
    void bracketBuildsParentPlusTpAndSlChildren() {
        var req = new IbkrOrderRequest("b-1", "MES", Order.Side.BUY, Order.Type.LIMIT, 2,
            5000, 0, 4990.1, 5020.3, false);
        List<com.ib.client.Order> orders = EClientIbkrGatewayClient.buildOrders(req, 50, "DU1234567", 0.25);

        assertEquals(3, orders.size());
        var parent = orders.get(0);
        var tp = orders.get(1);
        var sl = orders.get(2);

        assertFalse(parent.transmit(), "parent must not transmit before children are attached");
        assertFalse(tp.transmit());
        assertTrue(sl.transmit(), "last child transmits the whole bracket");

        assertEquals(51, tp.orderId());
        assertEquals(50, tp.parentId());
        assertEquals(Types.Action.SELL, tp.action());
        assertEquals(OrderType.LMT, tp.orderType());
        assertEquals(5020.25, tp.lmtPrice(), 1e-9);
        assertEquals("b-1-TP", tp.orderRef());

        assertEquals(52, sl.orderId());
        assertEquals(50, sl.parentId());
        assertEquals(Types.Action.SELL, sl.action());
        assertEquals(OrderType.STP, sl.orderType());
        assertEquals(4990.0, sl.auxPrice(), 1e-9);
        assertEquals("b-1-SL", sl.orderRef());
        assertEquals(2.0, sl.totalQuantity().value().doubleValue());
    }

    @Test
    void submittedStatusAcksOrder() throws Exception {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);
        client.trackForTest(7, "ord-7", "MES", Order.Side.BUY, 1);

        client.wrapper.orderStatus(7, "Submitted", Decimal.ZERO, Decimal.ONE, 0, 1L, 0, 0, 17, "", 0);

        assertEquals("Submitted", client.ackFutureForTest(7).get());
        assertEquals(IbkrOrderUpdate.Kind.STATUS, updates.get(0).kind());
        assertEquals("ord-7", updates.get(0).clientOrderId());
    }

    @Test
    void orderErrorRejectsBeforeAck() {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);
        client.trackForTest(8, "ord-8", "AAPL", Order.Side.BUY, 5);

        client.wrapper.error(8, 0L, 201, "Order rejected - reason: insufficient margin", "");
        client.wrapper.orderStatus(8, "Cancelled", Decimal.ZERO, Decimal.get(5), 0, 1L, 0, 0, 17, "", 0);

        var ex = assertThrows(ExecutionException.class, () -> client.ackFutureForTest(8).get());
        assertTrue(ex.getCause().getMessage().contains("201"));
        long rejects = updates.stream().filter(u -> u.kind() == IbkrOrderUpdate.Kind.REJECTED).count();
        assertEquals(1, rejects, "reject emitted exactly once");
        assertTrue(updates.stream().noneMatch(u -> u.kind() == IbkrOrderUpdate.Kind.CANCELLED));
    }

    @Test
    void warningCodesDoNotReject() throws Exception {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);
        client.trackForTest(9, "ord-9", "MES", Order.Side.BUY, 1);

        client.wrapper.error(9, 0L, 399, "Order will not be placed until market open", "");
        client.wrapper.orderStatus(9, "PreSubmitted", Decimal.ZERO, Decimal.ONE, 0, 1L, 0, 0, 17, "", 0);

        assertEquals("PreSubmitted", client.ackFutureForTest(9).get());
        assertTrue(updates.stream().noneMatch(u -> u.kind() == IbkrOrderUpdate.Kind.REJECTED));
    }

    @Test
    void cancelAfterAckEmitsCancelledOnce() {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);
        client.trackForTest(10, "ord-10", "MES", Order.Side.BUY, 1);
        client.wrapper.orderStatus(10, "Submitted", Decimal.ZERO, Decimal.ONE, 0, 1L, 0, 0, 17, "", 0);

        client.wrapper.error(10, 0L, 202, "Order Canceled - reason:", "");
        client.wrapper.orderStatus(10, "Cancelled", Decimal.ZERO, Decimal.ONE, 0, 1L, 0, 0, 17, "", 0);

        assertEquals(1, updates.stream().filter(u -> u.kind() == IbkrOrderUpdate.Kind.CANCELLED).count());
    }

    @Test
    void executionsAreDedupedAndCarryCumulativeFill() {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);
        client.trackForTest(11, "ord-11", "MES", Order.Side.BUY, 3);

        Contract mes = new Contract();
        mes.symbol("MES");
        mes.secType("FUT");
        client.wrapper.execDetails(-1, mes, exec(11, "X1", "BOT", 1, 5000.25, 1, 5000.25));
        client.wrapper.execDetails(-1, mes, exec(11, "X1", "BOT", 1, 5000.25, 1, 5000.25)); // duplicate
        client.wrapper.execDetails(-1, mes, exec(11, "X2", "BOT", 2, 5000.50, 3, 5000.4167));

        var fills = updates.stream().filter(u -> u.kind() == IbkrOrderUpdate.Kind.EXECUTION).toList();
        assertEquals(2, fills.size());
        assertEquals(1.0, fills.get(0).quantity());
        assertEquals(2.0, fills.get(0).remaining());
        assertEquals(3.0, fills.get(1).filledTotal());
        assertEquals(0.0, fills.get(1).remaining());
        assertEquals("ord-11", fills.get(1).clientOrderId());
        assertEquals(Order.Side.BUY, fills.get(1).side());
    }

    @Test
    void connectivityCodesEmitConnectionEvents() {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var updates = record(client);

        client.wrapper.error(-1, 0L, 2104, "Market data farm connection is OK:usfarm", "");
        client.wrapper.error(-1, 0L, 1100, "Connectivity between IB and TWS has been lost.", "");
        client.wrapper.error(-1, 0L, 1102, "Connectivity restored - data maintained.", "");

        assertEquals(List.of("LOST", "RESTORED"), updates.stream().map(IbkrOrderUpdate::status).toList());
    }

    @Test
    void placeOrderWhenDisconnectedRejectsWithoutSocket() {
        var client = new EClientIbkrGatewayClient(CONFIG);
        var ack = client.placeOrder(IbkrOrderRequest.market("x", "SPY", Order.Side.BUY, 1));
        assertFalse(ack.accepted());
        assertTrue(ack.errorMessage().contains("not connected"));
    }

    @Test
    void connectToClosedPortFailsWithActionableMessage() {
        var client = new EClientIbkrGatewayClient(new IbkrConnectionConfig("127.0.0.1", 1, 17, "DU1"));
        var ex = assertThrows(IllegalStateException.class, client::connect);
        assertTrue(ex.getMessage().contains("Enable ActiveX and Socket Clients"));
        assertFalse(client.isConnected());
    }

    @Test
    void accountIsMaskedInLogs() {
        assertEquals("****4567", EClientIbkrGatewayClient.mask("DU1234567"));
        assertEquals("****", EClientIbkrGatewayClient.mask(null));
    }

    private static List<IbkrOrderUpdate> record(EClientIbkrGatewayClient client) {
        List<IbkrOrderUpdate> updates = new CopyOnWriteArrayList<>();
        client.addOrderListener(updates::add);
        return updates;
    }

    private static Execution exec(int orderId, String execId, String side, double shares, double price,
                                  double cum, double avg) {
        Execution e = new Execution();
        e.orderId(orderId);
        e.execId(execId);
        e.side(side);
        e.shares(Decimal.get(shares));
        e.price(price);
        e.cumQty(Decimal.get(cum));
        e.avgPrice(avg);
        return e;
    }
}
