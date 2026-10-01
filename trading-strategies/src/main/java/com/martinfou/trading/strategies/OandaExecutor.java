package com.martinfou.trading.strategies;

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

public class OandaExecutor {
    private static final Logger log = LoggerFactory.getLogger(OandaExecutor.class);
    private final String apiKey, accountId, baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OandaExecutor(String apiKey, String accountId, boolean practice) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.baseUrl = practice ? "https://api-fxpractice.oanda.com/v3/" : "https://api-fxtrade.oanda.com/v3/";
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public record OrderResult(String orderId, String tradeId, String fillPrice, String status) {}

    /** Ajoute des clientExtensions pour identifier la stratégie dans OANDA. */
    private java.util.Map<String, Object> clientExtensions(String tag) {
        return new java.util.HashMap<>() {{
            put("clientExtensions", new java.util.HashMap<>() {{
                put("tag", tag);
                put("comment", "Stratégie: " + tag);
            }});
        }};
    }

    public OrderResult placeMarketOrder(String instrument, String units, String tag) throws Exception {
        return placeMarketOrder(instrument, units, tag, false);
    }

    public OrderResult placeMarketOrder(String instrument, String units, String tag, boolean reduceOnly) throws Exception {
        return placeMarketOrder(instrument, units, tag, reduceOnly, null, null);
    }

    /**
     * Story 1.7: a market order that carries its stop/target in the SAME order body
     * ({@code stopLossOnFill}/{@code takeProfitOnFill}) instead of a second call after the fill.
     * The prices arrive already formatted by the caller (see
     * {@code LiveStrategyRunner.formatPrice}: 3 decimals for JPY, 1 for metals, 5 otherwise), so no
     * {@code %.5f} hard-coding is reintroduced here.
     */
    public OrderResult placeMarketOrder(String instrument, String units, String tag, boolean reduceOnly,
                                        String stopLossOnFill, String takeProfitOnFill) throws Exception {
        var orderBody = new java.util.LinkedHashMap<String, Object>() {{
            put("type", "MARKET");
            put("instrument", instrument);
            put("units", units);
            put("timeInForce", "FOK");
            putAll(clientExtensions(tag));
            if (reduceOnly) {
                put("positionFill", "REDUCE_ONLY");
            }
            if (stopLossOnFill != null) {
                put("stopLossOnFill", new java.util.HashMap<>() {{ put("price", stopLossOnFill); }});
            }
            if (takeProfitOnFill != null) {
                put("takeProfitOnFill", new java.util.HashMap<>() {{ put("price", takeProfitOnFill); }});
            }
        }};
        String body = mapper.writeValueAsString(new java.util.HashMap<>() {{
            put("order", orderBody);
        }});

        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();

        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        var json = mapper.readTree(resp.body());
        
        if (resp.statusCode() != 201) {
            String err = json.has("errorMessage") ? json.get("errorMessage").asText() : resp.body();
            throw new RuntimeException("OANDA order failed: " + err);
        }

        var orderFill = json.get("orderFillTransaction");
        return new OrderResult(
            json.get("orderCreateTransaction").get("id").asText(),
            orderFill != null ? orderFill.get("tradeOpened").get("tradeID").asText() : "N/A",
            orderFill != null ? orderFill.get("price").asText() : "N/A",
            "FILLED"
        );
    }

    public record StopOrderResult(String orderId, String status, String price) {}

    public StopOrderResult placeStopOrder(String instrument, String units, String price, String tag) throws Exception {
        return placeStopOrder(instrument, units, price, tag, false, null, null);
    }

    public StopOrderResult placeStopOrder(String instrument, String units, String price, String tag,
                                          boolean reduceOnly, String stopLossOnFill, String takeProfitOnFill) throws Exception {
        var orderBody = new java.util.LinkedHashMap<String, Object>();
        orderBody.put("type", "STOP");
        orderBody.put("instrument", instrument);
        orderBody.put("units", units);
        orderBody.put("price", price);
        orderBody.put("timeInForce", "GTC");
        orderBody.putAll(clientExtensions(tag));
        if (reduceOnly) {
            orderBody.put("positionFill", "REDUCE_ONLY");
        }
        if (stopLossOnFill != null) {
            orderBody.put("stopLossOnFill", new java.util.HashMap<>() {{ put("price", stopLossOnFill); }});
        }
        if (takeProfitOnFill != null) {
            orderBody.put("takeProfitOnFill", new java.util.HashMap<>() {{ put("price", takeProfitOnFill); }});
        }
        String body = mapper.writeValueAsString(new java.util.HashMap<>() {{
            put("order", orderBody);
        }});

        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();

        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        var json = mapper.readTree(resp.body());

        if (resp.statusCode() != 201) {
            String err = json.has("errorMessage") ? json.get("errorMessage").asText() : resp.body();
            throw new RuntimeException("OANDA stop order failed: " + err);
        }

        return new StopOrderResult(
            json.get("orderCreateTransaction").get("id").asText(),
            "PENDING",
            json.get("orderCreateTransaction").get("price").asText()
        );
    }

    public String addStopLoss(String tradeId, String price, String tag) throws Exception {
        String body = "{\"order\":{\"type\":\"STOP_LOSS\",\"tradeID\":\"" 
            + tradeId + "\",\"price\":\"" + price + "\",\"clientExtensions\":{\"tag\":\"" + tag + "\"}}}";
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();
        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 201 ? "OK" : "FAILED: " + resp.body();
    }

    public String addTakeProfit(String tradeId, String price, String tag) throws Exception {
        String body = "{\"order\":{\"type\":\"TAKE_PROFIT\",\"tradeID\":\"" 
            + tradeId + "\",\"price\":\"" + price + "\",\"clientExtensions\":{\"tag\":\"" + tag + "\"}}}";
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();
        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 201 ? "OK" : "FAILED: " + resp.body();
    }

    public java.util.Set<String> getOpenTradeIds() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/openTrades"))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch open trades: status " + resp.statusCode() + ", body " + resp.body());
        }

        var json = mapper.readTree(resp.body());
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (json.has("trades")) {
            for (var trade : json.get("trades")) {
                if (trade.has("id")) {
                    ids.add(trade.get("id").asText());
                }
            }
        }
        return ids;
    }

    /**
     * Total {@code |currentUnits|} currently open at the broker for {@code instrument} on the side a
     * {@code closingSide} order would reduce (a BUY close reduces shorts, a SELL close reduces
     * longs). One GET {@code /openTrades} call. Returns 0 when no such position is open or the units
     * cannot be read — callers treat 0 as "nothing to close".
     */
    public double getOpenPositionUnits(String instrument, Order.Side closingSide) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/openTrades"))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch open trades: status " + resp.statusCode() + ", body " + resp.body());
        }

        var json = mapper.readTree(resp.body());
        double total = 0;
        if (json.has("trades")) {
            for (var trade : json.get("trades")) {
                if (!instrument.equals(trade.path("instrument").asText())) continue;
                double currentUnits = 0;
                try {
                    currentUnits = trade.has("currentUnits")
                        ? Double.parseDouble(trade.path("currentUnits").asText()) : 0;
                } catch (NumberFormatException ignored) {
                    continue;
                }
                boolean isLong = currentUnits > 0;
                boolean reduced = (closingSide == Order.Side.BUY && !isLong && currentUnits < 0)
                    || (closingSide == Order.Side.SELL && isLong);
                if (reduced) {
                    total += Math.abs(currentUnits);
                }
            }
        }
        return total;
    }

    public JsonNode getTradeDetails(String tradeId) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/trades/" + tradeId))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(false); // low priority for reconciliation
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch trade details: status " + resp.statusCode() + ", body " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: OandaExecutor <apiKey> <accountId> <instrument> <units>");
            System.out.println("  units positive = BUY, negative = SELL");
            return;
        }
        var exec = new OandaExecutor(args[0], args[1], true);
        var result = exec.placeMarketOrder(args[2], args[3], "TEST");
        System.out.println("Trade executed: " + result);
    }
}
