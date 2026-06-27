package com.martinfou.trading.data.oanda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live HTTP client for OANDA v20 REST API (practice or live). */
public class HttpOandaRestClient implements OandaRestClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOandaRestClient.class);

    private final String apiToken;
    private final String accountId;
    private final String baseUrl;
    volatile HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, OandaInstrument> instrumentCache = new java.util.concurrent.ConcurrentHashMap<>();
    long initialBackoffMs = 1000;

    private OandaInstrument getInstrument(String name) {
        if (instrumentCache.isEmpty()) {
            synchronized (instrumentCache) {
                if (instrumentCache.isEmpty()) {
                    try {
                        JsonNode json = get("/accounts/" + accountId + "/instruments");
                        for (JsonNode inst : json.get("instruments")) {
                            instrumentCache.put(inst.get("name").asText(), new OandaInstrument(
                                inst.get("name").asText(),
                                inst.get("type").asText(),
                                inst.get("displayPrecision").asInt(),
                                inst.get("pipLocation").asInt(),
                                inst.has("marginRate") ? inst.get("marginRate").asDouble() : 0.0
                            ));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch instruments: {}", e.getMessage());
                    }
                }
            }
        }
        return instrumentCache.getOrDefault(name, new OandaInstrument(name, "UNKNOWN", name.contains("JPY") ? 3 : 5, name.contains("JPY") ? -2 : -4, 0.05));
    }

    public HttpOandaRestClient(String apiToken, String accountId, boolean practice) {
        this(apiToken, accountId, practice
            ? "https://api-fxpractice.oanda.com/v3/"
            : "https://api-fxtrade.oanda.com/v3/");
    }

    public HttpOandaRestClient(String apiToken, String accountId, String baseUrl) {
        this.apiToken = apiToken;
        this.accountId = accountId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.client = buildHttpClient();
    }

    HttpClient buildHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Sends {@code request} using the shared {@link HttpClient}, with one automatic retry if the
     * connection is closed by an HTTP/2 GOAWAY frame (or equivalent TCP reset) from an intermediate
     * proxy or from OANDA itself.
     *
     * <p>On a GOAWAY the existing HTTP/2 connection pool is no longer usable. We recreate the
     * {@link HttpClient} (which opens a fresh TCP + TLS connection on the next call) and replay the
     * request exactly once.  Any other {@link IOException} — or a second consecutive failure — is
     * rethrown so the caller can decide how to handle it.
     */
    private <T> HttpResponse<T> sendWithRetry(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        boolean isGet = request.method().equalsIgnoreCase("GET");
        int maxAttempts = isGet ? 8 : 2;
        long backoffMs = initialBackoffMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return client.send(request, handler);
            } catch (java.io.IOException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }

                boolean shouldRetry = false;
                if (isGet) {
                    shouldRetry = true;
                } else {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    shouldRetry = msg.contains("goaway")
                        || msg.contains("reset")
                        || msg.contains("connection")
                        || msg.contains("eof")
                        || msg.contains("closed")
                        || e instanceof java.net.ConnectException;
                }

                if (!shouldRetry) {
                    throw e;
                }

                log.warn("OANDA REST — HTTP/2 connection error (attempt {}/{}): {}; rebuilding HttpClient and retrying request",
                    attempt, maxAttempts, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());

                this.client = buildHttpClient();

                if (isGet) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                    backoffMs = Math.min(backoffMs * 2, 32000);
                }
            }
        }
        throw new java.io.IOException("Request failed after " + maxAttempts + " attempts");
    }

    @Override
    public OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag) {
        try {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("type", "MARKET");
            order.put("instrument", instrument);
            order.put("units", String.valueOf(units));
            order.put("timeInForce", "FOK");
            if (clientTag != null && !clientTag.isBlank()) {
                Map<String, String> ext = Map.of("tag", clientTag, "comment", clientTag);
                order.put("clientExtensions", ext);
                order.put("tradeClientExtensions", ext);
            }
            String body = mapper.writeValueAsString(Map.of("order", order));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() != 201) {
                String err = json.has("errorMessage") ? json.get("errorMessage").asText() : response.body();
                return OandaMarketOrderResult.failure(response.statusCode(), err);
            }
            String orderId = json.get("orderCreateTransaction").get("id").asText();
            JsonNode fill = json.get("orderFillTransaction");
            if (fill == null || fill.isNull()) {
                return OandaMarketOrderResult.failure(response.statusCode(), "No fill transaction in response");
            }
            String tradeId = fill.has("tradeOpened")
                ? fill.get("tradeOpened").get("tradeID").asText()
                : null;
            double price = Double.parseDouble(fill.get("price").asText());
            return OandaMarketOrderResult.success(orderId, tradeId, price);
        } catch (Exception e) {
            log.warn("OANDA market order failed: {}", e.getMessage());
            return OandaMarketOrderResult.failure(0, e.getMessage());
        }
    }

    @Override
    public OandaMarketOrderResult placeOrder(String type, String instrument, long units, double price, double stopLoss, double takeProfit, double trailingStop, boolean guaranteed, String clientTag) {
        try {
            OandaInstrument instMeta = getInstrument(instrument);
            String fmt = "%." + instMeta.displayPrecision() + "f";
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("type", type.toUpperCase());
            order.put("instrument", instrument);
            order.put("units", String.valueOf(units));
            if (type.equalsIgnoreCase("MARKET")) {
                order.put("timeInForce", "FOK");
            } else {
                order.put("timeInForce", "GTC");
                order.put("price", String.format(java.util.Locale.US, fmt, price));
            }
            if (stopLoss > 0) {
                Map<String, Object> sl = new LinkedHashMap<>();
                sl.put("price", String.format(java.util.Locale.US, fmt, stopLoss));
                sl.put("timeInForce", "GTC");
                if (guaranteed) {
                    sl.put("guaranteed", true);
                }
                order.put("stopLossOnFill", sl);
            }
            if (takeProfit > 0) {
                Map<String, Object> tp = new LinkedHashMap<>();
                tp.put("price", String.format(java.util.Locale.US, fmt, takeProfit));
                tp.put("timeInForce", "GTC");
                order.put("takeProfitOnFill", tp);
            }
            if (trailingStop > 0) {
                Map<String, Object> ts = new LinkedHashMap<>();
                ts.put("distance", String.format(java.util.Locale.US, fmt, trailingStop));
                ts.put("timeInForce", "GTC");
                order.put("trailingStopLossOnFill", ts);
            }
            if (clientTag != null && !clientTag.isBlank()) {
                Map<String, String> ext = Map.of("tag", clientTag, "comment", clientTag);
                order.put("clientExtensions", ext);
                order.put("tradeClientExtensions", ext);
            }
            String body = mapper.writeValueAsString(Map.of("order", order));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() != 201) {
                String err = json.has("errorMessage") ? json.get("errorMessage").asText() : response.body();
                return OandaMarketOrderResult.failure(response.statusCode(), err);
            }
            String orderId = json.get("orderCreateTransaction").get("id").asText();
            String tradeId = null;
            Double fillPriceObj = null;
            if (json.has("orderFillTransaction")) {
                JsonNode fill = json.get("orderFillTransaction");
                if (fill != null && !fill.isNull()) {
                    tradeId = fill.has("tradeOpened")
                        ? fill.get("tradeOpened").get("tradeID").asText()
                        : null;
                    fillPriceObj = Double.parseDouble(fill.get("price").asText());
                }
            }
            return new OandaMarketOrderResult(response.statusCode(), orderId, tradeId, fillPriceObj, null);
        } catch (Exception e) {
            log.warn("OANDA order placement failed: {}", e.getMessage());
            return OandaMarketOrderResult.failure(0, e.getMessage());
        }
    }

    @Override
    public boolean cancelOrder(String orderId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders/" + orderId + "/cancel"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            log.warn("Failed to cancel OANDA order {}. HTTP status: {}, Response: {}", orderId, response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.warn("OANDA order cancellation failed for order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    @Override
    public double closeTrade(String tradeId, String units) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (units != null && !units.isBlank() && !units.equalsIgnoreCase("ALL")) {
                bodyMap.put("units", units);
            }
            String body = bodyMap.isEmpty() ? "{}" : mapper.writeValueAsString(bodyMap);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/trades/" + tradeId + "/close"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                try {
                    JsonNode root = mapper.readTree(response.body());
                    if (root.has("orderFillTransaction") && !root.get("orderFillTransaction").isNull()) {
                        JsonNode fill = root.get("orderFillTransaction");
                        if (fill.has("price")) {
                            return Double.parseDouble(fill.get("price").asText());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse OANDA close trade response: {}", e.getMessage());
                }
                return 0.0;
            }
            log.warn("Failed to close OANDA trade {}. HTTP status: {}, Response: {}", tradeId, response.statusCode(), response.body());
            return -1.0;
        } catch (Exception e) {
            log.warn("OANDA trade close failed for trade {}: {}", tradeId, e.getMessage());
            return -1.0;
        }
    }

    @Override
    public List<Map<String, Object>> fetchTransactions(int limit) {
        try {
            JsonNode json = get("/accounts/" + accountId + "/transactions");
            List<Map<String, Object>> list = new ArrayList<>();
            if (json.has("transactions")) {
                for (JsonNode txNode : json.get("transactions")) {
                    Map<String, Object> tx = mapper.convertValue(txNode, new TypeReference<Map<String, Object>>() {});
                    list.add(tx);
                    if (list.size() >= limit) {
                        break;
                    }
                }
            }
            return list;
        } catch (Exception e) {
            log.warn("Failed to fetch OANDA transactions: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public OandaAccountSnapshot fetchAccountSummary() {
        try {
            JsonNode json = get("/accounts/" + accountId + "/summary");
            JsonNode account = json.get("account");
            return new OandaAccountSnapshot(
                Double.parseDouble(account.path("balance").asText("0")),
                Double.parseDouble(account.path("NAV").asText("0")),
                Double.parseDouble(account.path("unrealizedPL").asText("0")),
                account.path("currency").asText(),
                Double.parseDouble(account.path("marginAvailable").asText("0")),
                Double.parseDouble(account.path("marginUsed").asText("0")),
                Double.parseDouble(account.path("marginCloseoutPercent").asText("0"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch OANDA account summary", e);
        }
    }

    @Override
    public List<OandaPositionSnapshot> fetchOpenPositions() {
        try {
            JsonNode json = get("/accounts/" + accountId + "/openTrades");
            List<OandaPositionSnapshot> positions = new ArrayList<>();
            for (JsonNode trade : json.get("trades")) {
                double units = Double.parseDouble(trade.get("currentUnits").asText());
                Order.Side side = units >= 0 ? Order.Side.BUY : Order.Side.SELL;
                String tradeId = trade.get("id").asText();
                String clientTag = null;
                if (trade.has("clientExtensions") && !trade.get("clientExtensions").isNull()) {
                    JsonNode ext = trade.get("clientExtensions");
                    if (ext.has("tag")) {
                        clientTag = ext.get("tag").asText();
                    }
                }
                java.time.Instant entryTime = null;
                if (trade.has("openTime")) {
                    entryTime = java.time.Instant.parse(trade.get("openTime").asText());
                }
                positions.add(new OandaPositionSnapshot(
                    tradeId,
                    trade.get("instrument").asText(),
                    side,
                    Math.abs(units),
                    Double.parseDouble(trade.get("price").asText()),
                    clientTag,
                    entryTime));
            }
            return List.copyOf(positions);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch OANDA open trades", e);
        }
    }

    @Override
    public Map<String, Object> fetchOrderBook(String instrument) {
        try {
            JsonNode json = get("/instruments/" + instrument + "/orderBook");
            return mapper.convertValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch OANDA order book for {}: {}", instrument, e.getMessage());
            return Map.of();
        }
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path.replaceFirst("^/", "")))
            .header("Authorization", "Bearer " + apiToken)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build();
        HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "OANDA API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    @Override
    public void reset() {
        log.info("Resetting HttpOandaRestClient's HttpClient connection pool");
        this.client = buildHttpClient();
    }
}
