package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the live IBKR gateway client refuses to place an order without touching the wire when the
 * order tripwire is armed. The tripwire fires before any connection attempt, so the listener (a
 * local {@link HttpServer} standing in for the gateway) must receive 0 requests.
 */
class TcpIbkrGatewayClientTripwireTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void placeMarketOrder_refusesWithoutTouchingTheWire() {
        TcpIbkrGatewayClient client = new TcpIbkrGatewayClient(
                new IbkrConnectionConfig("127.0.0.1", port, 1, "DU12345"));
        assertThrows(IllegalStateException.class,
                () -> client.placeMarketOrder("EURUSD", 1.0, Order.Side.SELL, "tag"));
        assertEquals(0, requestCount.get(), "no connection/request may be made when the tripwire refuses");
    }
}
