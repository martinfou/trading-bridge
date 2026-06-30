package com.martinfou.trading.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class OandaPriceClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> responsePayload = new AtomicReference<>("");
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] responseBytes = responsePayload.get().getBytes(StandardCharsets.UTF_8);
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

    private OandaPriceClient createClient() {
        return createClient("fake_key");
    }

    private OandaPriceClient createClient(String key) {
        // Override baseUrl to test locally
        OandaPriceClient client = new OandaPriceClient(key, "123", true) {
            // Using a subclass to hook custom baseUrl
        };
        // Reflection override of baseUrl
        try {
            java.lang.reflect.Field field = OandaPriceClient.class.getDeclaredField("baseUrl");
            field.setAccessible(true);
            field.set(client, "http://localhost:" + port + "/");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return client;
    }

    @Test
    void getPrice_validResponse_returnsPrice() throws Exception {
        responseStatus.set(200);
        responsePayload.set("{\n" +
                "  \"prices\": [\n" +
                "    {\n" +
                "      \"instrument\": \"EUR_USD\",\n" +
                "      \"time\": \"2026-06-29T23:00:00Z\",\n" +
                "      \"bids\": [{\"price\": \"1.0850\"}],\n" +
                "      \"asks\": [{\"price\": \"1.0852\"}],\n" +
                "      \"closeoutBid\": \"1.0849\",\n" +
                "      \"closeoutAsk\": \"1.0853\"\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        OandaPriceClient client = createClient();
        OandaPriceClient.Price price = client.getPrice("EUR_USD");
        assertNotNull(price);
        assertEquals(1.0850, price.bid());
        assertEquals(1.0852, price.ask());
        assertEquals(1.0849, price.closeoutBid());
        assertEquals(1.0853, price.closeoutAsk());
    }

    @Test
    void getPrice_missingPricesNode_throwsOandaApiException() {
        responseStatus.set(200);
        responsePayload.set("{}");

        OandaPriceClient client = createClient();
        assertThrows(OandaApiException.class, () -> client.getPrice("EUR_USD"));
    }

    @Test
    void getPrice_emptyPricesArray_throwsOandaApiException() {
        responseStatus.set(200);
        responsePayload.set("{\"prices\":[]}");

        OandaPriceClient client = createClient();
        assertThrows(OandaApiException.class, () -> client.getPrice("EUR_USD"));
    }

    @Test
    void getPrice_missingBids_throwsOandaApiException() {
        responseStatus.set(200);
        responsePayload.set("{\n" +
                "  \"prices\": [\n" +
                "    {\n" +
                "      \"instrument\": \"EUR_USD\",\n" +
                "      \"time\": \"2026-06-29T23:00:00Z\",\n" +
                "      \"asks\": [{\"price\": \"1.0852\"}],\n" +
                "      \"closeoutBid\": \"1.0849\",\n" +
                "      \"closeoutAsk\": \"1.0853\"\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        OandaPriceClient client = createClient();
        assertThrows(OandaApiException.class, () -> client.getPrice("EUR_USD"));
    }

    @Test
    void getPrice_httpError_throwsOandaApiException() {
        responseStatus.set(500);
        responsePayload.set("Internal Server Error");

        OandaPriceClient client = createClient();
        assertThrows(OandaApiException.class, () -> client.getPrice("EUR_USD"));
    }

    @Test
    void getCandles_validResponse_returnsBars() throws Exception {
        responseStatus.set(200);
        responsePayload.set("{\n" +
                "  \"candles\": [\n" +
                "    {\n" +
                "      \"complete\": true,\n" +
                "      \"volume\": 100,\n" +
                "      \"time\": \"2026-06-29T23:00:00Z\",\n" +
                "      \"mid\": {\n" +
                "        \"o\": \"1.0850\",\n" +
                "        \"h\": \"1.0860\",\n" +
                "        \"l\": \"1.0840\",\n" +
                "        \"c\": \"1.0855\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        OandaPriceClient client = createClient();
        var candles = client.getCandles("EUR_USD", "M1", 1);
        assertEquals(1, candles.size());
        assertEquals(1.0850, candles.get(0).open());
        assertEquals(1.0860, candles.get(0).high());
        assertEquals(1.0840, candles.get(0).low());
        assertEquals(1.0855, candles.get(0).close());
        assertEquals(100L, candles.get(0).volume());
    }

    @Test
    void getAccountSummary_missingFields_throwsOandaApiException() {
        responseStatus.set(200);
        responsePayload.set("{\"account\":{\"id\":\"123\"}}");

        OandaPriceClient client = createClient();
        assertThrows(OandaApiException.class, () -> client.getAccountSummary());
    }

    @Test
    void getPrice_nullApiKey_throwsOandaApiException() {
        responseStatus.set(500);
        responsePayload.set("Unauthorized API Key");

        OandaPriceClient client = createClient(null);
        assertThrows(OandaApiException.class, () -> client.getPrice("EUR_USD"));
    }
}
