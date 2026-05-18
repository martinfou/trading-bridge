package com.martinfou.trading.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class OandaPriceClient {
    private static final Logger log = LoggerFactory.getLogger(OandaPriceClient.class);
    private static final DateTimeFormatter OANDA_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    
    private final String apiKey;
    private final String accountId;
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OandaPriceClient(String apiKey, String accountId, boolean isPractice) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.baseUrl = isPractice ? "https://api-fxpractice.oanda.com/v3/" : "https://api-fxtrade.oanda.com/v3/";
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public record Price(double bid, double ask, double closeoutBid, double closeoutAsk, String time) {}
    public record AccountSummary(String id, double balance, double NAV, double unrealizedPL) {}

    public Price getPrice(String instrument) throws Exception {
        var json = get("/accounts/" + accountId + "/pricing?instruments=" + instrument);
        var prices = json.get("prices").get(0);
        return new Price(
            prices.get("bids").get(0).get("price").asDouble(),
            prices.get("asks").get(0).get("price").asDouble(),
            prices.get("closeoutBid").asDouble(),
            prices.get("closeoutAsk").asDouble(),
            prices.get("time").asText()
        );
    }

    public List<Bar> getCandles(String instrument, String granularity, int count) throws Exception {
        var json = get("/accounts/" + accountId + "/instruments/" + instrument 
            + "/candles?granularity=" + granularity + "&count=" + count);
        var candles = json.get("candles");
        List<Bar> bars = new ArrayList<>();
        
        for (var c : candles) {
            if (!c.get("complete").asBoolean()) continue;
            var mid = c.get("mid");
            var time = LocalDateTime.parse(c.get("time").asText().substring(0, 19), OANDA_DT);
            bars.add(new Bar(instrument, time,
                Double.parseDouble(mid.get("o").asText()),
                Double.parseDouble(mid.get("h").asText()),
                Double.parseDouble(mid.get("l").asText()),
                Double.parseDouble(mid.get("c").asText()),
                c.get("volume").asLong()
            ));
        }
        return bars;
    }

    public AccountSummary getAccountSummary() throws Exception {
        var json = get("/accounts/" + accountId + "/summary");
        return new AccountSummary(
            json.get("account").get("id").asText(),
            Double.parseDouble(json.get("account").get("balance").asText()),
            Double.parseDouble(json.get("account").get("NAV").asText()),
            Double.parseDouble(json.get("account").get("unrealizedPL").asText())
        );
    }

    private JsonNode get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path.replaceFirst("^/", "")))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OANDA API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }
}
