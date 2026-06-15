package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.agent.PairResult;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.StrategyProfile;
import com.martinfou.trading.core.agent.StrategySpec;
import com.martinfou.trading.core.agent.ValidationProfile;
import com.martinfou.trading.intelligence.agent.AgenticModelFactory;
import com.martinfou.trading.strategies.StrategyCatalog;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full pipeline: LLM generation → codegen → compile → backtest → evaluate → feedback.
 *
 * Usage:
 * <pre>
 * var orchestrator = new LtPipelineOrchestrator(new LongTermValidator());
 * PipelineResult result = orchestrator.run();
 * </pre>
 */
public class LtPipelineOrchestrator {

    private static final int MAX_ITERATIONS = 5;
    private static final int BACKTEST_TIMEOUT_SECONDS = 120;

    private static final String[] LT_PAIRS = {"EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD"};
    private static final double CAPITAL = 10_000;

    private final ValidationProfile profile;
    private final LtTemplateCodeGenerator codegen;
    private final ChatLanguageModel llm;
    private final String baseDir;

    private final List<String> sessionLessons = new ArrayList<>();

    public LtPipelineOrchestrator(ValidationProfile profile) {
        this(profile, AgenticModelFactory.createChatModel(), System.getProperty("user.dir"));
    }

    public LtPipelineOrchestrator(ValidationProfile profile, ChatLanguageModel llm, String baseDir) {
        this.profile = profile;
        this.codegen = new LtTemplateCodeGenerator();
        this.llm = llm;
        this.baseDir = baseDir;
    }

    /**
     * Run the full pipeline: generate → codegen → compile → backtest → evaluate.
     * Iterates up to MAX_ITERATIONS times, feeding failures back to the LLM.
     */
    public PipelineResult run() {
        System.out.println("=== Unified Strategy Engine — Pipeline ===");
        System.out.println("Profile: " + profile.name());
        System.out.println("Max iterations: " + MAX_ITERATIONS);
        System.out.println();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            System.out.println("--- Iteration " + (i + 1) + "/" + MAX_ITERATIONS + " ---");

            // Phase 1: Generate strategy spec via LLM
            StrategySpec spec = generateSpec(i);
            if (spec == null) {
                System.out.println("❌ Failed to generate valid spec, skipping iteration");
                continue;
            }
            System.out.println("Concept: " + spec.name() + " (" + spec.category() + ")");
            System.out.println("  " + spec.description());

            // Phase 2: Code generation
            String javaSource = codegen.generate(spec);
            String filePath = baseDir + "/" + codegen.filePath(spec);

            try {
                Path path = Paths.get(filePath);
                Files.createDirectories(path.getParent());
                Files.writeString(path, javaSource);
                System.out.println("  Code generated: " + path);
            } catch (IOException e) {
                System.err.println("  ❌ Failed to write source: " + e.getMessage());
                continue;
            }

            // Phase 3: Compile via CompileGate (Maven incremental)
            boolean compiled = compile();
            if (!compiled) {
                System.out.println("  ❌ Compilation failed, retrying with different parameters");
                sessionLessons.add(spec.name() + ": compilation failed — check template compatibility");
                continue;
            }
            System.out.println("  ✅ Compiled successfully");

            // Phase 4: Backtest on required pairs
            List<PairResult> pairResults = new ArrayList<>();
            for (String pair : profile.requiredPairs()) {
                PairResult pr = backtestStrategy(spec.name(), pair);
                if (pr != null) {
                    pairResults.add(pr);
                    System.out.printf("  %s: PF %.2f Sharpe %.2f DD %.1f%% WR %.0f%% trades %d%n",
                        pair, pr.pf(), pr.sharpe(), pr.dd(), pr.winRate(), pr.trades());
                }
            }

            if (pairResults.isEmpty()) {
                System.out.println("  ❌ No backtest results — all pairs failed");
                sessionLessons.add(spec.name() + ": all pairs failed in backtest");
                continue;
            }

            // Phase 5: Evaluate
            PipelineResult result = new PipelineResult(
                spec, profile.name().equals("LONG_TERM") ? StrategyProfile.LONG_TERM
                    : profile.name().equals("PROP_SHOP") ? StrategyProfile.PROP_SHOP
                    : StrategyProfile.NEWS_WEEKLY,
                false, pairResults, "", sessionLessons,
                System.currentTimeMillis() - startTime, Instant.now()
            );

            if (profile.qualifies(result)) {
                System.out.println("\n✅ STRATEGY QUALIFIED: " + spec.name());
                // Register in StrategyCatalog
                registerStrategy(spec);
                // Save experience
                sessionLessons.add(spec.name() + ": QUALIFIED — PF avg " + String.format("%.2f", result.averagePf()));
                return new PipelineResult(spec, result.profile(), true, pairResults,
                    "", sessionLessons, System.currentTimeMillis() - startTime, Instant.now());
            }

            // Phase 6: Feedback
            String whyRejected = profile.whyRejected(result);
            System.out.println("\n❌ Rejected: " + spec.name());
            System.out.println(whyRejected);

            // Build feedback message for LLM
            String feedback = buildFeedback(spec, result, whyRejected);
            sessionLessons.add(feedback);
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("\n=== Pipeline finished after " + MAX_ITERATIONS + " iterations ===");
        System.out.println("Duration: " + (duration / 1000) + "s");
        System.out.println("Lessons learned: " + sessionLessons.size());

        // Return last failure
        return new PipelineResult(
            null, profile.name().equals("LONG_TERM") ? StrategyProfile.LONG_TERM
                : profile.name().equals("PROP_SHOP") ? StrategyProfile.PROP_SHOP
                : StrategyProfile.NEWS_WEEKLY,
            false, List.of(), "Max iterations (" + MAX_ITERATIONS + ") reached without qualifying strategy",
            sessionLessons, duration, Instant.now()
        );
    }

    /**
     * Generate a strategy spec using the LLM.
     * Implements basic retry logic for invalid JSON.
     */
    private StrategySpec generateSpec(int iteration) {
        String prompt = buildSystemPrompt(iteration);
        try {
            String response = llm.generate(prompt);
            return parseSpec(response);
        } catch (Exception e) {
            System.err.println("  LLM error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build the system prompt for the LLM, including context, lessons, and instructions.
     */
    private String buildSystemPrompt(int iteration) {
        var sb = new StringBuilder();
        sb.append("You are a quantitative strategy architect for forex trading.\n");
        sb.append("Generate a LONG_TERM strategy specification as JSON.\n\n");

        sb.append("CONSTRAINTS:\n");
        sb.append("- H1 timeframe, max 1 trade per day per pair\n");
        sb.append("- SL: ATR-based (2.0-3.0x ATR), TP: 1.5-4.0x ATR\n");
        sb.append("- Position sizing: calcRiskPosition(capital=10000, riskPct=0.01)\n");
        sb.append("- Exit: closeOnly() mandatory\n");
        sb.append("- Max 3 indicators per strategy\n");
        sb.append("- Pairs: EUR/USD, GBP/USD, USD/JPY, AUD/USD\n");
        sb.append("- Trend following > Mean reversion on H1\n");
        sb.append("- RSI(3) for momentum, RSI(14) for mean reversion\n");
        sb.append("- Simple SMA crossover = too basic, add filter\n\n");

        // Inject session lessons
        if (!sessionLessons.isEmpty()) {
            sb.append("LESSONS LEARNED FROM PREVIOUS ATTEMPTS (do not repeat these):\n");
            for (String lesson : sessionLessons) {
                sb.append("- ").append(lesson).append("\n");
            }
            sb.append("\n");
        }

        sb.append("RESPONSE FORMAT (valid JSON only, no markdown):\n");
        sb.append("{\n");
        sb.append("  \"name\": \"LtConceptName\",\n");
        sb.append("  \"inspiration\": \"source of the idea\",\n");
        sb.append("  \"description\": \"one-line description\",\n");
        sb.append("  \"category\": \"Trend Following|Mean Reversion|Momentum|Volatility\",\n");
        sb.append("  \"indicators\": [\"SMA\", \"ATR\"],\n");
        sb.append("  \"longEntry\": \"CLOSE > SMA(LOOKBACK) AND RSI(3) > 50\",\n");
        sb.append("  \"shortEntry\": \"CLOSE < SMA(LOOKBACK) AND RSI(3) < 50\",\n");
        sb.append("  \"exitCondition\": \"REVERSE_SIGNAL\",\n");
        sb.append("  \"slMultiplier\": 2.0,\n");
        sb.append("  \"tpMultiplier\": 4.0,\n");
        sb.append("  \"maxHoldBars\": 240,\n");
        sb.append("  \"params\": { \"lookback\": 20 }\n");
        sb.append("}\n");

        sb.append("\nGenerate a strategy DIFFERENT from standard SMA crossovers. ");
        sb.append("Be creative but within the constraints.");
        return sb.toString();
    }

    /**
     * Parse LLM response into a StrategySpec (simple JSON parsing).
     */
    private StrategySpec parseSpec(String json) {
        try {
            // Basic JSON parsing without Jackson dependency in this context
            String name = extractJsonString(json, "name");
            String inspiration = extractJsonString(json, "inspiration");
            String description = extractJsonString(json, "description");
            String category = extractJsonString(json, "category");
            String longEntry = extractJsonString(json, "longEntry");
            String shortEntry = extractJsonString(json, "shortEntry");
            String exitCondition = extractJsonString(json, "exitCondition");
            double slMult = extractJsonDouble(json, "slMultiplier", 2.0);
            double tpMult = extractJsonDouble(json, "tpMultiplier", 4.0);
            int maxHold = extractJsonInt(json, "maxHoldBars", 240);
            int lookback = extractJsonIntFromParams(json, "lookback", 20);

            if (name == null || name.isBlank()) return null;
            if (longEntry == null && shortEntry == null) return null;

            var indicators = new ArrayList<String>();
            String inds = extractJsonArray(json, "indicators");
            if (inds != null) {
                for (String s : inds.split(",")) {
                    String trimmed = s.trim().replaceAll("[\"\\[\\]]", "");
                    if (!trimmed.isEmpty()) indicators.add(trimmed);
                }
            }

            var params = new HashMap<String, Object>();
            params.put("lookback", lookback);

            return new StrategySpec(name, inspiration != null ? inspiration : "LLM-generated",
                description != null ? description : name,
                StrategyProfile.LONG_TERM,
                category != null ? category : "Trend Following",
                indicators, longEntry, shortEntry,
                exitCondition != null ? exitCondition : "MAX_HOLD",
                slMult, tpMult, maxHold, params);
        } catch (Exception e) {
            System.err.println("  Failed to parse LLM response: " + e.getMessage());
            return null;
        }
    }

    private String extractJsonString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        idx = json.indexOf(':', idx) + 1;
        int start = json.indexOf('"', idx);
        if (start < 0) return null;
        start++;
        int end = json.indexOf('"', start);
        if (end < 0) return json.substring(start).trim();
        return json.substring(start, end);
    }

    private double extractJsonDouble(String json, String key, double def) {
        try {
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return def;
            idx = json.indexOf(':', idx) + 1;
            int end = json.indexOf(',', idx);
            if (end < 0) end = json.indexOf('}', idx);
            if (end < 0) return def;
            return Double.parseDouble(json.substring(idx, end).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int extractJsonInt(String json, String key, int def) {
        return (int) extractJsonDouble(json, key, def);
    }

    private int extractJsonIntFromParams(String json, String key, int def) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx < 0) return def;
        String paramsSection = json.substring(paramsIdx);
        return (int) extractJsonDouble(paramsSection, key, def);
    }

    private String extractJsonArray(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        idx = json.indexOf('[', idx);
        if (idx < 0) return null;
        idx++;
        int end = json.indexOf(']', idx);
        if (end < 0) return null;
        return json.substring(idx, end);
    }

    /**
     * Compile the project with Maven (incremental).
     */
    private boolean compile() {
        try {
            // Use CompileGate if available, otherwise fall back to ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "compile", "-pl", "trading-strategies", "-am", "-q");
            pb.directory(new java.io.File(baseDir));
            pb.environment().put("JAVA_HOME",
                "/home/martinfou/.local/share/mise/installs/java/26.0.1");
            pb.environment().put("PATH",
                "/home/martinfou/.local/share/mise/installs/java/26.0.1/bin:"
                + "/home/martinfou/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin:"
                + System.getenv("PATH"));

            Process p = pb.start();
            boolean finished = p.waitFor(BACKTEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            System.err.println("  Compile error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Run a single backtest for a strategy on a given pair.
     * Uses synthetic sample bars for MVP; real historical data loading
     * will be added when the data pipeline is connected.
     */
    private PairResult backtestStrategy(String strategyName, String pair) {
        try {
            String oandaSymbol = pair.replace("_", "/");
            Strategy strategy = StrategyCatalog.create(strategyName, oandaSymbol);

            // Use sample bars for MVP (3000 bars of synthetic data)
            List<Bar> bars = SampleBarGenerator.generate(oandaSymbol, 3000);

            if (bars == null || bars.isEmpty()) {
                System.err.println("  No data for " + pair);
                return null;
            }

            var costs = BacktestExecutionCost.ZERO;
            RunContext context = RunContext.forStrategy(strategy, oandaSymbol, RunMode.BACKTEST, bars, CAPITAL);
            BacktestResult br = context.run();

            return new PairResult(pair, br.profitFactor(), br.sharpeRatio(),
                br.maxDrawdownPct(), br.winRatePct(), br.totalTrades(), br.totalPnl());
        } catch (Exception e) {
            System.err.println("  Backtest error for " + pair + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Register the generated strategy in StrategyCatalog.
     */
    private void registerStrategy(StrategySpec spec) {
        String className = spec.name().replaceAll("[^a-zA-Z0-9]", "");
        try {
            Class<?> strategyClass = Class.forName("com.martinfou.trading.strategies.longterm." + className);
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<String, Strategy>)
                sym -> {
                    try {
                        return (Strategy) strategyClass.getConstructor(String.class, String.class)
                            .newInstance(className, sym);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
            StrategyCatalog.register(className, StrategyCatalog.Family.EXAMPLE, factory, "EUR_USD");
            System.out.println("  Registered in StrategyCatalog: " + className);
        } catch (ClassNotFoundException e) {
            System.err.println("  Cannot register " + className + " — class not found after compile");
        }
    }

    private String buildFeedback(StrategySpec spec, PipelineResult result, String whyRejected) {
        return String.format("%s (%s): rejected — %s",
            spec.name(), spec.category(), whyRejected.replace('\n', ' ').trim());
    }
}
