package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpIbkrGatewayClientTest {

    @Test
    void placeMarketOrder_failsLoudlyInsteadOfFabricating() {
        TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
            new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));

        assertThrows(UnsupportedOperationException.class, () ->
            client.placeMarketOrder("MES", 1.0, Order.Side.BUY, "tag"));
    }

    @Test
    void fetchAccountSummary_failsLoudlyInsteadOfFabricating() {
        TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
            new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));

        assertThrows(UnsupportedOperationException.class, client::fetchAccountSummary);
    }

    @Test
    void fetchOpenPositions_failsLoudlyInsteadOfFabricating() {
        TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
            new IbkrConnectionConfig("127.0.0.1", 7497, 1, "DU12345"));

        assertThrows(UnsupportedOperationException.class, client::fetchOpenPositions);
    }
}
