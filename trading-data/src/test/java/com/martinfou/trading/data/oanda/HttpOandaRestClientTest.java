package com.martinfou.trading.data.oanda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpOandaRestClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (InputStream is = exchange.getRequestBody()) {
                        byte[] bytes = is.readAllBytes();
                        lastRequestBody.set(new String(bytes, StandardCharsets.UTF_8));
                    }
                }
                
                String responseBody;
                if (exchange.getRequestURI().getPath().endsWith("/instruments")) {
                    responseBody = "{\"instruments\":[]}";
                } else {
                    responseBody = "{\n" +
                            "  \"orderCreateTransaction\": {\n" +
                            "    \"id\": \"100\"\n" +
                            "  },\n" +
                            "  \"orderFillTransaction\": {\n" +
                            "    \"id\": \"101\",\n" +
                            "    \"price\": \"1.1000\",\n" +
                            "    \"tradeOpened\": {\n" +
                            "      \"tradeID\": \"500\"\n" +
                            "    }\n" +
                            "  }\n" +
                            "}";
                }
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, responseBytes.length);
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

    @Test
    void placeMarketOrder_propagatesTradeClientExtensions() throws Exception {
        var client = new HttpOandaRestClient("token", "123", "http://localhost:" + port + "/");
        OandaMarketOrderResult result = client.placeMarketOrder("EUR_USD", 1000, "my-tag-123");
        assertNotNull(result);
        
        String reqBody = lastRequestBody.get();
        assertNotNull(reqBody);
        
        JsonNode root = mapper.readTree(reqBody);
        JsonNode order = root.get("order");
        assertNotNull(order);
        
        assertEquals("MARKET", order.get("type").asText());
        assertEquals("EUR_USD", order.get("instrument").asText());
        assertEquals("1000", order.get("units").asText());
        
        JsonNode clientExt = order.get("clientExtensions");
        assertNotNull(clientExt);
        assertEquals("my-tag-123", clientExt.get("tag").asText());
        assertEquals("my-tag-123", clientExt.get("comment").asText());
        
        JsonNode tradeExt = order.get("tradeClientExtensions");
        assertNotNull(tradeExt);
        assertEquals("my-tag-123", tradeExt.get("tag").asText());
        assertEquals("my-tag-123", tradeExt.get("comment").asText());
    }

    @Test
    void placeOrder_propagatesTradeClientExtensions() throws Exception {
        var client = new HttpOandaRestClient("token", "123", "http://localhost:" + port + "/");
        OandaMarketOrderResult result = client.placeOrder("LIMIT", "EUR_USD", 1000, 1.10, 1.09, 1.12, 0, false, "my-tag-456");
        assertNotNull(result);
        
        String reqBody = lastRequestBody.get();
        assertNotNull(reqBody);
        
        JsonNode root = mapper.readTree(reqBody);
        JsonNode order = root.get("order");
        assertNotNull(order);
        
        assertEquals("LIMIT", order.get("type").asText());
        
        JsonNode clientExt = order.get("clientExtensions");
        assertNotNull(clientExt);
        assertEquals("my-tag-456", clientExt.get("tag").asText());
        
        JsonNode tradeExt = order.get("tradeClientExtensions");
        assertNotNull(tradeExt);
        assertEquals("my-tag-456", tradeExt.get("tag").asText());
    }
}
