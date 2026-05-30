package com.martinfou.trading.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Analyzes OANDA open positions to derive market sentiment.
 *
 * <p>Uses the OANDA REST API to fetch all open trades/positions and compute
 * the long/short ratio per instrument. This is a measure of retail sentiment
 * which can be used as a contrarian indicator.
 */
public class OandaPositionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OandaPositionAnalyzer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String apiKey;
    private final String accountId;
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OandaPositionAnalyzer(String apiKey, String accountId, boolean isPractice) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.baseUrl = isPractice
            ? "https://api-fxpractice.oanda.com/v3/"
            : "https://api-fxtrade.oanda.com/v3/";
        this.client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    }

    /**
     * Sentiment snapshot for a single instrument.
     */
    public record PositionSentiment(
        String instrument,
        long longUnits,
        long shortUnits,
        int longTradeCount,
        int shortTradeCount,
        double unrealizedPL
    ) {
        /** Net units: positive = net long, negative = net short */
        public long netUnits() { return longUnits - shortUnits; }

        /** Long ratio as percentage of total (0-100) */
        public double longRatio() {
            long total = Math.abs(longUnits) + Math.abs(shortUnits);
            return total == 0 ? 50.0 : (double) Math.abs(longUnits) / total * 100;
        }

        /** Short ratio as percentage of total (0-100) */
        public double shortRatio() { return 100.0 - longRatio(); }

        /**
         * Sentiment label: "Bullish", "Bearish", "Mixed", or "None".
         * Uses longRatio with threshold of 60%.
         */
        public String sentimentLabel() {
            if (longUnits == 0 && shortUnits == 0) return "None";
            if (longRatio() >= 60) return "Bullish";
            if (shortRatio() >= 60) return "Bearish";
            return "Mixed";
        }

        @Override
        public String toString() {
            return String.format("%-8s %-7s Long: %+d (%5.1f%%) Short: %+d (%5.1f%%) Trades: %d/%d P&L: %.2f",
                instrument, sentimentLabel(), longUnits, longRatio(), shortUnits, shortRatio(),
                longTradeCount, shortTradeCount, unrealizedPL);
        }
    }

    /**
     * Aggregated market sentiment across all positions.
     */
    public record MarketSentiment(
        List<PositionSentiment> positions,
        int totalLongTrades,
        int totalShortTrades,
        double totalUnrealizedPL,
        Instant timestamp
    ) {
        /** Overall market bias: "Bullish", "Bearish", "Mixed" */
        public String overallBias() {
            if (totalLongTrades > totalShortTrades * 1.5) return "Bullish";
            if (totalShortTrades > totalLongTrades * 1.5) return "Bearish";
            return "Mixed";
        }

        /** Total open trades */
        public int totalTrades() { return totalLongTrades + totalShortTrades; }

        /** Number of instruments traded */
        public int instrumentCount() { return positions.size(); }
    }

    /**
     * Fetch all open positions and compute sentiment.
     */
    public MarketSentiment fetchSentiment() throws Exception {
        var json = get("/accounts/" + accountId + "/openTrades");

        var trades = json.get("trades");
        Map<String, PositionSentiment> posMap = new LinkedHashMap<>();

        int totalLong = 0, totalShort = 0;
        double totalPL = 0;

        if (trades != null && trades.isArray()) {
            for (var trade : trades) {
                String instrument = trade.get("instrument").asText();
                String unitsStr = trade.get("currentUnits").asText();
                long units = Long.parseLong(unitsStr);
                double pl = trade.has("unrealizedPL") ? Double.parseDouble(trade.get("unrealizedPL").asText()) : 0;
                totalPL += pl;

                var existing = posMap.getOrDefault(instrument, new PositionSentiment(
                    instrument, 0, 0, 0, 0, 0));

                long newLong = existing.longUnits;
                long newShort = existing.shortUnits;
                int newLongTrades = existing.longTradeCount;
                int newShortTrades = existing.shortTradeCount;

                if (units > 0) {
                    newLong += units;
                    newLongTrades++;
                    totalLong++;
                } else {
                    newShort += Math.abs(units);
                    newShortTrades++;
                    totalShort++;
                }

                posMap.put(instrument, new PositionSentiment(
                    instrument, newLong, newShort, newLongTrades, newShortTrades,
                    existing.unrealizedPL + pl));
            }
        }

        return new MarketSentiment(
            new ArrayList<>(posMap.values()),
            totalLong, totalShort, totalPL, Instant.now()
        );
    }

    /**
     * Get sentiment for a specific instrument.
     */
    public PositionSentiment fetchPositionSentiment(String instrument) throws Exception {
        var sentiment = fetchSentiment();
        return sentiment.positions().stream()
            .filter(p -> p.instrument().equals(instrument))
            .findFirst()
            .orElse(new PositionSentiment(instrument, 0, 0, 0, 0, 0.0));
    }

    /**
     * Find the most shorted or most longed instruments (contrarian play).
     */
    public List<PositionSentiment> mostExtreme(int count) {
        try {
            var sentiment = fetchSentiment();
            return sentiment.positions().stream()
                .sorted((a, b) -> Double.compare(
                    Math.abs(b.longRatio() - 50),
                    Math.abs(a.longRatio() - 50)))
                .limit(count)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to fetch extreme positions: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Print a formatted sentiment report.
     */
    public void printReport() throws Exception {
        var sentiment = fetchSentiment();
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  OANDA POSITION SENTIMENT");
        System.out.println("  " + sentiment.timestamp().atZone(ZoneId.of("America/New_York"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        System.out.println("═══════════════════════════════════════════");
        System.out.printf("  Overall bias: %s%n", sentiment.overallBias());
        System.out.printf("  Total trades: %d (%d long / %d short)%n",
            sentiment.totalTrades(), sentiment.totalLongTrades(), sentiment.totalShortTrades());
        System.out.printf("  Unrealized P&L: $%.2f%n", sentiment.totalUnrealizedPL());
        System.out.println("───────────────────────────────────────────");
        for (var pos : sentiment.positions()) {
            System.out.println("  " + pos);
        }
        System.out.println("═══════════════════════════════════════════\n");
    }

    private com.fasterxml.jackson.databind.JsonNode get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path.replaceFirst("^/", "")))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(TIMEOUT)
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OANDA API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }
}
