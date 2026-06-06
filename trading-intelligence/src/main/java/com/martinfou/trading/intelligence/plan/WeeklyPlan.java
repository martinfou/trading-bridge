package com.martinfou.trading.intelligence.plan;

import com.martinfou.trading.intelligence.template.RiskBudgetEnvelope;

import java.util.List;
import java.util.Map;

/** LLM-reviewed weekly strategy plan persisted to hot folder (Epic 22.2). */
public record WeeklyPlan(
    String weekId,
    List<Pick> picks,
    ReviewerStatus reviewerStatus,
    String briefRef,
    RiskBudgetEnvelope riskEnvelopeSnapshot
) {
    public record Pick(
        String templateId,
        String pair,
        String direction,
        Map<String, Object> params,
        List<String> sources,
        String rationale
    ) {}
}
