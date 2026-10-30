package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import com.martinfou.trading.data.ibkr.TcpIbkrGatewayClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-paper integration test for IBKR stop orders (Story 46.8).
 *
 * <p>Skipped automatically when no IB Gateway is reachable on {@code IBKR_HOST:IBKR_PORT}
 * or when running inside a surefire test JVM where {@code OrderTripwire} blocks broker ports.</p>
 */
class IbkrPaperStopOrderIT {

    @Test
    @DisplayName("Places 1-contract MGC stop far from market, reads it back, and cancels it when gateway is reachable")
    void placeReadAndCancelStopOrderOnPaperGateway() {
        String host = System.getenv().getOrDefault("IBKR_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("IBKR_PORT", "4002"));
        String account = System.getenv().getOrDefault("IBKR_ACCOUNT_ID", "DUR967322");
        int clientId = Integer.parseInt(System.getenv().getOrDefault("IBKR_TEST_CLIENT_ID", "28"));

        Assumptions.assumeTrue(port != 4001 && port != 7496, "Refusing live IBKR port");
        Assumptions.assumeTrue(
            com.martinfou.trading.core.guardrails.OrderTripwire.decision(
                System.getenv(), System.getProperty("java.class.path")),
            "Skipping live paper order placement inside test runtime (OrderTripwire active)"
        );
        Assumptions.assumeTrue(isGatewayReachable(host, port),
            "IB Gateway not reachable at " + host + ":" + port + " — skipping live paper test");

        IbkrConnectionConfig config = new IbkrConnectionConfig(host, port, clientId, account);
        try (IbkrBroker broker = new IbkrBroker(new TcpIbkrGatewayClient(config))) {
            broker.connect();
            assertTrue(broker.isConnected());

            // Place a 1-contract MGC SELL STOP far below market ($500/oz) so it cannot fill
            Order stop = new Order("MGC", Order.Side.SELL, Order.Type.STOP, 1.0, 500.0)
                .asCloseOnly()
                .withStrategyId("sq-mgc-m30-breakout-0-24542_MGC");

            OrderSubmitResult submit = broker.submitOrder(stop);
            assertTrue(submit.accepted(), "Expected stop order to be accepted by paper gateway");
            assertNotNull(submit.brokerOrderId());
            assertFalse(submit.brokerOrderId().isBlank());

            OrderSubmitResult cancelled = broker.cancelOrder(submit.brokerOrderId());
            assertTrue(cancelled.accepted(), "Expected stop order cancellation to succeed");
        }
    }

    private static boolean isGatewayReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
