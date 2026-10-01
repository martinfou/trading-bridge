package com.martinfou.trading.data.oanda;

import com.martinfou.trading.core.guardrails.OrderTripwire;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the live OANDA HTTP client refuses to place an order without sending a request when the
 * order tripwire is armed. A local {@link HttpServer} counts requests: the count must stay 0.
 */
class HttpOandaRestClientTripwireTest {

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
                byte[] body = "{\"orderCreateTransaction\":{\"id\":\"1\"},\"orderFillTransaction\":{\"tradeOpened\":{\"tradeID\":\"t1\"},\"price\":\"1.1\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
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
    void placeMarketOrder_refusesWithoutSending() {
        var client = new HttpOandaRestClient("token", "123", "http://localhost:" + port + "/");
        assertThrows(IllegalStateException.class,
                () -> client.placeMarketOrder("EUR_USD", -1000, "my-tag"));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }

    @Test
    void placeOrder_refusesWithoutSending() {
        var client = new HttpOandaRestClient("token", "123", "http://localhost:" + port + "/");
        assertThrows(IllegalStateException.class,
                () -> client.placeOrder("MARKET", "EUR_USD", -1000, 0, 1.05, 0, 0, false, "my-tag", false));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }

    @Test
    void placeMarketOrder_testGateOpenButRealBroker_refusesBeforeAnyRequest() throws Exception {
        // ANTI-INCIDENT: even with the test-only gate open, a REAL broker destination is refused
        // before any HTTP request is built or sent. The tripwire throws in checkOrderAllowed (the
        // first statement of placeMarketOrder, outside its try/catch), so this is hermetic and the
        // real OANDA practice endpoint is never contacted — zero requests.
        try (AutoCloseable ignored = OrderTripwire.allowOrdersForTestingOnly()) {
            var client = new HttpOandaRestClient("token", "123", "https://api-fxpractice.oanda.com/v3/");
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.placeMarketOrder("EUR_USD", -1000, "my-tag"));
            assertTrue(ex.getMessage().contains("local destination"),
                    "refusal must name the local-destination rule: " + ex.getMessage());
        }
    }
}
