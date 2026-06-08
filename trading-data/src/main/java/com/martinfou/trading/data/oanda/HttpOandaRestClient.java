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
public final class HttpOandaRestClient implements OandaRestClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOandaRestClient.class);

    private final String apiToken;
    private final String accountId;
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpOandaRestClient(String apiToken, String accountId, boolean practice) {
        this(apiToken, accountId, practice
            ? "https://api-fxpractice.oanda.com/v3/"
            : "https://api-fxtrade.oanda.com/v3/");
    }

    public HttpOandaRestClient(String apiToken, String accountId, String baseUrl) {
        this.apiToken = apiToken;
        this.accountId = accountId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
                order.put("clientExtensions", Map.of("tag", clientTag, "comment", clientTag));
            }
            String body = mapper.writeValueAsString(Map.of("order", order));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
    public OandaMarketOrderResult placeOrder(String type, String instrument, long units, double price, double stopLoss, double takeProfit, String clientTag) {
        try {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("type", type.toUpperCase());
            order.put("instrument", instrument);
            order.put("units", String.valueOf(units));
            if (type.equalsIgnoreCase("MARKET")) {
                order.put("timeInForce", "FOK");
            } else {
                order.put("timeInForce", "GTC");
                String fmt = instrument.contains("JPY") ? "%.3f" : "%.5f";
                order.put("price", String.format(java.util.Locale.US, fmt, price));
            }
            String fmt = instrument.contains("JPY") ? "%.3f" : "%.5f";
            if (stopLoss > 0) {
                Map<String, Object> sl = new LinkedHashMap<>();
                sl.put("price", String.format(java.util.Locale.US, fmt, stopLoss));
                sl.put("timeInForce", "GTC");
                order.put("stopLossOnFill", sl);
            }
            if (takeProfit > 0) {
                Map<String, Object> tp = new LinkedHashMap<>();
                tp.put("price", String.format(java.util.Locale.US, fmt, takeProfit));
                tp.put("timeInForce", "GTC");
                order.put("takeProfitOnFill", tp);
            }
            if (clientTag != null && !clientTag.isBlank()) {
                order.put("clientExtensions", Map.of("tag", clientTag, "comment", clientTag));
            }
            String body = mapper.writeValueAsString(Map.of("order", order));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
                Double.parseDouble(account.get("balance").asText()),
                Double.parseDouble(account.get("NAV").asText()),
                Double.parseDouble(account.get("unrealizedPL").asText()),
                account.get("currency").asText());
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
                String clientTag = null;
                if (trade.has("clientExtensions") && !trade.get("clientExtensions").isNull()) {
                    JsonNode ext = trade.get("clientExtensions");
                    if (ext.has("tag")) {
                        clientTag = ext.get("tag").asText();
                    }
                }
                positions.add(new OandaPositionSnapshot(
                    trade.get("instrument").asText(),
                    side,
                    Math.abs(units),
                    Double.parseDouble(trade.get("price").asText()),
                    clientTag));
            }
            return List.copyOf(positions);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch OANDA open trades", e);
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
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "OANDA API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }
}
