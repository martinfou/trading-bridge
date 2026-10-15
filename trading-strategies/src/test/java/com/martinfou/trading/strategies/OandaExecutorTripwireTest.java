package com.martinfou.trading.strategies;

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
 * Proves the OANDA executor's four order methods refuse WITHOUT sending a single HTTP request when
 * the order tripwire is armed (no flag / test runtime). A local {@link HttpServer} counts requests:
 * the count must stay 0. This is the empirical proof that the live path cannot leak orders.
 */
class OandaExecutorTripwireTest {

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
                byte[] body = "{\"orderCreateTransaction\":{\"id\":\"1\"}}".getBytes(StandardCharsets.UTF_8);
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

    private OandaExecutor executor() {
        return new OandaExecutor("fake-key", "123", "http://localhost:" + port + "/");
    }

    @Test
    void placeMarketOrder_refusesWithoutSending() {
        assertThrows(IllegalStateException.class,
                () -> executor().placeMarketOrder("EUR_USD", "-1000", "TAG", false, "1.05", null));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }

    @Test
    void placeStopOrder_refusesWithoutSending() {
        assertThrows(IllegalStateException.class,
                () -> executor().placeStopOrder("EUR_USD", "-1000", "1.05", "TAG", false, null, null));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }

    @Test
    void addStopLoss_refusesWithoutSending() {
        assertThrows(IllegalStateException.class,
                () -> executor().addStopLoss("T123", "1.05", "TAG"));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }

    @Test
    void addTakeProfit_refusesWithoutSending() {
        assertThrows(IllegalStateException.class,
                () -> executor().addTakeProfit("T123", "1.10", "TAG"));
        assertEquals(0, requestCount.get(), "no HTTP request may be sent when the tripwire refuses");
    }
}
