package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.EClientIbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live smoke test against a running, logged-in IB Gateway <b>paper</b> session.
 * Not part of the normal build (IT suffix); run explicitly:
 *
 * <pre>
 *   IBKR_IT=true ./mvnw -pl trading-broker -am test -Dtest=IbkrPaperSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
 *   IBKR_IT=true IBKR_IT_ORDERS=true ...   # also places (paper) orders
 * </pre>
 *
 * Order tests refuse to run unless every managed account is a paper account ({@code DU...}).
 */
@EnabledIfEnvironmentVariable(named = "IBKR_IT", matches = "true")
class IbkrPaperSmokeIT {

    private EClientIbkrGatewayClient client;
    private IbkrBroker broker;
    private final List<BrokerEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void connect() {
        String host = env("IBKR_GATEWAY_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("IBKR_GATEWAY_PORT", String.valueOf(IbkrConnectionConfig.DEFAULT_PAPER_PORT)));
        int clientId = Integer.parseInt(env("IBKR_CLIENT_ID", "77"));
        client = new EClientIbkrGatewayClient(new IbkrConnectionConfig(host, port, clientId, System.getenv("IBKR_ACCOUNT_ID")));
        broker = new IbkrBroker(client, Duration.ofSeconds(15));
        broker.addEventListener(e -> {
            events.add(e);
            System.out.println("  event: " + e.toPayload());
        });
        broker.connect();
    }

    @AfterEach
    void disconnect() {
        if (broker != null) {
            broker.disconnect();
        }
    }

    @Test
    void readOnly_accountsSummaryAndPositions() {
        assertTrue(broker.isConnected());
        List<String> accounts = client.managedAccounts();
        System.out.println("Managed accounts: " + accounts.stream().map(IbkrPaperSmokeIT::mask).toList()
            + " paper=" + accounts.stream().allMatch(a -> a.startsWith("DU")));
        assertFalse(accounts.isEmpty());

        var state = broker.getAccountState();
        System.out.printf("Account: equity=%.2f cash=%.2f %s%n", state.equity(), state.balance(), state.currency());
        assertTrue(state.equity() > 0, "NetLiquidation should be positive");

        var positions = broker.getPositions();
        System.out.println("Positions: " + positions);
        System.out.println("Open orders: " + client.fetchOpenOrders());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IBKR_IT_ORDERS", matches = "true")
    void orders_mesLimitFarFromMarketThenCancel() throws Exception {
        requirePaper();
        var order = new Order("MES", Order.Side.BUY, Order.Type.LIMIT, 1, priceFarBelow());
        var result = broker.submitOrder(order);
        assertTrue(result.accepted(), "limit order should be accepted: " + result.rejectReason());
        assertTrue(client.fetchOpenOrders().stream().anyMatch(o -> o.brokerOrderId().equals(result.brokerOrderId())),
            "order should be working at IB");

        assertTrue(broker.cancelOrder(result.brokerOrderId()).accepted());
        awaitEvent(e -> e.type() == BrokerEventType.REJECT && "CANCELLED".equals(e.message())
            && order.id().equals(e.orderId()), "cancel confirmation");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IBKR_IT_ORDERS", matches = "true")
    void orders_mesMarketRoundTrip() throws Exception {
        requirePaper();
        var buy = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1, 0);
        assertTrue(broker.submitOrder(buy).accepted());
        BrokerEvent buyFill = awaitEvent(e -> e.type() == BrokerEventType.FILL && buy.id().equals(e.orderId()), "buy fill");
        System.out.println("BUY filled @ " + buyFill.price());

        var sell = new Order("MES", Order.Side.SELL, Order.Type.MARKET, 1, 0);
        assertTrue(broker.submitOrder(sell).accepted());
        BrokerEvent sellFill = awaitEvent(e -> e.type() == BrokerEventType.FILL && sell.id().equals(e.orderId()), "sell fill");
        System.out.println("SELL filled @ " + sellFill.price());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IBKR_IT_ORDERS", matches = "true")
    void orders_mesBracketThenCancelParent() throws Exception {
        requirePaper();
        double entry = priceFarBelow();
        var order = new Order("MES", Order.Side.BUY, Order.Type.LIMIT, 1, entry, entry - 20, entry + 40);
        var result = broker.submitOrder(order);
        assertTrue(result.accepted(), "bracket should be accepted: " + result.rejectReason());

        long legs = client.fetchOpenOrders().stream()
            .filter(o -> o.brokerOrderId().equals(result.brokerOrderId()) || result.brokerOrderId().equals(o.parentId()))
            .count();
        assertEquals(3, legs, "parent + TP + SL should be working");

        broker.cancelOrder(result.brokerOrderId());
        awaitEvent(e -> "CANCELLED".equals(e.message()) && (order.id() + "-SL").equals(e.orderId()), "SL child cancelled");
        awaitEvent(e -> "CANCELLED".equals(e.message()) && (order.id() + "-TP").equals(e.orderId()), "TP child cancelled");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IBKR_IT_ORDERS", matches = "true")
    void orders_stockLimitFarFromMarketThenCancel() throws Exception {
        requirePaper();
        var order = new Order("SPY", Order.Side.BUY, Order.Type.LIMIT, 1, 1.00);
        var result = broker.submitOrder(order);
        assertTrue(result.accepted(), "stock limit should be accepted: " + result.rejectReason());
        assertTrue(broker.cancelOrder(result.brokerOrderId()).accepted());
        awaitEvent(e -> "CANCELLED".equals(e.message()) && order.id().equals(e.orderId()), "stock cancel");
    }

    /** MES entry far below any plausible market so it never fills; overridable via IBKR_IT_MES_FAR_PRICE. */
    private static double priceFarBelow() {
        return Double.parseDouble(env("IBKR_IT_MES_FAR_PRICE", "3000"));
    }

    private void requirePaper() {
        List<String> accounts = client.managedAccounts();
        assertFalse(accounts.isEmpty());
        assertTrue(accounts.stream().allMatch(a -> a.startsWith("DU")),
            "REFUSING to place orders: session is not a paper account");
    }

    private BrokerEvent awaitEvent(java.util.function.Predicate<BrokerEvent> match, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (BrokerEvent e : events) {
                if (match.test(e)) {
                    return e;
                }
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + what + "; events=" + events.stream().map(BrokerEvent::toPayload).toList());
        return null;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String mask(String account) {
        return account == null || account.length() <= 4 ? "****" : account.substring(0, 2) + "****" + account.substring(account.length() - 3);
    }
}
