package com.martinfou.trading.data;

import java.time.LocalDate;
import java.util.List;

/**
 * Non-interactive runner for weekly prop shop analysis.
 * Calls all connectors and outputs structured data.
 * Usage: mvn exec:java -pl trading-data -Dexec.mainClass=com.martinfou.trading.data.WeeklyAnalysisRunner
 */
public class WeeklyAnalysisRunner {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OANDA_API_KEY");
        String accountId = System.getenv("OANDA_ACCOUNT_ID");

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  WEEKLY FOREX PROP SHOP ANALYSIS                            ║");
        System.out.println("║  " + LocalDate.now() + "                                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // 1. ECONOMIC CALENDAR
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📅 1. ECONOMIC CALENDAR (ForexFactory)");
        System.out.println("=".repeat(70));
        try {
            var scraper = new ForexFactoryScraper();
            var allEvents = scraper.fetchWeek(LocalDate.now());
            var highImpact = ForexFactoryScraper.highImpact(allEvents);
            System.out.printf("Total events: %d, HIGH impact: %d%n%n", allEvents.size(), highImpact.size());
            for (var e : highImpact) {
                System.out.println("  " + e);
            }
        } catch (Exception e) {
            System.err.println("  ❌ ForexFactory error: " + e.getMessage());
            System.err.println("  ℹ️  Using hardcoded fallback (EconomicCalendar.THIS_WEEK)");
            var fallback = EconomicCalendar.getHighImpactEvents();
            System.out.printf("  Fallback HIGH events: %d%n", fallback.size());
            EconomicCalendar.printWeek();
        }

        // 2. COT DATA
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📊 2. COT DATA (Commitment of Traders)");
        System.out.println("=".repeat(70));
        try {
            var fetcher = new COTDataFetcher();
            var positions = fetcher.fetchAll();
            System.out.printf("  %d positions fetched%n", positions.size());
            for (var pos : positions) {
                System.out.println("  " + pos);
            }
            System.out.println("\n  Ranked by bullishness (long:short ratio):");
            var ranked = COTDataFetcher.rankByBullishness(positions);
            for (var entry : ranked) {
                System.out.printf("  %-8s → %.2f:1%n", entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            System.err.println("  ❌ COT error: " + e.getMessage());
        }

        // 3. OANDA POSITION SENTIMENT
        System.out.println("\n" + "=".repeat(70));
        System.out.println("👥 3. OANDA POSITION SENTIMENT");
        System.out.println("=".repeat(70));
        if (apiKey != null && accountId != null) {
            try {
                var analyzer = new OandaPositionAnalyzer(apiKey, accountId, true);
                analyzer.printReport();
            } catch (Exception e) {
                System.err.println("  ❌ OANDA sentiment error: " + e.getMessage());
                // Check for OANDA 503 maintenance
                if (e.getMessage() != null && e.getMessage().contains("maintenance")) {
                    System.err.println("  ⚠️ OANDA API in maintenance mode (weekend evening). Re-run later.");
                }
            }
        } else {
            System.err.println("  ⚠️ OANDA_API_KEY or OANDA_ACCOUNT_ID not set");
        }

        // 4. SEASONALITY
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📈 4. SEASONALITY ANALYSIS");
        System.out.println("=".repeat(70));
        try {
            var analyzer = new SeasonalityAnalyzer();
            var adages = analyzer.activeAdages();
            System.out.println("  Active adages: " + String.join(", ", adages));
            var profiles = analyzer.analyzeAll();
            analyzer.printReport(profiles);
        } catch (Exception e) {
            System.err.println("  ❌ Seasonality error: " + e.getMessage());
        }

        // 5. FULL MARKET OUTLOOK
        System.out.println("\n" + "=".repeat(70));
        System.out.println("🌍 5. FULL MARKET OUTLOOK (Aggregated)");
        System.out.println("=".repeat(70));
        if (apiKey != null && accountId != null) {
            try {
                var aggregator = new MarketSentimentAggregator(apiKey, accountId, true);
                var outlook = aggregator.analyze();
                System.out.println(outlook);
            } catch (Exception e) {
                System.err.println("  ❌ Market outlook error: " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("maintenance")) {
                    System.err.println("  ⚠️ OANDA unavailable. Proceeding with COT + Seasonality only.");
                }
            }
        }

        // 6. QUICK SUMMARY
        System.out.println("\n" + "=".repeat(70));
        System.out.println("⚡ 6. QUICK SUMMARY");
        System.out.println("=".repeat(70));
        if (apiKey != null && accountId != null) {
            try {
                var aggregator = new MarketSentimentAggregator(apiKey, accountId, true);
                System.out.println("  " + aggregator.quickSummary());
            } catch (Exception e) {
                System.err.println("  ❌ Quick summary error: " + e.getMessage());
            }
        }

        System.out.println("\n✅ Weekly analysis complete.");
    }
}
