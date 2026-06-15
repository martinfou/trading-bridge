package com.martinfou.trading.examples;

import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.StrategyProfile;
import com.martinfou.trading.intelligence.pipeline.LongTermValidator;
import com.martinfou.trading.intelligence.pipeline.LtPipelineOrchestrator;
import com.martinfou.trading.intelligence.pipeline.NewsWeeklyValidator;
import com.martinfou.trading.intelligence.pipeline.PropShopValidator;

/**
 * CLI entry point for the Unified Strategy Engine pipeline.
 *
 * Usage:
 * <pre>
 *   # Run with default profile (LONG_TERM)
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunStrategyPipeline"
 *
 *   # Run with specific profile and iterations
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunStrategyPipeline" \
 *     -Dexec.args="--profile LONG_TERM --iterations 5"
 * </pre>
 */
public class RunStrategyPipeline {

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║   Unified Strategy Engine — Pipeline    ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println();

        // Parse CLI args (simple, no library needed)
        String profile = "LONG_TERM";
        int iterations = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--profile" -> {
                    if (i + 1 < args.length) profile = args[++i];
                }
                case "--iterations" -> {
                    if (i + 1 < args.length) {
                        try { iterations = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException e) { /* ignore */ }
                    }
                }
                case "--list", "-l" -> {
                    com.martinfou.trading.strategies.StrategyCatalog.printCatalog();
                    return;
                }
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
            }
        }

        System.out.println("Profile:    " + profile);
        System.out.println("Iterations: " + iterations);
        System.out.println();

        // Select validation profile
        var validator = switch (profile.toUpperCase()) {
            case "LONG_TERM" -> new LongTermValidator();
            case "PROP_SHOP" -> new PropShopValidator();
            case "NEWS_WEEKLY" -> new NewsWeeklyValidator();
            default -> {
                System.err.println("Unknown profile: " + profile + ". Options: LONG_TERM, PROP_SHOP, NEWS_WEEKLY");
                System.exit(1);
                yield null; // unreachable
            }
        };

        // Run pipeline
        var orchestrator = new LtPipelineOrchestrator(validator);
        PipelineResult result = orchestrator.run();

        // Print final result
        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  FINAL RESULT");
        System.out.println("═══════════════════════════════════════════");
        if (result.qualified()) {
            System.out.println("  ✅ " + result.spec().name() + " — QUALIFIED");
            System.out.println("  PF avg: " + String.format("%.2f", result.averagePf()));
            System.out.println("  DD max: " + String.format("%.1f%%", result.maxDrawdown()));
            System.out.println("  Pairs:  " + result.qualifiedPairCount(1.05, 20, 100) + "/" + result.pairResults().size());
        } else {
            System.out.println("  ❌ No strategy qualified");
            System.out.println("  Reason: " + result.failureReason());
        }
        System.out.println("  Duration: " + (result.durationMs() / 1000) + "s");
        System.out.println("  Lessons:  " + result.lessonsLearned().size());
    }

    private static void printUsage() {
        System.out.println("Usage: RunStrategyPipeline [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --profile PROFILE     Validation profile (LONG_TERM)");
        System.out.println("  --iterations N        Max iterations (default: 5)");
        System.out.println("  --help, -h            Show this help");
    }
}
