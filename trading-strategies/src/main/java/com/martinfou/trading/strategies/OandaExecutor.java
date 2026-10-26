package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.guardrails.OrderTripwire;
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
        this(apiKey, accountId, practice ? "https://api-fxpractice.oanda.com/v3/" : "https://api-fxtrade.oanda.com/v3/");
    }

    /** Test seam: explicit base URL so tests can point the executor at a local stub. */
    OandaExecutor(String apiKey, String accountId, String baseUrl) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Result of a MARKET order submission.
     *
     * <p>A fill is <b>explicit</b>: {@code tradeId}/{@code fillPrice} are non-null only when the order
     * actually filled. An order that was created and then cancelled (a FOK MARKET order with no
     * liquidity, a market halt, …) returns {@code tradeId == null} with a {@code status} of
     * {@code CANCELLED} and the broker's {@code orderCancelTransaction.reason} in {@code cancelReason}.
     * There is deliberately no {@code "N/A"} sentinel: callers test {@link #isFilled()} instead of
     * comparing strings.
     */
    public record OrderResult(String orderId, String tradeId, String fillPrice, String status, String cancelReason) {
        /**
         * True when the broker returned a fill for this order. A close fills too, and it carries no
         * trade id, so this is NOT the condition for counting an entry.
         */
        public boolean isFilled() {
            return "FILLED".equals(status);
        }

        /**
         * True only when the fill OPENED a trade: the single condition a caller may use to count an
         * entry or to track a position. An entry that filled but opened nothing, and an order that
         * was created then cancelled, both return false.
         */
        public boolean openedTrade() {
            return tradeId != null;
        }
    }

    /** Les clientExtensions de l'ORDRE (valides sur tous les ordres, y compris reduce-only). */
    private java.util.Map<String, Object> clientExtensions(String tag) {
        java.util.Map<String, Object> ext = new java.util.HashMap<>() {{
            put("tag", tag);
            put("comment", "Stratégie: " + tag);
        }};
        return new java.util.HashMap<>() {{
            put("clientExtensions", ext);
        }};
    }

    /**
     * Les clientExtensions du TRADE. C'est ce que {@code GET /openTrades} renvoie comme
     * {@code clientExtensions} du trade, donc la seule forme qui rend une position attribuable
     * à une stratégie après coup (story 50.1 L1.4).
     *
     * <p>À n'envoyer QUE sur un ordre capable d'ouvrir un trade. Sur un ordre
     * {@code positionFill: REDUCE_ONLY} aucun trade n'est créé, et OANDA refuse alors le champ :
     * l'envoyer sur les fermetures ferait échouer des closes, ce qui est pire que le défaut
     * d'origine. Vérifié au code : les closes passent par {@code placeMarketOrder(..., true)}
     * (LiveStrategyRunner:1516 et :1897).
     */
    private java.util.Map<String, Object> tradeClientExtensions(String tag) {
        java.util.Map<String, Object> ext = new java.util.HashMap<>() {{
            put("tag", tag);
            put("comment", "Stratégie: " + tag);
        }};
        return new java.util.HashMap<>() {{
            put("tradeClientExtensions", ext);
        }};
    }

    /** Builds the MARKET order JSON body (extracted so the shape is testable without sending). */
    String buildMarketOrderBody(String instrument, String units, String tag, boolean reduceOnly,
                                String stopLossOnFill, String takeProfitOnFill) throws Exception {
        var orderBody = new java.util.LinkedHashMap<String, Object>() {{
            put("type", "MARKET");
            put("instrument", instrument);
            put("units", units);
            put("timeInForce", "FOK");
            putAll(clientExtensions(tag));
            if (!reduceOnly) {
                // Only an order that can open a trade carries trade-level extensions.
                putAll(tradeClientExtensions(tag));
            }
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
        return mapper.writeValueAsString(new java.util.HashMap<>() {{
            put("order", orderBody);
        }});
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
        OrderTripwire.checkOrderAllowed(instrument, units, "OandaExecutor.placeMarketOrder", baseUrl);
        String body = buildMarketOrderBody(instrument, units, tag, reduceOnly, stopLossOnFill, takeProfitOnFill);

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

        String orderId = json.get("orderCreateTransaction").get("id").asText();

        // Filled: the fill transaction carries the trade id and the fill price.
        var orderFill = json.get("orderFillTransaction");
        if (orderFill != null) {
            var tradeOpened = orderFill.get("tradeOpened");
            String tradeId = tradeOpened != null && tradeOpened.has("tradeID")
                ? tradeOpened.get("tradeID").asText() : null;
            String fillPrice = orderFill.has("price") ? orderFill.get("price").asText() : null;
            return new OrderResult(orderId, tradeId, fillPrice, "FILLED", null);
        }

        // No fill transaction: the order was created and then cancelled (a FOK MARKET order with no
        // liquidity, a market halt, …). The absence of a fill is explicit here — null tradeId/fillPrice,
        // never a sentinel string — and the cancel reason is surfaced for the caller's failure message.
        var orderCancel = json.get("orderCancelTransaction");
        String cancelReason = orderCancel != null && orderCancel.has("reason")
            ? orderCancel.get("reason").asText() : null;
        String status = orderCancel != null ? "CANCELLED" : "UNKNOWN";
        return new OrderResult(orderId, null, null, status, cancelReason);
    }

    public record StopOrderResult(String orderId, String status, String price) {}

    /** A single open trade from {@code GET /openTrades}: id, instrument, currentUnits, and the trade-level tag. */
    public record TradeView(String id, String instrument, String units, String tag) {}

    public StopOrderResult placeStopOrder(String instrument, String units, String price, String tag) throws Exception {
        return placeStopOrder(instrument, units, price, tag, false, null, null);
    }

    /** Builds the STOP order JSON body (extracted so the shape is testable without sending). */
    String buildStopOrderBody(String instrument, String units, String price, String tag,
                              boolean reduceOnly, String stopLossOnFill, String takeProfitOnFill) throws Exception {
        var orderBody = new java.util.LinkedHashMap<String, Object>();
        orderBody.put("type", "STOP");
        orderBody.put("instrument", instrument);
        orderBody.put("units", units);
        orderBody.put("price", price);
        orderBody.put("timeInForce", "GTC");
        orderBody.putAll(clientExtensions(tag));
        if (!reduceOnly) {
            // Only an order that can open a trade carries trade-level extensions.
            orderBody.putAll(tradeClientExtensions(tag));
        }
        if (reduceOnly) {
            orderBody.put("positionFill", "REDUCE_ONLY");
        }
        if (stopLossOnFill != null) {
            orderBody.put("stopLossOnFill", new java.util.HashMap<>() {{ put("price", stopLossOnFill); }});
        }
        if (takeProfitOnFill != null) {
            orderBody.put("takeProfitOnFill", new java.util.HashMap<>() {{ put("price", takeProfitOnFill); }});
        }
        return mapper.writeValueAsString(new java.util.HashMap<>() {{
            put("order", orderBody);
        }});
    }

    public StopOrderResult placeStopOrder(String instrument, String units, String price, String tag,
                                          boolean reduceOnly, String stopLossOnFill, String takeProfitOnFill) throws Exception {
        OrderTripwire.checkOrderAllowed(instrument, units, "OandaExecutor.placeStopOrder", baseUrl);
        String body = buildStopOrderBody(instrument, units, price, tag, reduceOnly, stopLossOnFill, takeProfitOnFill);

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
        OrderTripwire.checkOrderAllowed(tradeId, price, "OandaExecutor.addStopLoss", baseUrl);
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
        OrderTripwire.checkOrderAllowed(tradeId, price, "OandaExecutor.addTakeProfit", baseUrl);
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
     * {@code GET /openTrades} as a list of {@link TradeView}, exposing id/instrument/units/tag. The
     * tag comes from the trade's {@code clientExtensions} (what the order body's
     * {@code tradeClientExtensions} produces). Used by the orphan detector (story 50.1).
     */
    public java.util.List<TradeView> getOpenTradesDetailed() throws Exception {
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
        java.util.List<TradeView> views = new java.util.ArrayList<>();
        if (json.has("trades")) {
            for (var trade : json.get("trades")) {
                String id = trade.hasNonNull("id") ? trade.get("id").asText() : null;
                String instrument = trade.hasNonNull("instrument") ? trade.get("instrument").asText() : null;
                String units = trade.hasNonNull("currentUnits") ? trade.get("currentUnits").asText() : null;
                String tag = trade.path("clientExtensions").path("tag").asText(null);
                views.add(new TradeView(id, instrument, units, tag));
            }
        }
        return views;
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
        System.out.println(result.openedTrade()
            ? "Trade executed: " + result
            : "Order NOT filled (" + result.status() + (result.cancelReason() != null ? ", " + result.cancelReason() : "") + "): " + result);
    }
}
