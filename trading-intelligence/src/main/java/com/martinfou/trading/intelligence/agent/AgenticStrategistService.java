package com.martinfou.trading.intelligence.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.agent.WeeklyStrategyOutlook;
import com.martinfou.trading.core.agent.ComfortLevel;
import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.core.agent.TradeTriggerCondition;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.SeasonalityAnalyzer;
import com.martinfou.trading.intelligence.agent.tools.MacroTools;
import com.martinfou.trading.intelligence.agent.tools.SeasonalityTools;
import com.martinfou.trading.intelligence.agent.tools.SentimentTools;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/**
 * Orchestrator service for the Agentic Strategist ReAct loop.
 */
public class AgenticStrategistService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgenticStrategistService.class);
    
    private final ChatLanguageModel chatModelOverride;
    private final ExecutorService executor;
    private final boolean ownExecutor;
    private final long timeoutSeconds;
    
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .build();

    private interface AgenticStrategist {
        String analyze(String userMessage);
    }

    public AgenticStrategistService() {
        this(null, null, 40);
    }

    public AgenticStrategistService(ChatLanguageModel chatModelOverride) {
        this(chatModelOverride, null, 40);
    }

    public AgenticStrategistService(ChatLanguageModel chatModelOverride, ExecutorService executor) {
        this(chatModelOverride, executor, 40);
    }

    public AgenticStrategistService(ChatLanguageModel chatModelOverride, ExecutorService executor, long timeoutSeconds) {
        this.chatModelOverride = chatModelOverride;
        this.timeoutSeconds = timeoutSeconds;
        if (executor != null) {
            this.executor = executor;
            this.ownExecutor = false;
        } else {
            this.executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "agentic-strategist-pool");
                t.setDaemon(true);
                return t;
            });
            this.ownExecutor = true;
        }
    }

    /**
     * Runs the strategist ReAct loop for the specified asset up to the cutoff timestamp.
     *
     * @param asset  the asset to analyze (e.g., "EUR_USD")
     * @param cutoff the cutoff timestamp to prevent lookahead bias
     * @return the generated weekly strategy outlook
     * @throws Exception if execution fails or timeouts/budget/iteration limits are exceeded
     */
    public WeeklyStrategyOutlook run(String asset, Instant cutoff) throws Exception {
        log.info("Starting weekly analysis for asset: {}, cutoff: {}", asset, cutoff);

        // 1. Retrieve currentAssetPrice via SeasonalityAnalyzer
        String instrument = normalizeInstrument(asset);
        SeasonalityAnalyzer analyzer = new SeasonalityAnalyzer();
        List<Bar> bars = analyzer.loadBars(instrument);
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("No bar data found for instrument: " + instrument);
        }

        List<Bar> filtered = bars.stream()
                .filter(b -> !b.timestamp().isAfter(cutoff))
                .sorted(Comparator.comparing(Bar::timestamp))
                .toList();

        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("No bar data found for instrument " + instrument + " before or at cutoff " + cutoff);
        }

        double currentAssetPrice = filtered.get(filtered.size() - 1).close();
        log.info("Normalized instrument: {}, current asset price: {}", instrument, currentAssetPrice);

        // 2. Load and interpolate prompt
        String systemPromptTemplate = loadSystemPrompt();
        String systemPrompt = systemPromptTemplate
                .replace("{{targetAsset}}", asset)
                .replace("{{currentAssetPrice}}", String.valueOf(currentAssetPrice))
                .replace("{{cutoffTimestamp}}", cutoff.toString());

        // 3. Instantiate GuardrailChatModel wrapping the delegate model
        ChatLanguageModel delegate = (chatModelOverride != null) ? chatModelOverride : AgenticModelFactory.createChatModel();
        GuardrailChatModel decoratedModel = new GuardrailChatModel(delegate);

        // 4. Configure AiServices
        AgenticStrategist strategist = AiServices.builder(AgenticStrategist.class)
                .chatLanguageModel(decoratedModel)
                .systemMessageProvider(memoryId -> systemPrompt)
                .tools(new MacroTools(), new SentimentTools(), new SeasonalityTools())
                .build();

        // 5. Execute asynchronously with timeout
        Callable<String> task = () -> {
            return strategist.analyze("Generate weekly strategy outlook for " + asset);
        };

        Future<String> future = executor.submit(task);
        String jsonResult;
        try {
            jsonResult = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Execution timed out for asset: {}", asset);
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException("Execution failed", cause);
        }

        // 6. Deserialize with Jackson case-insensitive ObjectMapper
        WeeklyStrategyOutlookRaw rawOutlook;
        try {
            rawOutlook = objectMapper.readValue(jsonResult, WeeklyStrategyOutlookRaw.class);
        } catch (Exception e) {
            throw new ValidationException("Failed to parse LLM JSON output: " + e.getMessage());
        }

        // 7. Compute ComfortLevel
        ComfortLevel comfortLevel = computeComfortLevel(rawOutlook);

        // 8. Map to final WeeklyStrategyOutlook DTO
        WeeklyStrategyOutlook finalOutlook = new WeeklyStrategyOutlook(
                rawOutlook.targetAsset(),
                rawOutlook.bias(),
                rawOutlook.identifiedRegime(),
                comfortLevel,
                rawOutlook.rawSentimentScore(),
                rawOutlook.seasonalityWinRate(),
                rawOutlook.strategyRationale(),
                rawOutlook.setups(),
                rawOutlook.riskFactors(),
                rawOutlook.alphaKillSwitchCondition()
        );

        // 9. Run financial/context validations
        validateOutlook(finalOutlook, currentAssetPrice);

        return finalOutlook;
    }

    private ComfortLevel computeComfortLevel(WeeklyStrategyOutlookRaw raw) {
        MarketDirection bias = raw.bias();
        double winRate = raw.seasonalityWinRate();
        double sentiment = raw.rawSentimentScore();
        boolean macroConflict = raw.riskFactors() != null && raw.riskFactors().macroEventConflict();
        boolean sentimentDivergence = raw.riskFactors() != null && raw.riskFactors().sentimentDivergence();

        // Check LOW first as it takes precedence
        if (macroConflict 
                || sentimentDivergence 
                || winRate < 50.0 
                || bias == MarketDirection.NEUTRAL) {
            return ComfortLevel.LOW;
        }

        // Check HIGH
        boolean sentimentAligned = (bias == MarketDirection.BULLISH && sentiment > 0.2)
                || (bias == MarketDirection.BEARISH && sentiment < -0.2);

        if (bias != MarketDirection.NEUTRAL 
                && winRate >= 60.0 
                && sentimentAligned 
                && !macroConflict 
                && !sentimentDivergence) {
            return ComfortLevel.HIGH;
        }

        // Catch-all: MEDIUM
        return ComfortLevel.MEDIUM;
    }

    private void validateOutlook(WeeklyStrategyOutlook outlook, double currentAssetPrice) {
        // Validation of setup side and trigger type depending on bias
        if (outlook.bias() == MarketDirection.NEUTRAL) {
            if (outlook.setups() != null && !outlook.setups().isEmpty()) {
                throw new ValidationException("NEUTRAL bias permits no setups, but found " + outlook.setups().size());
            }
        } else if (outlook.bias() == MarketDirection.BULLISH) {
            if (outlook.setups() != null) {
                for (TradeTriggerCondition setup : outlook.setups()) {
                    if (setup.side() != Order.Side.BUY) {
                        throw new ValidationException("BULLISH bias permits only BUY setups, but found " + setup.side());
                    }
                }
            }
        } else if (outlook.bias() == MarketDirection.BEARISH) {
            if (outlook.setups() != null) {
                for (TradeTriggerCondition setup : outlook.setups()) {
                    if (setup.side() != Order.Side.SELL) {
                        throw new ValidationException("BEARISH bias permits only SELL setups, but found " + setup.side());
                    }
                }
            }
        }

        // Validation of price zone, invalidation pips, and executionContextRules keys
        if (outlook.setups() != null) {
            for (TradeTriggerCondition setup : outlook.setups()) {
                if (setup.targetedPriceZone() <= 0) {
                    throw new ValidationException("targetedPriceZone must be strictly positive: " + setup.targetedPriceZone());
                }
                double deviation = Math.abs(setup.targetedPriceZone() - currentAssetPrice) / currentAssetPrice;
                if (deviation > 0.05) {
                    throw new ValidationException("targetedPriceZone " + setup.targetedPriceZone() + " deviates more than 5.0% from current price " + currentAssetPrice);
                }

                if (setup.invalidationPips() < 10 || setup.invalidationPips() > 200) {
                    throw new ValidationException("invalidationPips must be between 10 and 200 pips: " + setup.invalidationPips());
                }

                if (setup.executionContextRules() != null) {
                    for (String key : setup.executionContextRules().keySet()) {
                        if (!key.equals("thresholdPrice") && !key.equals("triggerOffset") && !key.equals("trendStrength")) {
                            throw new ValidationException("Forbidden key in executionContextRules: " + key);
                        }
                    }
                }
            }
        }
    }

    private String normalizeInstrument(String asset) {
        String normalized = asset.replace('_', '/').toUpperCase();
        if (!normalized.contains("/") && normalized.length() == 6) {
            normalized = normalized.substring(0, 3) + "/" + normalized.substring(3);
        }
        return normalized;
    }

    private String loadSystemPrompt() {
        try (InputStream is = getClass().getResourceAsStream("/prompts/agentic-strategist-system.txt")) {
            if (is == null) {
                throw new IllegalStateException("Prompt template not found in classpath: /prompts/agentic-strategist-system.txt");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read system prompt file", e);
        }
    }

    @Override
    public void close() {
        if (ownExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
