package com.martinfou.trading.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.TimeConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class OandaPriceClient {
    private static final Logger log = LoggerFactory.getLogger(OandaPriceClient.class);

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

    public record Price(double bid, double ask, double closeoutBid, double closeoutAsk, Instant time) {}

    public record AccountSummary(String id, double balance, double NAV, double unrealizedPL) {}

    public Price getPrice(String instrument) throws OandaApiException {
        var json = get("/accounts/" + accountId + "/pricing?instruments=" + instrument);
        if (json == null || !json.has("prices")) {
            throw new OandaApiException("Invalid OANDA response: missing 'prices' node");
        }
        var pricesNode = json.get("prices");
        if (pricesNode == null || !pricesNode.isArray() || pricesNode.size() == 0) {
            throw new OandaApiException("Invalid OANDA response: 'prices' is empty or not an array");
        }
        var prices = pricesNode.get(0);
        if (prices == null) {
            throw new OandaApiException("Invalid OANDA response: price element is null");
        }
        if (!prices.has("bids") || !prices.get("bids").isArray() || prices.get("bids").size() == 0) {
            throw new OandaApiException("Invalid OANDA response: missing or empty bids");
        }
        if (!prices.has("asks") || !prices.get("asks").isArray() || prices.get("asks").size() == 0) {
            throw new OandaApiException("Invalid OANDA response: missing or empty asks");
        }
        if (!prices.has("closeoutBid")) {
            throw new OandaApiException("Invalid OANDA response: missing closeoutBid");
        }
        if (!prices.has("closeoutAsk")) {
            throw new OandaApiException("Invalid OANDA response: missing closeoutAsk");
        }
        if (!prices.has("time")) {
            throw new OandaApiException("Invalid OANDA response: missing time");
        }

        try {
            return new Price(
                prices.get("bids").get(0).get("price").asDouble(),
                prices.get("asks").get(0).get("price").asDouble(),
                prices.get("closeoutBid").asDouble(),
                prices.get("closeoutAsk").asDouble(),
                TimeConventions.parseOandaTimestamp(prices.get("time").asText())
            );
        } catch (Exception e) {
            throw new OandaApiException("Failed to parse pricing fields from OANDA response", e);
        }
    }

    public List<Bar> getCandles(String instrument, String granularity, int count) throws OandaApiException {
        return getCandlesBefore(instrument, granularity, count, null);
    }

    public List<Bar> getCandlesBefore(String instrument, String granularity, int count, Instant to) throws OandaApiException {
        String url = "/accounts/" + accountId + "/instruments/" + instrument
            + "/candles?granularity=" + granularity + "&count=" + count;
        if (to != null) {
            try {
                url += "&to=" + java.net.URLEncoder.encode(to.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new OandaApiException("Failed to encode 'to' timestamp: " + to, e);
            }
        }
        var json = get(url);
        if (json == null || !json.has("candles")) {
            throw new OandaApiException("Invalid OANDA response: missing 'candles' node");
        }
        var candles = json.get("candles");
        if (candles == null || !candles.isArray()) {
            throw new OandaApiException("Invalid OANDA response: 'candles' is empty or not an array");
        }
        List<Bar> bars = new ArrayList<>();

        for (var c : candles) {
            if (c == null) continue;
            if (!c.has("complete") || !c.get("complete").asBoolean()) continue;
            if (!c.has("mid") || !c.has("time") || !c.has("volume")) {
                throw new OandaApiException("Invalid OANDA response: candle missing required fields");
            }
            var mid = c.get("mid");
            if (mid == null || !mid.has("o") || !mid.has("h") || !mid.has("l") || !mid.has("c")) {
                throw new OandaApiException("Invalid OANDA response: candle mid missing pricing fields");
            }
            try {
                Instant time = TimeConventions.parseOandaTimestamp(c.get("time").asText());
                bars.add(new Bar(instrument, time,
                    Double.parseDouble(mid.get("o").asText()),
                    Double.parseDouble(mid.get("h").asText()),
                    Double.parseDouble(mid.get("l").asText()),
                    Double.parseDouble(mid.get("c").asText()),
                    c.get("volume").asLong()
                ));
            } catch (Exception e) {
                throw new OandaApiException("Failed to parse candle fields from OANDA response", e);
            }
        }
        return bars;
    }

    public AccountSummary getAccountSummary() throws OandaApiException {
        var json = get("/accounts/" + accountId + "/summary");
        if (json == null || !json.has("account")) {
            throw new OandaApiException("Invalid OANDA response: missing 'account' node");
        }
        var account = json.get("account");
        if (account == null || !account.has("id") || !account.has("balance") || !account.has("NAV") || !account.has("unrealizedPL")) {
            throw new OandaApiException("Invalid OANDA response: 'account' node is missing fields");
        }
        try {
            return new AccountSummary(
                account.get("id").asText(),
                Double.parseDouble(account.get("balance").asText()),
                Double.parseDouble(account.get("NAV").asText()),
                Double.parseDouble(account.get("unrealizedPL").asText())
            );
        } catch (Exception e) {
            throw new OandaApiException("Failed to parse account summary fields from OANDA response", e);
        }
    }

    private JsonNode get(String path) throws OandaApiException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path.replaceFirst("^/", "")))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();
        
        try {
            com.martinfou.trading.data.oanda.OandaRateLimiter.GLOBAL.acquire(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OandaApiException("Price request interrupted while waiting for rate limit token", e);
        }
        
        long startTime = System.nanoTime();
        String responseBody = null;
        try {
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } finally {
                long durationNs = System.nanoTime() - startTime;
                com.martinfou.trading.core.metrics.LatencyTelemetry.getOandaLatencyBuffer().add(durationNs / 1_000_000.0);
            }
            responseBody = response.body();
            if (response.statusCode() != 200) {
                String sanitizedBody = (responseBody != null && apiKey != null && !apiKey.isEmpty())
                    ? responseBody.replace(apiKey, "[REDACTED]")
                    : (responseBody != null ? responseBody : "");
                log.error("OANDA API returned error status {}: {}", response.statusCode(), sanitizedBody);
                throw new OandaApiException("OANDA API returned error status " + response.statusCode());
            }
            return mapper.readTree(responseBody);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            String sanitizedBody = (responseBody != null && apiKey != null && !apiKey.isEmpty())
                ? responseBody.replace(apiKey, "[REDACTED]")
                : (responseBody != null ? responseBody : "");
            log.error("Failed to parse OANDA JSON response. Response: {}", sanitizedBody, e);
            throw new OandaApiException("OANDA JSON response parsing failed", e);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("OANDA HTTP request failed for path: {}", path, e);
            throw new OandaApiException("OANDA connection failed for path: " + path, e);
        } catch (OandaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error in OANDA get", e);
            throw new OandaApiException("OANDA client unexpected error", e);
        }
    }
}
