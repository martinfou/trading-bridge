package com.martinfou.trading.intelligence.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanValidatorTest {

    private PlanValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = PlanValidator.fromRegistry(TemplateRegistry.loadDefault());
    }

    @Test
    void validateApprovedPlan_acceptsValidPick() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T4", "EUR_USD", "LONG", Map.of(), List.of("src"), "rationale")
        ));
        assertTrue(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_rejectsUnknownTemplate() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T99", "EUR_USD", "LONG", Map.of(), List.of("src"), "rationale")
        ));
        assertFalse(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_rejectsDuplicatePairDirection() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T4", "EUR_USD", "LONG", Map.of(), List.of("a"), "one"),
            new WeeklyPlan.Pick("T5", "EUR_USD", "LONG", Map.of(), List.of("b"), "two")
        ));
        assertFalse(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_rejectsTooManyPicks() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T4", "EUR_USD", "LONG", Map.of(), List.of("a"), "one"),
            new WeeklyPlan.Pick("T5", "GBP_USD", "SHORT", Map.of(), List.of("b"), "two"),
            new WeeklyPlan.Pick("T4", "USD_JPY", "LONG", Map.of(), List.of("c"), "three"),
            new WeeklyPlan.Pick("T5", "AUD_USD", "SHORT", Map.of(), List.of("d"), "four")
        ));
        assertFalse(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateSchema_rejectsMissingReviewerStatus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree("""
            {"weekId":"2026-W24","picks":[]}
            """);
        PlanValidator.Result result = validator.validateSchema(node);
        assertTrue(result.dlq());
    }

    @Test
    void validateApprovedPlan_acceptsNoTradeWeek() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T8", null, null, Map.of("reason", "contradictions"), List.of(), "no trade")
        ));
        assertTrue(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_acceptsCaseInsensitivePair() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick("T4", "eur_usd", "LONG", Map.of(), List.of("src"), "rationale")
        ));
        assertTrue(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_guardsAgainstNullPicks() {
        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            null,
            ReviewerStatus.APPROVED,
            "brief-2026-06-06.json",
            RiskBudgetEnvelope.defaults(List.of("EUR_USD"))
        );
        assertFalse(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_guardsAgainstNullPickElement() {
        java.util.List<WeeklyPlan.Pick> picks = new java.util.ArrayList<>();
        picks.add(null);
        WeeklyPlan plan = samplePlan(picks);
        assertTrue(validator.validateApprovedPlan(plan).valid());
    }

    @Test
    void validateApprovedPlan_guardsAgainstNullTemplateId() {
        WeeklyPlan plan = samplePlan(List.of(
            new WeeklyPlan.Pick(null, "EUR_USD", "LONG", Map.of(), List.of("src"), "rationale")
        ));
        assertFalse(validator.validateApprovedPlan(plan).valid());
    }

    private static WeeklyPlan samplePlan(List<WeeklyPlan.Pick> picks) {
        return new WeeklyPlan(
            "2026-W24",
            picks,
            ReviewerStatus.APPROVED,
            "brief-2026-06-06.json",
            RiskBudgetEnvelope.defaults(List.of("EUR_USD", "GBP_USD", "USD_JPY", "GBP_JPY", "AUD_USD", "USD_CAD"))
        );
    }
}
