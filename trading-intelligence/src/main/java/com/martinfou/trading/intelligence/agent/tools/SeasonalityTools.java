package com.martinfou.trading.intelligence.agent.tools;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.data.SeasonalityAnalyzer;
import com.martinfou.trading.intelligence.agent.SeasonalityData;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SeasonalityTools {

    private static final Logger log = LoggerFactory.getLogger(SeasonalityTools.class);
    private final SeasonalityAnalyzer analyzer = new SeasonalityAnalyzer();

    @Tool("Fetches historical weekly seasonality metrics for the given asset at the specified cutoffTimestamp.")
    public SeasonalityData fetchWeeklySeasonality(
        @P("The asset name (e.g. EUR_USD, GBP_USD)") String asset,
        @P("The current simulation cutoff time to prevent lookahead bias") Instant cutoffTimestamp
    ) {
        if (asset == null || cutoffTimestamp == null) {
            log.warn("fetchWeeklySeasonality called with null parameter(s): asset={}, cutoff={}", asset, cutoffTimestamp);
            return new SeasonalityData(asset != null ? asset : "UNKNOWN", 0, MarketDirection.NEUTRAL, 0);
        }

        log.info("fetchWeeklySeasonality called: asset={}, cutoff={}", asset, cutoffTimestamp);

        String instrument = normalizeInstrument(asset);
        ZonedDateTime cutoffZdt = ZonedDateTime.ofInstant(cutoffTimestamp, ZoneId.of("UTC"));
        int targetWeek = cutoffZdt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int cutoffYear = cutoffZdt.get(IsoFields.WEEK_BASED_YEAR);

        // Validation / Safety Guard: return neutral seasonality if the instrument is unsupported/unknown
        if (!instrument.matches("^[A-Z0-9]{3}/[A-Z0-9]{3}$")) {
            log.warn("Unsupported or unknown instrument format for seasonality: {}. Returning default neutral.", instrument);
            return new SeasonalityData(asset, targetWeek, MarketDirection.NEUTRAL, 0);
        }

        try {
            List<Bar> bars = analyzer.loadBars(instrument);
            if (bars == null || bars.isEmpty()) {
                log.warn("No bar data found for instrument {}, returning default seasonality", instrument);
                return new SeasonalityData(asset, targetWeek, MarketDirection.NEUTRAL, 0);
            }

            // Filter bars strictly before or equal to cutoffTimestamp
            List<Bar> filtered = bars.stream()
                .filter(b -> !b.timestamp().isAfter(cutoffTimestamp))
                .toList();

            if (filtered.isEmpty()) {
                log.warn("No bar data found for instrument {} before cutoff {}, returning default", instrument, cutoffTimestamp);
                return new SeasonalityData(asset, targetWeek, MarketDirection.NEUTRAL, 0);
            }

            // Group bars in targetWeek by year (using TreeMap to guarantee chronological sorting of years, and excluding cutoffYear to prevent lookahead bias)
            Map<Integer, List<Bar>> barsByYear = new TreeMap<>();
            for (Bar bar : filtered) {
                ZonedDateTime barZdt = ZonedDateTime.ofInstant(bar.timestamp(), ZoneId.of("UTC"));
                int barWeek = barZdt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                if (barWeek == targetWeek) {
                    int barYear = barZdt.get(IsoFields.WEEK_BASED_YEAR);
                    if (barYear == cutoffYear) {
                        continue; // Exclude current active/incomplete week
                    }
                    barsByYear.computeIfAbsent(barYear, k -> new ArrayList<>()).add(bar);
                }
            }

            double pipSize = getPipSize(instrument);
            List<Double> weeklyReturnsPips = new ArrayList<>();
            for (List<Bar> yearBars : barsByYear.values()) {
                if (yearBars.size() < 2) continue;
                // Sort bars chronologically
                yearBars.sort(Comparator.comparing(Bar::timestamp));
                Bar first = yearBars.get(0);
                Bar last = yearBars.get(yearBars.size() - 1);
                double changePips = (last.close() - first.open()) / pipSize;
                weeklyReturnsPips.add(changePips);
            }

            if (weeklyReturnsPips.isEmpty()) {
                log.warn("No completed historical weeks found for instrument {} week {} before cutoff", instrument, targetWeek);
                return new SeasonalityData(asset, targetWeek, MarketDirection.NEUTRAL, 0);
            }

            int winCount = 0;
            double totalPips = 0;
            for (double r : weeklyReturnsPips) {
                totalPips += r;
                if (r > 0) {
                    winCount++;
                }
            }

            double winRate = (double) winCount / weeklyReturnsPips.size() * 100.0;
            double averagePips = totalPips / weeklyReturnsPips.size();

            MarketDirection bias;
            if (winRate >= 60.0) {
                bias = MarketDirection.BULLISH;
            } else if (winRate <= 40.0) {
                bias = MarketDirection.BEARISH;
            } else {
                bias = MarketDirection.NEUTRAL;
            }

            // Fix SLF4J formatting bug by formatting double arguments as formatted strings
            log.info("Calculated seasonality for {} week {}: winRate={}%, averagePips={}, bias={}",
                instrument, targetWeek, String.format("%.2f", winRate), String.format("%.1f", averagePips), bias);

            return new SeasonalityData(asset, targetWeek, bias, (int) Math.round(averagePips));

        } catch (Exception e) {
            log.error("Failed to analyze seasonality for asset {}: {}", asset, e.getMessage(), e);
            return new SeasonalityData(asset, targetWeek, MarketDirection.NEUTRAL, 0);
        }
    }

    private String normalizeInstrument(String asset) {
        String normalized = asset.replace('_', '/').toUpperCase();
        if (!normalized.contains("/") && normalized.length() == 6) {
            normalized = normalized.substring(0, 3) + "/" + normalized.substring(3);
        }
        return normalized;
    }

    private double getPipSize(String instrument) {
        if (instrument.contains("JPY")) {
            return 0.01;
        } else if (instrument.contains("XAU")) {
            return 0.1;
        } else {
            return 0.0001;
        }
    }
}
