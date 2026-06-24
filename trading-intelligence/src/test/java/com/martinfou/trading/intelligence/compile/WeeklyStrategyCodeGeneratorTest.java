package com.martinfou.trading.intelligence.compile;

import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.template.RiskBudgetEnvelope;
import com.martinfou.trading.intelligence.template.TemplateRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyStrategyCodeGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generate_allTemplateHandlersProduceJava() throws Exception {
        WeeklyStrategyCodeGenerator generator = WeeklyStrategyCodeGenerator.loadDefault();
        Path generatedDir = tempDir.resolve("generated");
        Path registrar = tempDir.resolve("LlmWeeklyStrategyCatalogRegistrar.java");
        writeRegistrarTemplate(registrar);

        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(
                pick("T1", "EUR_USD", "LONG", Map.of("direction", "LONG", "eventId", "e1", "eventTimeUtc", "2026-06-11T12:30:00Z")),
                pick("T2", "GBP_USD", "SHORT", Map.of("direction", "SHORT", "cotInstrument", "GBP", "percentile52w", 85)),
                pick("T3", "USD_JPY", "LONG", Map.of("direction", "LONG", "retailLongPct", 72)),
                pick("T4", "EUR_USD", "LONG", Map.of()),
                pick("T5", "AUD_USD", "SHORT", Map.of()),
                pick("T6", "USD_CAD", "LONG", Map.of("direction", "LONG", "centralBank", "BoC", "decisionDayUtc", "2026-06-10")),
                pick("T7", "GBP_JPY", "SHORT", Map.of("direction", "SHORT", "eventId", "e2", "eventTimeUtc", "2026-06-12T12:30:00Z")),
                pick("T8", null, null, Map.of("reason", "contradictions"))
            ),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );

        WeeklyStrategyCodeGenerator.GenerationResult result = generator.generate(plan, generatedDir, registrar);
        assertEquals(8, result.strategies().size());
        assertTrue(Files.list(generatedDir).count() >= 8);
        String registrarText = Files.readString(registrar);
        assertTrue(registrarText.contains("LlmWeeklyStrategyCatalog.register"));
        assertTrue(registrarText.contains("Weekly2026W24T4EURUSD"));
    }

    @Test
    void toStrategyId_formatsWeekTemplatePair() {
        assertEquals("LLM_WEEKLY_2026-W24_T4_EUR_USD",
            WeeklyStrategyCodeGenerator.toStrategyId("2026-W24", "T4", "EUR_USD"));
    }

    @Test
    void generate_escapesPhysicalNewlinesInRationale() throws Exception {
        WeeklyStrategyCodeGenerator generator = WeeklyStrategyCodeGenerator.loadDefault();
        Path generatedDir = tempDir.resolve("generated");
        Path registrar = tempDir.resolve("LlmWeeklyStrategyCatalogRegistrar.java");
        writeRegistrarTemplate(registrar);

        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(
                new WeeklyPlan.Pick("T8", null, null, Map.of("reason", "test"), List.of(), "rationale\nwith\nnewlines")
            ),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );

        WeeklyStrategyCodeGenerator.GenerationResult result = generator.generate(plan, generatedDir, registrar);
        assertEquals(1, result.strategies().size());
        String source = result.strategies().get(0).source();
        assertTrue(source.contains("rationale\\nwith\\nnewlines"), "Should contain escaped newlines instead of physical ones");
    }

    @Test
    void generate_supportsSameTemplateAndPairWithDifferentDirectionsWithoutCollision() throws Exception {
        WeeklyStrategyCodeGenerator generator = WeeklyStrategyCodeGenerator.loadDefault();
        Path generatedDir = tempDir.resolve("generated");
        Path registrar = tempDir.resolve("LlmWeeklyStrategyCatalogRegistrar.java");
        writeRegistrarTemplate(registrar);

        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(
                pick("T3", "EUR_USD", "LONG", Map.of("direction", "LONG")),
                pick("T3", "EUR_USD", "SHORT", Map.of("direction", "SHORT"))
            ),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );

        WeeklyStrategyCodeGenerator.GenerationResult result = generator.generate(plan, generatedDir, registrar);
        assertEquals(2, result.strategies().size());
        
        var strats = result.strategies();
        assertTrue(strats.get(0).className().contains("Long") || strats.get(0).className().contains("Short"));
        assertTrue(strats.get(1).className().contains("Long") || strats.get(1).className().contains("Short"));
        assertNotEquals(strats.get(0).className(), strats.get(1).className());
        assertNotEquals(strats.get(0).strategyId(), strats.get(1).strategyId());

        assertTrue(Files.exists(generatedDir.resolve(strats.get(0).className() + ".java")));
        assertTrue(Files.exists(generatedDir.resolve(strats.get(1).className() + ".java")));
    }

    @Test
    void toClassNameAndStrategyId_sanitizesDirectionWithHyphensAndSpaces() {
        String weekId = "2026-W24";
        String templateId = "T3";
        String pair = "EUR_USD";
        String direction = "LONG-TERM EXPANDED";

        String className = WeeklyStrategyCodeGenerator.toClassName(weekId, templateId, pair, direction);
        String strategyId = WeeklyStrategyCodeGenerator.toStrategyId(weekId, templateId, pair, direction);

        assertEquals("Weekly2026W24T3EURUSDLongtermexpanded", className);
        assertEquals("LLM_WEEKLY_2026-W24_T3_EUR_USD_LONGTERMEXPANDED", strategyId);
    }

    @Test
    void toClassNameAndStrategyId_handlesNullWeekIdAndTemplateId() {
        assertEquals("WeeklyNoneLong", WeeklyStrategyCodeGenerator.toClassName(null, null, null, "LONG"));
        assertEquals("LLM_WEEKLY_NONE_NONE_EUR_USD_LONG", WeeklyStrategyCodeGenerator.toStrategyId(null, null, "EUR_USD", "LONG"));
    }

    @Test
    void generate_escapesOtherControlCharactersInRationale() throws Exception {
        WeeklyStrategyCodeGenerator generator = WeeklyStrategyCodeGenerator.loadDefault();
        Path generatedDir = tempDir.resolve("generated");
        Path registrar = tempDir.resolve("LlmWeeklyStrategyCatalogRegistrar.java");
        writeRegistrarTemplate(registrar);

        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(
                new WeeklyPlan.Pick("T8", null, null, Map.of("reason", "test"), List.of(), "rationale\twith\bcontrol\fchars")
            ),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );

        WeeklyStrategyCodeGenerator.GenerationResult result = generator.generate(plan, generatedDir, registrar);
        assertEquals(1, result.strategies().size());
        String source = result.strategies().get(0).source();
        assertTrue(source.contains("rationale\\twith\\bcontrol\\fchars"), "Should contain escaped control characters");
    }

    private static WeeklyPlan.Pick pick(String templateId, String pair, String direction, Map<String, Object> params) {
        return new WeeklyPlan.Pick(templateId, pair, direction, params, List.of("source-1"), "rationale");
    }

    private static void writeRegistrarTemplate(Path registrar) throws Exception {
        Files.createDirectories(registrar.getParent());
        Files.writeString(registrar, """
            package com.martinfou.trading.strategies.llmweekly;

            public final class LlmWeeklyStrategyCatalogRegistrar {
                public static void registerAll() {
            // CODEGEN-BEGIN
            // CODEGEN-END
                }
            }
            """);
    }
}
