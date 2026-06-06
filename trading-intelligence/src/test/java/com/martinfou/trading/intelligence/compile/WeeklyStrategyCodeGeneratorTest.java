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
