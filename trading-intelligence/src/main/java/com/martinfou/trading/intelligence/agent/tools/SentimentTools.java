package com.martinfou.trading.intelligence.agent.tools;

import com.martinfou.trading.data.OandaPositionAnalyzer;
import com.martinfou.trading.intelligence.RepoRoots;
import com.martinfou.trading.intelligence.agent.SentimentData;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class SentimentTools {

    private static final Logger log = LoggerFactory.getLogger(SentimentTools.class);

    @Tool("Fetches retail market sentiment for the given asset at the specified cutoffTimestamp.")
    public SentimentData fetchMarketSentiment(
        @P("The asset name (e.g. EUR_USD, GBP_USD)") String asset,
        @P("The current simulation cutoff time to prevent lookahead bias") Instant cutoffTimestamp
    ) {
        if (asset == null || cutoffTimestamp == null) {
            log.warn("fetchMarketSentiment called with null parameter(s): asset={}, cutoff={}", asset, cutoffTimestamp);
            return new SentimentData(asset != null ? asset : "UNKNOWN", 0.0, "50% Long / 50% Short", List.of());
        }

        log.info("fetchMarketSentiment called: asset={}, cutoff={}", asset, cutoffTimestamp);

        // Try to load from historical briefs first
        WeeklyIntelBrief brief = findClosestBrief(cutoffTimestamp);
        if (brief != null && brief.sentiment() != null && brief.sentiment().oandaRetail() != null) {
            // Check maximum age of 14 days relative to cutoffTimestamp
            long ageDays = Duration.between(brief.generatedAt(), cutoffTimestamp).toDays();
            if (ageDays <= 14) {
                for (WeeklyIntelBrief.OandaRetailEntry entry : brief.sentiment().oandaRetail()) {
                    if (matchesAsset(entry.instrument(), asset)) {
                        double longPct = entry.longPct();
                        double shortPct = entry.shortPct();
                        double score = (longPct - shortPct) / 100.0;
                        String ratioStr = String.format("%.0f%% Long / %.0f%% Short", longPct, shortPct);
                        log.info("Found historical sentiment in brief: long={}, short={}", longPct, shortPct);
                        return new SentimentData(asset, score, ratioStr, List.of());
                    }
                }
            } else {
                log.warn("Found closest brief, but it is stale: age={} days (cutoff={})", ageDays, cutoffTimestamp);
            }
        }

        // Live fallback if cutoff is close to now, OANDA credentials are set, and we are explicitly in live/paper mode
        String tradingMode = System.getProperty("trading.mode");
        boolean isLiveOrPaper = "live".equalsIgnoreCase(tradingMode) || "paper".equalsIgnoreCase(tradingMode);
        if (isLiveOrPaper && cutoffTimestamp.isAfter(Instant.now().minus(24, ChronoUnit.HOURS))) {
            String apiKey = System.getenv("OANDA_API_KEY");
            String accountId = System.getenv("OANDA_ACCOUNT_ID");
            if (apiKey != null && !apiKey.isBlank() && accountId != null && !accountId.isBlank()) {
                try {
                    log.info("Attempting live OANDA sentiment fetch...");
                    OandaPositionAnalyzer analyzer = new OandaPositionAnalyzer(apiKey, accountId, true);
                    OandaPositionAnalyzer.MarketSentiment sentiment = analyzer.fetchSentiment();
                    for (OandaPositionAnalyzer.PositionSentiment pos : sentiment.positions()) {
                        if (matchesAsset(pos.instrument(), asset)) {
                            double longPct = pos.longRatio();
                            double shortPct = pos.shortRatio();
                            double score = (longPct - shortPct) / 100.0;
                            String ratioStr = String.format("%.0f%% Long / %.0f%% Short", longPct, shortPct);
                            return new SentimentData(asset, score, ratioStr, List.of());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Live OANDA sentiment fetch failed: {}", e.getMessage(), e);
                }
            }
        }

        // Default stable fallback if no data matches
        log.info("No sentiment data found for {}, returning default balanced sentiment", asset);
        return new SentimentData(asset, 0.0, "50% Long / 50% Short", List.of());
    }

    private WeeklyIntelBrief findClosestBrief(Instant cutoff) {
        Path repoRoot = RepoRoots.findRepoRoot();
        String intelDirProp = System.getProperty("trading.intel.dir");
        Path intelRoot = intelDirProp != null ? Path.of(intelDirProp) : repoRoot.resolve("data/weekly-intel");

        if (!Files.exists(intelRoot)) {
            return null;
        }

        try (var stream = Files.list(intelRoot)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().startsWith("brief-") && p.getFileName().toString().endsWith(".json"))
                .toList();

            Path closestFile = null;
            Instant closestInstant = null;

            for (Path file : files) {
                try {
                    String name = file.getFileName().toString();
                    if (name.length() <= 11) continue; // safety guard for brief-.json
                    String dateStr = name.substring(6, name.length() - 5);
                    LocalDate localDate = LocalDate.parse(dateStr);
                    Instant fileInstant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();

                    if (!fileInstant.isAfter(cutoff)) {
                        if (closestInstant == null || fileInstant.isAfter(closestInstant)) {
                            closestInstant = fileInstant;
                            closestFile = file;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse brief date from file {}: {}", file, e.getMessage(), e);
                }
            }

            if (closestFile != null) {
                log.info("Loaded closest brief file for cutoff {}: {}", cutoff, closestFile.getFileName());
                return WeeklyIntelBriefIO.read(closestFile);
            }
        } catch (Exception e) {
            log.warn("Error finding closest brief: {}", e.getMessage(), e);
        }

        return null;
    }

    private boolean matchesAsset(String briefInstrument, String requestedAsset) {
        if (briefInstrument == null || requestedAsset == null) return false;
        String normBrief = briefInstrument.replace("/", "").replace("_", "").toUpperCase();
        String normReq = requestedAsset.replace("/", "").replace("_", "").toUpperCase();
        return normBrief.equals(normReq);
    }
}
