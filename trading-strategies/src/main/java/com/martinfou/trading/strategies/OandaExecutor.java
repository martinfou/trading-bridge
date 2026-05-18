package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public OrderResult placeMarketOrder(String instrument, String units) throws Exception {
        String body = mapper.writeValueAsString(new java.util.HashMap<>() {{
            put("order", new java.util.HashMap<>() {{
                put("type", "MARKET");
                put("instrument", instrument);
                put("units", units);
                put("timeInForce", "FOK");
            }});
        }});

        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();

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

    public String addStopLoss(String tradeId, String price) throws Exception {
        String body = "{\"order\":{\"type\":\"STOP_LOSS\",\"tradeID\":\"" 
            + tradeId + "\",\"price\":\"" + price + "\"}}";
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 201 ? "OK" : "FAILED: " + resp.body();
    }

    public String addTakeProfit(String tradeId, String price) throws Exception {
        String body = "{\"order\":{\"type\":\"TAKE_PROFIT\",\"tradeID\":\"" 
            + tradeId + "\",\"price\":\"" + price + "\"}}";
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "accounts/" + accountId + "/orders"))
            .header("Authorization", "B" + "earer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 201 ? "OK" : "FAILED: " + resp.body();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: OandaExecutor <apiKey> <accountId> <instrument> <units>");
            System.out.println("  units positive = BUY, negative = SELL");
            return;
        }
        var exec = new OandaExecutor(args[0], args[1], true);
        var result = exec.placeMarketOrder(args[2], args[3]);
        System.out.println("Trade executed: " + result);
    }
}
