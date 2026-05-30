package com.martinfou.trading.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates data from all connectors into a unified market sentiment view.
 *
 * <p>Combines:
 * <ul>
 *   <li>ForexFactoryScraper — economic calendar (high-impact events)</li>
 *   <li>COTDataFetcher — institutional positioning</li>
 *   <li>OandaPositionAnalyzer — retail sentiment</li>
 *   <li>SeasonalityAnalyzer — seasonal patterns</li>
 * </ul>
 *
 * <p>Produces a {@link MarketOutlook} with scores and recommendations.
 */
public class MarketSentimentAggregator {

    private static final Logger log = LoggerFactory.getLogger(MarketSentimentAggregator.class);
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final ForexFactoryScraper calendar;
    private final COTDataFetcher cot;
    private final OandaPositionAnalyzer sentiment;
    private final SeasonalityAnalyzer seasonality;

    /**
     * Complete market outlook combining all sources.
     */
    public record MarketOutlook(
        Instant timestamp,
        String dateLabel,
        String overallBias,           // Bullish / Bearish / Mixed
        int totalScore,               // -10 (very bearish) to +10 (very bullish)
        List<String> activeAdages,
        List<CalendarEventView> upcomingEvents,
        List<COTView> cotPositions,
        List<SentimentView> retailSentiment,
        Map<String, String> pairBias, // Per-pair recommendation
        String recommendation
    ) {
        @Override
        public String toString() {
            var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(NY);
            return """
                ╔══════════════════════════════════════════╗
                ║  MARKET OUTLOOK — %s
                ╠══════════════════════════════════════════╣
                ║  Overall: %s (Score: %+d/10)
                ║  Adages:  %s
                ╠══════════════════════════════════════════╣
                %s
                ╠══════════════════════════════════════════╣
                %s
                ╠══════════════════════════════════════════╣
                %s
                ╠══════════════════════════════════════════╣
                ║  Recommendation: %s
                ╚══════════════════════════════════════════╝
                """.formatted(
                    fmt.format(timestamp), overallBias, totalScore,
                    String.join(", ", activeAdages),
                    formatEvents(upcomingEvents),
                    formatCOT(cotPositions),
                    formatSentiment(retailSentiment),
                    recommendation
                );
        }

        private static String formatEvents(List<CalendarEventView> events) {
            if (events.isEmpty()) return "  No upcoming high-impact events";
            return events.stream()
                .map(e -> String.format("  🔥 %s %s — %s", e.time(), e.currency(), e.event()))
                .collect(Collectors.joining("\n"));
        }

        private static String formatCOT(List<COTView> positions) {
            if (positions.isEmpty()) return "  No COT data available";
            return positions.stream()
                .map(p -> String.format("  %-8s Spec: %+d (%.2f:1)", p.pair(), p.netSpec(), p.ratio()))
                .collect(Collectors.joining("\n"));
        }

        private static String formatSentiment(List<SentimentView> retail) {
            if (retail.isEmpty()) return "  No retail sentiment data";
            return retail.stream()
                .map(s -> String.format("  %-8s %s (Long: %.0f%% Short: %.0f%%)", s.instrument(), s.label(), s.longPct(), s.shortPct()))
                .collect(Collectors.joining("\n"));
        }
    }

    public record CalendarEventView(String time, String currency, String event) {}
    public record COTView(String pair, long netSpec, double ratio) {}
    public record SentimentView(String instrument, String label, double longPct, double shortPct) {}

    public MarketSentimentAggregator(String oandaApiKey, String oandaAccountId, boolean isPractice) {
        this.calendar = new ForexFactoryScraper();
        this.cot = new COTDataFetcher();
        this.sentiment = new OandaPositionAnalyzer(oandaApiKey, oandaAccountId, isPractice);
        this.seasonality = new SeasonalityAnalyzer();
    }

    public MarketOutlook analyze() {
        try {
            int score = 0;
            List<CalendarEventView> events = new ArrayList<>();
            List<COTView> cotViews = new ArrayList<>();
            List<SentimentView> sentimentViews = new ArrayList<>();
            Map<String, String> pairBias = new LinkedHashMap<>();
            List<String> adages = seasonality.activeAdages();

            // 1. Calendar: count high-impact events per currency
            try {
                var calEvents = calendar.fetchWeek(LocalDate.now());
                var highImpact = ForexFactoryScraper.highImpact(calEvents);
                for (var e : highImpact) {
                    var fmt = DateTimeFormatter.ofPattern("EE dd HH:mm").withZone(NY);
                    events.add(new CalendarEventView(fmt.format(e.time()), e.currency(), e.event()));
                }
                log.info("📅 {} high-impact events this week", highImpact.size());
            } catch (Exception e) {
                log.warn("Calendar fetch failed: {}", e.getMessage());
            }

            // 2. COT: institutional positioning
            try {
                var cotData = cot.fetchAll();
                for (var pos : cotData) {
                    cotViews.add(new COTView(pos.pair(), pos.netSpeculator(), pos.speculatorRatio()));
                    // Score: net spec > 0 = mildly bullish, > 30k = strongly bullish
                    long net = pos.netSpeculator();
                    String pair = pos.pair();
                    if (net > 30_000) { score += 1; pairBias.put(pair, "Bullish"); }
                    else if (net < -30_000) { score -= 1; pairBias.put(pair, "Bearish"); }
                    else if (net > 10_000) { score += 0; pairBias.put(pair, "Slight Bullish"); }
                    else if (net < -10_000) { score -= 0; pairBias.put(pair, "Slight Bearish"); }
                    else { pairBias.put(pair, "Neutral"); }
                }
                log.info("📊 COT: {} positions analyzed", cotData.size());
            } catch (Exception e) {
                log.warn("COT fetch failed: {}", e.getMessage());
            }

            // 3. Retail sentiment (contrarian)
            try {
                var marketSent = sentiment.fetchSentiment();
                for (var pos : marketSent.positions()) {
                    sentimentViews.add(new SentimentView(
                        pos.instrument(), pos.sentimentLabel(),
                        pos.longRatio(), pos.shortRatio()));
                    // Contrarian: if 70%+ are long, bearish signal
                    if (pos.longRatio() > 70) { score -= 1; }
                    else if (pos.shortRatio() > 70) { score += 1; }
                }
                log.info("👥 Retail sentiment: {} instruments", marketSent.instrumentCount());
            } catch (Exception e) {
                log.warn("Sentiment fetch failed: {}", e.getMessage());
            }

            // 4. Seasonality adjustments
            if (adages.contains("Sell in May and Go Away")) {
                score -= 1;
            }
            if (adages.contains("September Slump")) {
                score -= 1;
            }
            if (adages.contains("Santa Claus Rally")) {
                score += 1;
            }
            if (adages.contains("January Effect")) {
                score += 1;
            }

            // Clamp score to [-10, +10]
            score = Math.max(-10, Math.min(10, score));

            // Determine overall bias
            String bias;
            if (score >= 4) bias = "🟢 Bullish";
            else if (score >= 1) bias = "🔵 Slight Bullish";
            else if (score >= -1) bias = "🟡 Neutral";
            else if (score >= -4) bias = "🟠 Slight Bearish";
            else bias = "🔴 Bearish";

            // Recommendation
            String recommendation;
            if (score >= 5) recommendation = "Favor long positions. Look for breakout strategies on high-impact news. Consider carry trades.";
            else if (score >= 2) recommendation = "Mild bullish bias. Look for mean reversion buys on dips. Fade extreme retail shorts.";
            else if (score >= -2) recommendation = "No strong directional bias. Focus on mean reversion and range-trading strategies. Avoid trend-following.";
            else if (score >= -5) recommendation = "Mild bearish bias. Look for short entries on rallies. Consider safe-haven pairs (JPY, CHF).";
            else recommendation = "Strong bearish bias. Favor short positions. Defensive: USD, JPY, Gold. Avoid commodity currencies.";

            String biasOnly = bias.replaceAll("[🟢🔵🟡🟠🔴] ", "");

            return new MarketOutlook(
                Instant.now(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(NY).format(Instant.now()),
                bias, score, adages, events, cotViews, sentimentViews, pairBias, recommendation
            );

        } catch (Exception e) {
            log.error("Failed to aggregate sentiment: {}", e.getMessage());
            return new MarketOutlook(Instant.now(), LocalDate.now().toString(),
                "⚠ Error", 0, List.of(), List.of(), List.of(), List.of(), Map.of(),
                "Unable to analyze — " + e.getMessage());
        }
    }

    /**
     * Quick one-line summary for use in strategy prompts.
     */
    public String quickSummary() {
        var outlook = analyze();
        return String.format("[%s] Score: %+d/10 | Events: %d | COT pairs: %d | %s",
            outlook.overallBias(), outlook.totalScore(),
            outlook.upcomingEvents().size(),
            outlook.cotPositions().size(),
            outlook.recommendation().substring(0, Math.min(80, outlook.recommendation().length())));
    }
}
