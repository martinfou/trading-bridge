package com.martinfou.trading.data;

import java.time.LocalDate;
import java.util.Scanner;

/**
 * Interactive demo runner for all data connectors.
 *
 * <p>Usage: pass OANDA_API_KEY and OANDA_ACCOUNT_ID as args, or set env vars.
 *
 * <p>Run with:
 * <pre>
 *   mvn compile -q -pl trading-core,trading-data -am
 *   mvn exec:java -pl trading-data \
 *     -Dexec.mainClass=com.martinfou.trading.data.ConnectorRunner \
 *     -Dexec.args="API_KEY ACCOUNT_ID" \
 *     -Dexec.classpathScope=compile 2>/dev/null
 * </pre>
 */
public class ConnectorRunner {

    public static void main(String[] args) throws Exception {
        String apiKey = args.length > 0 ? args[0] : System.getenv("OANDA_API_KEY");
        String accountId = args.length > 1 ? args[1] : System.getenv("OANDA_ACCOUNT_ID");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: ConnectorRunner <OANDA_API_KEY> <OANDA_ACCOUNT_ID>");
            System.err.println("Or set OANDA_API_KEY and OANDA_ACCOUNT_ID environment variables.");
            System.exit(1);
        }

        var scanner = new Scanner(System.in);
        boolean done = false;

        while (!done) {
            System.out.println("\n╔══════════════════════════════════════════╗");
            System.out.println("║  DATA CONNECTOR DEMO                   ║");
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.println("║  1. Economic Calendar (ForexFactory)    ║");
            System.out.println("║  2. COT Data (myfxbook/CFTC)           ║");
            System.out.println("║  3. OANDA Position Sentiment           ║");
            System.out.println("║  4. Seasonality Analysis (Historical)  ║");
            System.out.println("║  5. Full Market Outlook (All Sources)  ║");
            System.out.println("║  6. Quick Summary (One-Liner)          ║");
            System.out.println("║  0. Exit                               ║");
            System.out.println("╚══════════════════════════════════════════╝");
            System.out.print("Choice: ");

            String choice = scanner.nextLine().strip();

            try {
                switch (choice) {
                    case "1" -> demoCalendar();
                    case "2" -> demoCOT();
                    case "3" -> demoOandaSentiment(apiKey, accountId);
                    case "4" -> demoSeasonality();
                    case "5" -> demoFullOutlook(apiKey, accountId);
                    case "6" -> demoQuickSummary(apiKey, accountId);
                    case "0" -> { done = true; System.out.println("Bye!"); }
                    default -> System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.err.println("❌ Error: " + e.getMessage());
                e.printStackTrace();
            }

            if (!done) {
                System.out.print("\nPress Enter to continue...");
                scanner.nextLine();
            }
        }
    }

    static void demoCalendar() throws Exception {
        System.out.println("\n📅 FOREX FACTORY ECONOMIC CALENDAR");
        System.out.println("───────────────────────────────────────────");
        var scraper = new ForexFactoryScraper();
        var events = scraper.fetchWeek(LocalDate.now());
        if (events.isEmpty()) {
            System.out.println("No events fetched. Using fallback.");
            events = ForexFactoryScraper.highImpact(
                new ForexFactoryScraper().fetchWeek(LocalDate.now()));
        }
        var highImpact = ForexFactoryScraper.highImpact(events);
        System.out.printf("Total events: %d (HIGH impact: %d)%n%n", events.size(), highImpact.size());
        for (var e : highImpact) {
            System.out.println("  " + e);
        }
    }

    static void demoCOT() throws Exception {
        System.out.println("\n📊 COT DATA (COMMITMENT OF TRADERS)");
        System.out.println("───────────────────────────────────────────");
        var fetcher = new COTDataFetcher();
        var positions = fetcher.fetchAll();
        System.out.printf("%d positions fetched%n%n", positions.size());
        for (var pos : positions) {
            System.out.println("  " + pos);
        }
        System.out.println("\n  Ranked by bullishness:");
        var ranked = COTDataFetcher.rankByBullishness(positions);
        for (var entry : ranked) {
            System.out.printf("  %-8s → %.2f:1%n", entry.getKey(), entry.getValue());
        }
    }

    static void demoOandaSentiment(String apiKey, String accountId) throws Exception {
        System.out.println("\n👥 OANDA POSITION SENTIMENT");
        System.out.println("───────────────────────────────────────────");
        var analyzer = new OandaPositionAnalyzer(apiKey, accountId, true);
        analyzer.printReport();
    }

    static void demoSeasonality() throws Exception {
        System.out.println("\n📈 SEASONALITY ANALYSIS");
        System.out.println("───────────────────────────────────────────");
        var analyzer = new SeasonalityAnalyzer();
        System.out.println("  Active adages: " + String.join(", ", analyzer.activeAdages()));
        System.out.println();
        var profiles = analyzer.analyzeAll();
        analyzer.printReport(profiles);
    }

    static void demoFullOutlook(String apiKey, String accountId) throws Exception {
        System.out.println("\n🌍 FULL MARKET OUTLOOK");
        System.out.println("───────────────────────────────────────────");
        var aggregator = new MarketSentimentAggregator(apiKey, accountId, true);
        var outlook = aggregator.analyze();
        System.out.println(outlook);
    }

    static void demoQuickSummary(String apiKey, String accountId) throws Exception {
        System.out.println("\n⚡ QUICK SUMMARY");
        System.out.println("───────────────────────────────────────────");
        var aggregator = new MarketSentimentAggregator(apiKey, accountId, true);
        System.out.println("  " + aggregator.quickSummary());
    }
}
