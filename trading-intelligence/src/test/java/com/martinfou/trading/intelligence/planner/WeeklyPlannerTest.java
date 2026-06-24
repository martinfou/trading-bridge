package com.martinfou.trading.intelligence.planner;

import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.llm.StubLlmClient;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.template.TemplateRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyPlannerTest {

    @Test
    void plan_dualPassProducesApprovedPlan() throws Exception {
        String plannerJson = """
            {
              "weekId": "2026-W24",
              "picks": [{
                "templateId": "T4",
                "pair": "EUR_USD",
                "direction": "LONG",
                "params": {},
                "sources": ["ff-2026-06-11-us-cpi"],
                "rationale": "London breakout week"
              }],
              "briefRef": "brief-2026-06-06.json",
              "riskEnvelopeSnapshot": {
                "maxPicks": 3,
                "maxLotSize": 0.01,
                "maxWeeklyDrawdownPct": 5.0,
                "whitelistPairs": ["EUR_USD","GBP_USD","USD_JPY","GBP_JPY","AUD_USD","USD_CAD"]
              }
            }
            """;
        String reviewerJson = plannerJson.replace(
            "\"riskEnvelopeSnapshot\"",
            "\"reviewerStatus\": \"APPROVED\",\n  \"riskEnvelopeSnapshot\""
        );

        WeeklyPlanner planner = new WeeklyPlanner(
            new StubLlmClient(plannerJson, reviewerJson),
            TemplateRegistry.loadDefault()
        );

        WeeklyIntelBrief brief = new WeeklyIntelBrief(
            Instant.parse("2026-06-06T17:00:00Z"),
            LocalDate.of(2026, 6, 9),
            List.of(new WeeklyIntelBrief.CalendarEventEntry(
                "ff-2026-06-11-us-cpi", "US CPI", "USD", "HIGH",
                Instant.parse("2026-06-11T12:30:00Z"), "forexfactory")),
            List.of(),
            List.of(),
            WeeklyIntelBrief.SentimentBlock.empty(),
            List.of(),
            IngestStatus.OK
        );

        var plan = planner.plan(brief, "brief-2026-06-06.json");
        assertEquals(ReviewerStatus.APPROVED, plan.reviewerStatus());
        assertEquals(1, plan.picks().size());
        assertEquals("T4", plan.picks().getFirst().templateId());
    }

    @Test
    void stripMarkdownFences_removesCodeBlock() {
        String raw = "```json\n{\"weekId\":\"2026-W24\"}\n```";
        assertEquals("{\"weekId\":\"2026-W24\"}", WeeklyPlanner.stripMarkdownFences(raw));
    }

    @Test
    void stripMarkdownFences_withConversationalText() {
        String raw = "Voici le plan :\n```json\n{\"weekId\":\"2026-W24\"}\n```\nJ'espère que cela aide !";
        assertEquals("{\"weekId\":\"2026-W24\"}", WeeklyPlanner.stripMarkdownFences(raw));
    }

    @Test
    void plan_missingBriefRefPreservesEnvelopeSnapshot() throws Exception {
        String plannerJson = """
            {
              "weekId": "2026-W24",
              "picks": [],
              "riskEnvelopeSnapshot": {
                "maxPicks": 4,
                "maxLotSize": 0.05,
                "maxWeeklyDrawdownPct": 10.0,
                "whitelistPairs": ["EUR_USD"]
              }
            }
            """;
        String reviewerJson = plannerJson.replace(
            "\"riskEnvelopeSnapshot\"",
            "\"reviewerStatus\": \"APPROVED\",\n  \"riskEnvelopeSnapshot\""
        );

        WeeklyPlanner planner = new WeeklyPlanner(
            new StubLlmClient(plannerJson, reviewerJson),
            TemplateRegistry.loadDefault()
        );

        WeeklyIntelBrief brief = new WeeklyIntelBrief(
            Instant.parse("2026-06-06T17:00:00Z"),
            LocalDate.of(2026, 6, 9),
            List.of(),
            List.of(),
            List.of(),
            WeeklyIntelBrief.SentimentBlock.empty(),
            List.of(),
            IngestStatus.OK
        );

        var plan = planner.plan(brief, "brief-2026-06-06.json");
        assertEquals("2026-W24", plan.weekId());
        assertEquals("brief-2026-06-06.json", plan.briefRef());
        assertNotNull(plan.riskEnvelopeSnapshot());
        assertEquals(0.05, plan.riskEnvelopeSnapshot().maxLotSize());
        assertEquals(10.0, plan.riskEnvelopeSnapshot().maxWeeklyDrawdownPct());
        assertEquals(4, plan.riskEnvelopeSnapshot().maxPicks());
    }
}
