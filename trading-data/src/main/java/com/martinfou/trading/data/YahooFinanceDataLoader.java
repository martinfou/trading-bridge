package com.martinfou.trading.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.BarAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ingests free historical Daily, H1, M30, and M1 price bars from Yahoo Finance CSV and v8 Chart API
 * for CME/COMEX Futures continuous proxies (ES=F, RTY=F, NQ=F, GC=F) and US Equities (IWM, MDY, AAPL, SPY, QQQ).
 *
 * <p>Yahoo v8 Chart API lookback limits:
 * <ul>
 *   <li>{@code 1m} (M1): last 30 days, fetched in &le; 7-day chunks</li>
 *   <li>{@code 5m}, {@code 15m}, {@code 30m} (M5, M15, M30): last 60 days</li>
 *   <li>{@code 1h} (H1, H4): last 730 days</li>
 *   <li>{@code 1d}, {@code 1wk} (D1, W1): multi-decade history</li>
 * </ul>
 */
public final class YahooFinanceDataLoader {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceDataLoader.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum lookback days supported by Yahoo Finance for 1-minute bars. */
    public static final int MAX_M1_LOOKBACK_DAYS = 30;
    /** Maximum chunk days per single HTTP request for 1-minute bars on Yahoo Finance. */
    public static final int MAX_M1_CHUNK_DAYS = 7;
    /** Maximum lookback days supported by Yahoo Finance for 5m / 15m / 30m bars. */
    public static final int MAX_INTRADAY_SUB_HOUR_LOOKBACK_DAYS = 60;
    /** Maximum lookback days supported by Yahoo Finance for 1h bars. */
    public static final int MAX_H1_LOOKBACK_DAYS = 729;

    @FunctionalInterface
    public interface ChartJsonFetcher {
        String fetchJson(String yahooTicker, long period1EpochSec, long period2EpochSec, String yahooInterval) throws IOException;
    }

    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private YahooFinanceDataLoader() {}

    public static List<Bar> loadCsv(Path csvPath, String targetSymbol) throws IOException {
        try (InputStream in = Files.newInputStream(csvPath)) {
            return loadCsv(in, targetSymbol);
        }
    }

    public static List<Bar> loadCsv(InputStream in, String targetSymbol) throws IOException {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            if (line == null) return List.of();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] tokens = line.split(",");
                if (tokens.length < 6) continue;

                try {
                    String dateStr = tokens[0].trim();
                    Instant timestamp;
                    if (dateStr.contains("T") || dateStr.contains("Z")) {
                        timestamp = Instant.parse(dateStr);
                    } else if (dateStr.length() == 10) {
                        LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                        timestamp = date.atStartOfDay(ZoneOffset.UTC).toInstant();
                    } else {
                        long epoch = Long.parseLong(dateStr);
                        timestamp = epoch > 1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                    }

                    double open = Double.parseDouble(tokens[1].trim());
                    double high = Double.parseDouble(tokens[2].trim());
                    double low = Double.parseDouble(tokens[3].trim());
                    double close = Double.parseDouble(tokens[4].trim());
                    // tokens[5] is Adj Close
                    long volume = tokens.length >= 7 ? parseLongSafe(tokens[6].trim()) : parseLongSafe(tokens[5].trim());

                    bars.add(new Bar(targetSymbol, timestamp, open, high, low, close, volume));
                } catch (Exception ignored) {
                    // skip invalid/null lines (e.g. market holidays)
                }
            }
        }
        return Collections.unmodifiableList(bars);
    }

    public static String mapProxySymbol(String yahooTicker) {
        if (yahooTicker == null) return "EUR_USD";
        String upper = yahooTicker.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ES=F" -> "MES";
            case "RTY=F", "M2K=F" -> "M2K";
            case "NQ=F" -> "MNQ";
            case "EMD=F" -> "EMD";
            case "YM=F", "MYM=F" -> "MYM";
            case "CL=F", "MCL=F" -> "MCL";
            case "GC=F" -> "GC";
            case "MGC=F" -> "MGC";
            case "SI=F", "SIL=F" -> "SI";
            default -> upper.replace('=', '_').replace('/', '_');
        };
    }

    /**
     * Maps an internal trading-bridge symbol (e.g. {@code MGC}, {@code MES}, {@code MNQ}, {@code M2K}, {@code IWM})
     * to its Yahoo Finance ticker (e.g. {@code GC=F}, {@code ES=F}, {@code NQ=F}, {@code RTY=F}, {@code IWM}).
     */
    public static String toYahooTicker(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "ES=F";
        }
        String norm = symbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');
        if (norm.endsWith("=F") || norm.endsWith("=X")) {
            return norm;
        }
        return switch (norm) {
            case "MES", "ES" -> "ES=F";
            case "MNQ", "NQ" -> "NQ=F";
            case "M2K", "RTY" -> "RTY=F";
            case "EMD" -> "EMD=F";
            case "MYM", "YM" -> "YM=F";
            case "MCL", "CL" -> "CL=F";
            case "MGC", "GC", "XAU_USD", "XAUUSD" -> "GC=F";
            case "SI", "SIL", "XAG_USD", "XAGUSD" -> "SI=F";
            case "EUR_USD", "GBP_USD", "USD_JPY", "GBP_JPY", "EUR_JPY", "AUD_USD", "NZD_USD", "USD_CAD", "USD_CHF", "EUR_GBP" ->
                norm.replace("_", "") + "=X";
            default -> norm;
        };
    }

    /**
     * Maps a standard trading-bridge timeframe (M1, M5, M15, M30, H1, H4, D1, W1) to a Yahoo v8 chart interval.
     * Note: H4 is fetched as {@code 1h} and aggregated to H4.
     */
    public static String toYahooInterval(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return "1h";
        }
        return switch (timeframe.trim().toUpperCase(Locale.ROOT)) {
            case "M1", "1M" -> "1m";
            case "M5", "5M" -> "5m";
            case "M15", "15M" -> "15m";
            case "M30", "30M" -> "30m";
            case "H1", "1H", "60M", "H4", "4H" -> "1h";
            case "D1", "1D" -> "1d";
            case "W1", "1W" -> "1wk";
            default -> "1h";
        };
    }

    /**
     * Returns the maximum lookback days Yahoo Finance supports for the given timeframe.
     */
    public static int maxLookbackDays(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return MAX_H1_LOOKBACK_DAYS;
        }
        return switch (timeframe.trim().toUpperCase(Locale.ROOT)) {
            case "M1", "1M" -> MAX_M1_LOOKBACK_DAYS;
            case "M5", "5M", "M15", "15M", "M30", "30M" -> MAX_INTRADAY_SUB_HOUR_LOOKBACK_DAYS;
            case "H1", "1H", "H4", "4H" -> MAX_H1_LOOKBACK_DAYS;
            case "D1", "1D", "W1", "1W" -> 365 * 30;
            default -> MAX_H1_LOOKBACK_DAYS;
        };
    }

    /**
     * True if Yahoo Finance can serve bars for at least part of {@code year} at {@code timeframe}
     * relative to {@code today}.
     */
    public static boolean canServeYear(String timeframe, int year, LocalDate today) {
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        if (yearEnd.isAfter(today)) {
            yearEnd = today;
        }
        LocalDate earliestAllowed = today.minusDays(maxLookbackDays(timeframe));
        return !yearEnd.isBefore(earliestAllowed);
    }

    public static boolean canServeYear(String timeframe, int year) {
        return canServeYear(timeframe, year, LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * True if Yahoo Finance can serve the entire {@code year} from Jan 1 at {@code timeframe}.
     */
    public static boolean canServeFullYear(String timeframe, int year, LocalDate today) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate earliestAllowed = today.minusDays(maxLookbackDays(timeframe));
        return !yearStart.isBefore(earliestAllowed);
    }

    /**
     * Fetches recent bars for {@code targetSymbol} and {@code timeframe} over the last {@code daysBack} days
     * (clamped to Yahoo Finance's maximum lookback for that timeframe).
     */
    public static List<Bar> fetchRecentBars(String targetSymbol, String timeframe, int daysBack) throws IOException {
        return fetchRecentBars(targetSymbol, timeframe, daysBack, Clock.systemUTC(), YahooFinanceDataLoader::fetchChartJsonHttp);
    }

    public static List<Bar> fetchRecentBars(
        String targetSymbol,
        String timeframe,
        int daysBack,
        Clock clock,
        ChartJsonFetcher fetcher
    ) throws IOException {
        Instant now = clock.instant();
        int clampedDays = Math.min(Math.max(1, daysBack), maxLookbackDays(timeframe));
        Instant from = now.minus(clampedDays, ChronoUnit.DAYS);
        return fetchRangeBars(targetSymbol, from, now, timeframe, clock, fetcher);
    }

    /**
     * Fetches available bars for {@code targetSymbol} in {@code year} at {@code timeframe}, automatically clamping
     * the start timestamp to Yahoo Finance's lookback window for intraday intervals (e.g. 30d for M1, 60d for M30).
     */
    public static List<Bar> fetchYearBars(String targetSymbol, int year, String timeframe) throws IOException {
        return fetchYearBars(targetSymbol, year, timeframe, Clock.systemUTC(), YahooFinanceDataLoader::fetchChartJsonHttp);
    }

    public static List<Bar> fetchYearBars(
        String targetSymbol,
        int year,
        String timeframe,
        Clock clock,
        ChartJsonFetcher fetcher
    ) throws IOException {
        Instant now = clock.instant();
        Instant yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yearEnd = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        if (yearEnd.isAfter(now)) {
            yearEnd = now;
        }
        return fetchRangeBars(targetSymbol, yearStart, yearEnd, timeframe, clock, fetcher);
    }

    /**
     * Fetches bars for {@code targetSymbol} between {@code from} and {@code to} at {@code timeframe}.
     * Automatically clamps {@code from} to Yahoo's lookback limit and splits {@code M1} queries into 7-day chunks.
     */
    public static List<Bar> fetchRangeBars(
        String targetSymbol,
        Instant from,
        Instant to,
        String timeframe,
        Clock clock,
        ChartJsonFetcher fetcher
    ) throws IOException {
        Instant now = clock.instant();
        Instant effectiveTo = to.isAfter(now) ? now : to;
        Instant earliestAllowed = now.minus(maxLookbackDays(timeframe), ChronoUnit.DAYS).plusSeconds(60);
        Instant effectiveFrom = from.isBefore(earliestAllowed) ? earliestAllowed : from;

        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new IOException(String.format(
                "Requested window [%s .. %s] is outside Yahoo Finance %s lookback limit (%d days)",
                from, to, timeframe, maxLookbackDays(timeframe)));
        }

        String yahooTicker = toYahooTicker(targetSymbol);
        String yahooInterval = toYahooInterval(timeframe);
        String tfUpper = timeframe == null ? "H1" : timeframe.trim().toUpperCase(Locale.ROOT);

        List<Bar> collected = new ArrayList<>();
        if ("1m".equals(yahooInterval)) {
            Duration maxChunk = Duration.ofDays(MAX_M1_CHUNK_DAYS);
            Instant chunkStart = effectiveFrom;
            while (chunkStart.isBefore(effectiveTo)) {
                Instant chunkEnd = chunkStart.plus(maxChunk);
                if (chunkEnd.isAfter(effectiveTo)) {
                    chunkEnd = effectiveTo;
                }
                if (chunkEnd.getEpochSecond() <= chunkStart.getEpochSecond()) {
                    break;
                }
                String json = fetcher.fetchJson(yahooTicker, chunkStart.getEpochSecond(), chunkEnd.getEpochSecond(), yahooInterval);
                collected.addAll(parseChartJson(json, targetSymbol));
                chunkStart = chunkEnd;
            }
        } else {
            String json = fetcher.fetchJson(yahooTicker, effectiveFrom.getEpochSecond(), effectiveTo.getEpochSecond(), yahooInterval);
            collected.addAll(parseChartJson(json, targetSymbol));
        }

        List<Bar> sanitized = BarStore.sanitizeChronological(collected);
        if ("H4".equals(tfUpper) || "4H".equals(tfUpper)) {
            return aggregateBars(sanitized, targetSymbol, "H4");
        }
        return sanitized;
    }

    /**
     * Parses a Yahoo Finance v8 chart JSON payload into {@link Bar} records.
     */
    public static List<Bar> parseChartJson(String json, String targetSymbol) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isNull() && !error.isMissingNode()) {
            throw new IOException("Yahoo Finance API error: " + error.toString());
        }
        JsonNode results = chart.path("result");
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }
        JsonNode result0 = results.get(0);
        JsonNode timestamps = result0.path("timestamp");
        JsonNode quoteArr = result0.path("indicators").path("quote");
        if (!timestamps.isArray() || !quoteArr.isArray() || quoteArr.isEmpty()) {
            return List.of();
        }

        JsonNode quote = quoteArr.get(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");

        int count = timestamps.size();
        var futuresContract = com.martinfou.trading.core.FuturesRegistry.find(targetSymbol);
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JsonNode tsNode = timestamps.get(i);
            JsonNode oNode = opens.get(i);
            JsonNode hNode = highs.get(i);
            JsonNode lNode = lows.get(i);
            JsonNode cNode = closes.get(i);
            if (tsNode == null || tsNode.isNull()
                || oNode == null || oNode.isNull()
                || hNode == null || hNode.isNull()
                || lNode == null || lNode.isNull()
                || cNode == null || cNode.isNull()) {
                continue;
            }
            long epochSec = tsNode.asLong();
            double open = oNode.asDouble();
            double high = hNode.asDouble();
            double low = lNode.asDouble();
            double close = cNode.asDouble();
            if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close)
                || open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0 || high < low) {
                continue;
            }
            if (futuresContract.isPresent()) {
                var contract = futuresContract.get();
                open = contract.quantizePrice(open);
                close = contract.quantizePrice(close);
                high = Math.max(contract.quantizePrice(high), Math.max(open, close));
                low = Math.min(contract.quantizePrice(low), Math.min(open, close));
            } else {
                high = Math.max(high, Math.max(open, close));
                low = Math.min(low, Math.min(open, close));
            }
            long vol = (volumes != null && volumes.get(i) != null && !volumes.get(i).isNull())
                ? volumes.get(i).asLong(0L)
                : 0L;
            bars.add(new Bar(targetSymbol, Instant.ofEpochSecond(epochSec), open, high, low, close, vol));
        }
        return BarStore.sanitizeChronological(bars);
    }

    static String fetchChartJsonHttp(String yahooTicker, long period1Sec, long period2Sec, String yahooInterval) throws IOException {
        String encodedTicker = URLEncoder.encode(yahooTicker, StandardCharsets.UTF_8);
        boolean prePost = yahooTicker.endsWith("=F") || yahooTicker.endsWith("=X");
        String url = String.format(Locale.ROOT,
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?period1=%d&period2=%d&interval=%s&includePrePost=%s",
            encodedTicker, period1Sec, period2Sec, yahooInterval, prePost);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        try {
            HttpResponse<String> response = DEFAULT_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Yahoo Finance HTTP " + response.statusCode() + " for " + yahooTicker + " (" + yahooInterval + ")");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching Yahoo Finance chart for " + yahooTicker, e);
        }
    }

    private static List<Bar> aggregateBars(List<Bar> source, String symbol, String targetTf) {
        if (source.isEmpty()) {
            return List.of();
        }
        BarAggregator agg = new BarAggregator(symbol, targetTf);
        List<Bar> aggregated = new ArrayList<>();
        for (Bar b : source) {
            boolean newPeriod = agg.isNewPeriod(b);
            agg.add(b);
            if (newPeriod) {
                agg.getLastCompletedBar().ifPresent(aggregated::add);
            }
        }
        agg.completePeriod();
        agg.getLastCompletedBar().ifPresent(aggregated::add);
        return BarStore.sanitizeChronological(aggregated);
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(s);
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }
}
