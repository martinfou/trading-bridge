package com.martinfou.trading.intelligence.agent;

import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.core.agent.MarketRegime;
import com.martinfou.trading.core.agent.WeeklyStrategyOutlook;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class AgenticStrategistServiceTest {

    @TempDir
    static Path tempDir;

    private static Path tempBarsPath;
    private static Instant testCutoff;

    @BeforeAll
    static void setUp() throws IOException {
        Path intelRoot = tempDir.resolve("weekly-intel");
        Path barsDir = tempDir.resolve("historical/bars");
        Files.createDirectories(intelRoot);
        Files.createDirectories(barsDir);

        System.setProperty("trading.intel.dir", intelRoot.toAbsolutePath().toString());
        System.setProperty("trading.data.dir", barsDir.toAbsolutePath().toString());
        System.setProperty("trading.mode", "backtest");

        testCutoff = Instant.parse("2026-05-22T18:00:00Z");
        tempBarsPath = barsDir.resolve("EUR_USD_H1_H1.bars");

        // Write a mock bar file with 1 bar
        ByteBuffer buf = ByteBuffer.allocate(44);
        buf.putLong(Instant.parse("2026-05-22T17:00:00Z").getEpochSecond());
        buf.putDouble(1.1200); // open
        buf.putDouble(1.1400); // high
        buf.putDouble(1.1100); // low
        buf.putDouble(1.1350); // close
        buf.putInt(100);       // volume

        Files.write(tempBarsPath, buf.array());
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("trading.intel.dir");
        System.clearProperty("trading.data.dir");
        System.clearProperty("trading.mode");
        System.clearProperty("deepseek.model");
    }

    @Test
    void testPromptVariableInjection() throws Exception {
        List<ChatMessage> capturedMessages = Collections.synchronizedList(new ArrayList<>());

        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                capturedMessages.addAll(messages);
                return createDummyResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                capturedMessages.addAll(messages);
                return createDummyResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                capturedMessages.addAll(messages);
                return createDummyResponse();
            }

            private Response<AiMessage> createDummyResponse() {
                return Response.from(AiMessage.from("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}"));
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            com.martinfou.trading.core.agent.WeeklyStrategyOutlook result = service.run("EUR_USD", testCutoff);
            assertNotNull(result);
            assertEquals("EUR_USD", result.targetAsset());
            assertEquals(MarketDirection.BULLISH, result.bias());
            assertEquals(MarketRegime.HIGH_VOL_TREND, result.identifiedRegime());

            // Verify the system message contained interpolated variables
            boolean foundSystemMessage = false;
            for (ChatMessage msg : capturedMessages) {
                if (msg instanceof SystemMessage) {
                    foundSystemMessage = true;
                    String text = ((SystemMessage) msg).text();
                    assertTrue(text.contains("targetAsset: EUR_USD"), "Prompt did not contain targetAsset: EUR_USD. Actual: " + text);
                    assertTrue(text.contains("currentAssetPrice: 1.135"), "Prompt did not contain currentAssetPrice: 1.135. Actual: " + text);
                    assertTrue(text.contains("cutoff timestamp is: 2026-05-22T18:00:00Z"), "Prompt did not contain cutoffTimestamp. Actual: " + text);
                }
            }
            assertTrue(foundSystemMessage, "No SystemMessage was found in captured messages");
        }
    }

    @Test
    void testCaseInsensitiveEnumParsing() throws Exception {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return Response.from(AiMessage.from("{\"targetAsset\":\"EUR_USD\",\"bias\":\"bullish\",\"identifiedRegime\":\"high_vol_trend\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}"));
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                return generate(messages);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            com.martinfou.trading.core.agent.WeeklyStrategyOutlook result = service.run("EUR_USD", testCutoff);
            assertNotNull(result);
            assertEquals(MarketDirection.BULLISH, result.bias());
            assertEquals(MarketRegime.HIGH_VOL_TREND, result.identifiedRegime());
        }
    }

    @Test
    void testComfortLevelCalculations() throws Exception {
        // Comfort Level HIGH:
        // Bias not NEUTRAL, seasonality winrate >= 60%, sentiment aligned (> 0.2 for BULLISH / < -0.2 for BEARISH), macro conflict / sentiment divergence both false.
        {
            ChatLanguageModel modelHigh = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":60.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelHigh)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.HIGH, res.comfortLevel());
            }
        }

        // Comfort Level LOW:
        // Any of: macro conflict true, sentiment divergence true, seasonality < 50%, bias NEUTRAL.
        {
            // Case 1: macroEventConflict is true
            ChatLanguageModel modelLow1 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":true,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelLow1)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.LOW, res.comfortLevel());
            }

            // Case 2: sentimentDivergence is true
            ChatLanguageModel modelLow2 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":true,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelLow2)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.LOW, res.comfortLevel());
            }

            // Case 3: winrate < 50%
            ChatLanguageModel modelLow3 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":49.9,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelLow3)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.LOW, res.comfortLevel());
            }

            // Case 4: bias is NEUTRAL
            ChatLanguageModel modelLow4 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"NEUTRAL\",\"identifiedRegime\":\"MEAN_REVERSION\",\"rawSentimentScore\":0.0,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelLow4)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.LOW, res.comfortLevel());
            }
        }

        // Comfort Level MEDIUM:
        // Any other case.
        {
            // E.g., winrate >= 50% but < 60%
            ChatLanguageModel modelMed1 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":55.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelMed1)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.MEDIUM, res.comfortLevel());
            }

            // E.g., sentiment not aligned (sentiment = 0.1 for BULLISH)
            ChatLanguageModel modelMed2 = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.1,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(modelMed2)) {
                var res = service.run("EUR_USD", testCutoff);
                assertEquals(com.martinfou.trading.core.agent.ComfortLevel.MEDIUM, res.comfortLevel());
            }
        }
    }

    @Test
    void testFinancialValidations() throws Exception {
        // Current Close price in setup is 1.1350 (from setUp mock H1 bar)
        // targetedPriceZone <= 0
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":0.0,\"invalidationPips\":50,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("targetedPriceZone must be strictly positive"));
            }
        }

        // targetedPriceZone deviation > 5% (1.1350 * 1.05 = 1.19175 -> let's use 1.200)
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.200,\"invalidationPips\":50,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("deviates more than 5.0%"));
            }
        }

        // invalidationPips < 10
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":9,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("invalidationPips must be between 10 and 200"));
            }
        }

        // invalidationPips > 200
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":201,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("invalidationPips must be between 10 and 200"));
            }
        }

        // Biais BULLISH permits only BUY setups (violating with SELL)
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"SELL\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":50,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("BULLISH bias permits only BUY setups"));
            }
        }

        // Biais BEARISH permits only SELL setups (violating with BUY)
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BEARISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":-0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":50,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("BEARISH bias permits only SELL setups"));
            }
        }

        // Biais NEUTRAL forbids setups (violating with a setup)
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"NEUTRAL\",\"identifiedRegime\":\"MEAN_REVERSION\",\"rawSentimentScore\":0.0,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":50,\"executionContextRules\":{}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("NEUTRAL bias permits no setups"));
            }
        }

        // Forbidden key in executionContextRules
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":50,\"executionContextRules\":{\"forbiddenKey\":\"value\"}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                ValidationException ex = assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
                assertTrue(ex.getMessage().contains("Forbidden key in executionContextRules"));
            }
        }

        // Valid setup with accepted executionContextRules keys: thresholdPrice, triggerOffset, trendStrength
        {
            ChatLanguageModel model = createMockModelWithJson("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.3,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\"," +
                    "\"setups\":[{\"setupName\":\"s1\",\"side\":\"BUY\",\"type\":\"MARKET\",\"targetedPriceZone\":1.1350,\"invalidationPips\":50,\"executionContextRules\":{\"thresholdPrice\":\"1.1200\",\"triggerOffset\":\"10\",\"trendStrength\":\"high\"}}]," +
                    "\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
            try (AgenticStrategistService service = new AgenticStrategistService(model)) {
                var res = service.run("EUR_USD", testCutoff);
                assertNotNull(res);
                assertEquals(1, res.setups().size());
                assertEquals("1.1200", res.setups().get(0).executionContextRules().get("thresholdPrice"));
            }
        }
    }

    private ChatLanguageModel createMockModelWithJson(String json) {
        return new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return Response.from(AiMessage.from(json));
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                return generate(messages);
            }
        };
    }

    @Test
    void testIterationLimitExceeded() {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return createToolExecutionResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                return createToolExecutionResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                return createToolExecutionResponse();
            }

            private Response<AiMessage> createToolExecutionResponse() {
                ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                        .id("call-id-test")
                        .name("fetchMarketSentiment")
                        .arguments("{\"asset\":\"EUR_USD\", \"cutoffTimestamp\":\"2026-05-22T18:00:00Z\"}")
                        .build();
                return Response.from(AiMessage.from(toolRequest));
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(IterationLimitExceededException.class, () -> {
                service.run("EUR_USD", testCutoff);
            });
        }
    }

    @Test
    void testBudgetExceededGpt4() {
        System.setProperty("deepseek.model", "gpt-4o");

        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return createHighCostResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                return createHighCostResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                return createHighCostResponse();
            }

            private Response<AiMessage> createHighCostResponse() {
                AiMessage aiMessage = AiMessage.from("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0\",\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
                // gpt-4o: input $2.50/M, output $10.00/M. Let's return 60,000 output tokens -> $0.60
                TokenUsage usage = new TokenUsage(100, 60000);
                return Response.from(aiMessage, usage);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(BudgetExceededException.class, () -> {
                service.run("EUR_USD", testCutoff);
            });
        } finally {
            System.clearProperty("deepseek.model");
        }
    }

    @Test
    void testBudgetExceededDeepSeek() {
        System.setProperty("deepseek.model", "deepseek-chat");

        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return createHighCostResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                return createHighCostResponse();
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                return createHighCostResponse();
            }

            private Response<AiMessage> createHighCostResponse() {
                AiMessage aiMessage = AiMessage.from("{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0\",\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}");
                // deepseek-chat: input $0.14/M, output $0.28/M. Let's return 2,000,000 output tokens -> $0.56
                TokenUsage usage = new TokenUsage(100, 2000000);
                return Response.from(aiMessage, usage);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(BudgetExceededException.class, () -> {
                service.run("EUR_USD", testCutoff);
            });
        } finally {
            System.clearProperty("deepseek.model");
        }
    }

    @Test
    void testTimeoutEnforcement() {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                sleepAndReturn();
                return null;
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                sleepAndReturn();
                return null;
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                sleepAndReturn();
                return null;
            }

            private void sleepAndReturn() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        // Initialize with a timeout of 1 second
        try (AgenticStrategistService service = new AgenticStrategistService(mockModel, null, 1)) {
            assertThrows(TimeoutException.class, () -> {
                service.run("EUR_USD", testCutoff);
            });
        }
    }

    @Test
    void testNullParametersThrowException() {
        try (AgenticStrategistService service = new AgenticStrategistService(null)) {
            assertThrows(NullPointerException.class, () -> service.run(null, testCutoff));
            assertThrows(NullPointerException.class, () -> service.run("EUR_USD", null));
        }
    }

    @Test
    void testNullBiasThrowsValidationException() {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                // bias is null
                String json = "{\"targetAsset\":\"EUR_USD\",\"bias\":null,\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
                return Response.from(AiMessage.from(json), new TokenUsage(100, 200));
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpec) {
                return generate(messages);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
        }
    }

    @Test
    void testNullSetupElementThrowsValidationException() {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                // setups list contains a null element
                String json = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[null],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
                return Response.from(AiMessage.from(json), new TokenUsage(100, 200));
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpec) {
                return generate(messages);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
        }
    }

    @Test
    void testNullSetupFieldsThrowValidationException() {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                // setups element has null side/type
                String json = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[{\"setupName\":\"test\",\"side\":null,\"type\":null,\"targetedPriceZone\":1.1350,\"invalidationPips\":30,\"executionContextRules\":{}}],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
                return Response.from(AiMessage.from(json), new TokenUsage(100, 200));
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpec) {
                return generate(messages);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            assertThrows(ValidationException.class, () -> service.run("EUR_USD", testCutoff));
        }
    }

    @Test
    void testNullTokenUsageCountsGracefulHandling() throws Exception {
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                String json = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
                // TokenUsage has null counts
                TokenUsage usage = new TokenUsage(null, null);
                return Response.from(AiMessage.from(json), usage);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
                return generate(messages);
            }
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpec) {
                return generate(messages);
            }
        };

        try (AgenticStrategistService service = new AgenticStrategistService(mockModel)) {
            WeeklyStrategyOutlook outlook = service.run("EUR_USD", testCutoff);
            assertNotNull(outlook);
        }
    }
}
