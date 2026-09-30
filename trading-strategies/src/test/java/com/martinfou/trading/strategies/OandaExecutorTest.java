package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the STOP-order path (FIX 1b) and the broker-truth position read (FIX 4):
 * a close-only STOP must carry {@code positionFill: REDUCE_ONLY} (never open a new position on a
 * hedging account), an entry STOP may carry {@code stopLossOnFill}/{@code takeProfitOnFill}, and
 * {@code getOpenPositionUnits} must sum only the side the close order reduces.
 */
class OandaExecutorTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> responsePayload = new AtomicReference<>("");
    private final java.util.concurrent.atomic.AtomicInteger responseStatus = new java.util.concurrent.atomic.AtomicInteger(201);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                try (InputStream in = exchange.getRequestBody()) {
                    requestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] responseBytes = responsePayload.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus.get(), responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
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

    private OandaExecutor createExecutor() {
        OandaExecutor exec = new OandaExecutor("fake-key", "123", true);
        try {
            java.lang.reflect.Field field = OandaExecutor.class.getDeclaredField("baseUrl");
            field.setAccessible(true);
            field.set(exec, "http://localhost:" + port + "/");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return exec;
    }

    private JsonNode sentOrder() throws Exception {
        JsonNode root = new ObjectMapper().readTree(requestBody.get());
        return root.get("order");
    }

    @Test
    void closeOnlyStopSetsReduceOnlyAndNoSlTp() throws Exception {
        responsePayload.set("{\"orderCreateTransaction\":{\"id\":\"1\",\"price\":\"207.500\"}}");
        OandaExecutor exec = createExecutor();

        exec.placeStopOrder("GBP_JPY", "1000", "207.500", "TAG", true, null, null);

        JsonNode order = sentOrder();
        assertEquals("REDUCE_ONLY", order.get("positionFill").asText(),
            "a close-only STOP must be REDUCE_ONLY or it opens a new position on a hedging account");
        assertFalse(order.has("stopLossOnFill"), "close-only STOP carries no SL on fill");
        assertFalse(order.has("takeProfitOnFill"), "close-only STOP carries no TP on fill");
    }

    @Test
    void entryStopCarriesSlAndTpOnFillButNotReduceOnly() throws Exception {
        responsePayload.set("{\"orderCreateTransaction\":{\"id\":\"2\",\"price\":\"207.500\"}}");
        OandaExecutor exec = createExecutor();

        exec.placeStopOrder("GBP_JPY", "1000", "207.500", "TAG", false, "206.000", "209.000");

        JsonNode order = sentOrder();
        assertFalse(order.has("positionFill"), "an entry STOP is not reduce-only");
        assertEquals("206.000", order.get("stopLossOnFill").get("price").asText());
        assertEquals("209.000", order.get("takeProfitOnFill").get("price").asText());
    }

    @Test
    void fourArgPlaceStopOrderDelegatesWithoutReduceOnly() throws Exception {
        responsePayload.set("{\"orderCreateTransaction\":{\"id\":\"3\",\"price\":\"207.500\"}}");
        OandaExecutor exec = createExecutor();

        exec.placeStopOrder("GBP_JPY", "1000", "207.500", "TAG");

        JsonNode order = sentOrder();
        assertFalse(order.has("positionFill"), "the legacy 4-arg signature stays a plain STOP");
        assertFalse(order.has("stopLossOnFill"));
        assertFalse(order.has("takeProfitOnFill"));
        assertEquals("STOP", order.get("type").asText());
    }

    @Test
    void openPositionUnitsSumsOnlyTheSideTheCloseReduces() throws Exception {
        responseStatus.set(200);
        responsePayload.set("""
            {
              "trades": [
                { "id": "1", "instrument": "GBP_JPY", "currentUnits": "48000" },
                { "id": "2", "instrument": "GBP_JPY", "currentUnits": "-12000" },
                { "id": "3", "instrument": "EUR_USD", "currentUnits": "5000" }
              ]
            }
            """);
        OandaExecutor exec = createExecutor();

        assertEquals(12_000, exec.getOpenPositionUnits("GBP_JPY", Order.Side.BUY), 1e-9,
            "a BUY close reduces the short side (12,000)");
        assertEquals(48_000, exec.getOpenPositionUnits("GBP_JPY", Order.Side.SELL), 1e-9,
            "a SELL close reduces the long side (48,000)");
        assertEquals(5_000, exec.getOpenPositionUnits("EUR_USD", Order.Side.SELL), 1e-9,
            "a SELL close of EUR_USD reduces its long side (5,000)");
        assertEquals(0, exec.getOpenPositionUnits("EUR_USD", Order.Side.BUY), 1e-9,
            "a BUY close of EUR_USD has no short side to reduce");
    }
}
