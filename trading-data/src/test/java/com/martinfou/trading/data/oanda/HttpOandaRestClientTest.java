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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    static class TestHttpOandaRestClient extends HttpOandaRestClient {
        final java.util.concurrent.atomic.AtomicInteger buildCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger sendCount = new java.util.concurrent.atomic.AtomicInteger(0);
        private HttpClient realClient;

        TestHttpOandaRestClient(String apiToken, String accountId, String baseUrl, HttpClient realClient) {
            super(apiToken, accountId, baseUrl);
            this.realClient = realClient;
            this.client = wrap(realClient);
            this.initialBackoffMs = 1;
        }

        @Override
        HttpClient buildHttpClient() {
            if (buildCount != null) {
                buildCount.incrementAndGet();
            }
            if (realClient == null) {
                return super.buildHttpClient();
            }
            return wrap(realClient);
        }

        private HttpClient wrap(HttpClient delegate) {
            return new HttpClient() {
                @Override
                public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
                    sendCount.incrementAndGet();
                    return delegate.send(request, responseBodyHandler);
                }

                @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
                @Override public java.util.Optional<java.time.Duration> connectTimeout() { return delegate.connectTimeout(); }
                @Override public Redirect followRedirects() { return delegate.followRedirects(); }
                @Override public java.util.Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
                @Override public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
                @Override public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
                @Override public java.util.Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
                @Override public Version version() { return delegate.version(); }
                @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
                @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) { return delegate.sendAsync(request, responseBodyHandler); }
                @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler); }
            };
        }
    }

    @Test
    void fetchAccountSummary_transientConnectionFailure_retriesAndSucceeds() throws Exception {
        HttpServer testServer = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger(0);
        testServer.createContext("/accounts/123/summary", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int count = requestCount.incrementAndGet();
                if (count <= 3) {
                    exchange.close();
                } else {
                    String responseBody = "{\"account\":{\"balance\":\"10000.0\",\"NAV\":\"10000.0\",\"unrealizedPL\":\"0.0\",\"currency\":\"USD\",\"marginAvailable\":\"10000.0\",\"marginUsed\":\"0.0\",\"marginCloseoutPercent\":\"0.0\"}}";
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
        });
        testServer.start();
        int testPort = testServer.getAddress().getPort();
        try {
            HttpClient baseClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var client = new TestHttpOandaRestClient("token", "123", "http://localhost:" + testPort + "/", baseClient);

            OandaAccountSnapshot summary = client.fetchAccountSummary();
            assertNotNull(summary);
            assertEquals(10000.0, summary.balance());

            assertEquals(2, client.sendCount.get());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void fetchAccountSummary_permanentConnectionFailure_throwsException() throws Exception {
        HttpServer testServer = HttpServer.create(new InetSocketAddress(0), 0);
        testServer.createContext("/accounts/123/summary", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.close();
            }
        });
        testServer.start();
        int testPort = testServer.getAddress().getPort();
        try {
            HttpClient baseClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var client = new TestHttpOandaRestClient("token", "123", "http://localhost:" + testPort + "/", baseClient);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
                client.fetchAccountSummary();
            });

            assertEquals(8, client.sendCount.get());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void placeMarketOrder_transientConnectionFailure_retriesAtMostTwice() throws Exception {
        HttpServer testServer = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger(0);
        testServer.createContext("/accounts/123/orders", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int count = requestCount.incrementAndGet();
                if (count <= 2) {
                    exchange.close();
                } else {
                    String responseBody = "{\n" +
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
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
        });
        testServer.start();
        int testPort = testServer.getAddress().getPort();
        try {
            HttpClient baseClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var client = new TestHttpOandaRestClient("token", "123", "http://localhost:" + testPort + "/", baseClient);

            OandaMarketOrderResult result = client.placeMarketOrder("EUR_USD", 1000, "my-tag-123");

            assertNotNull(result);
            org.junit.jupiter.api.Assertions.assertFalse(result.success());

            assertEquals(1, client.sendCount.get());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void placeMarketOrder_connectException_retriesTwice() throws Exception {
        int unusedPort = 54321;
        HttpClient baseClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var client = new TestHttpOandaRestClient("token", "123", "http://localhost:" + unusedPort + "/", baseClient);

        OandaMarketOrderResult result = client.placeMarketOrder("EUR_USD", 1000, "my-tag-123");

        assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertFalse(result.success());

        assertEquals(2, client.sendCount.get());
    }
}
