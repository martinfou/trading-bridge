package com.martinfou.trading.intelligence.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import com.martinfou.trading.intelligence.llm.LlmClient;
import com.martinfou.trading.intelligence.llm.LlmException;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.plan.WeeklyPlanIO;
import com.martinfou.trading.intelligence.template.PlanValidator;
import com.martinfou.trading.intelligence.template.RiskBudgetEnvelope;
import com.martinfou.trading.intelligence.template.TemplateRegistry;
import com.martinfou.trading.intelligence.time.WeekBounds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Dual-pass planner (T~0.7) + reviewer (T~0.2) pipeline (Epic 22.2). */
public final class WeeklyPlanner {

    private static final double PLANNER_TEMPERATURE = 0.7;
    private static final double REVIEWER_TEMPERATURE = 0.2;

    private final LlmClient llmClient;
    private final TemplateRegistry registry;
    private final RiskBudgetEnvelope envelope;
    private final PlanValidator validator;
    private final ObjectMapper mapper;

    public WeeklyPlanner(LlmClient llmClient, TemplateRegistry registry) {
        this(llmClient, registry, registry.defaultEnvelope(), WeeklyPlanIO.mapper());
    }

    WeeklyPlanner(LlmClient llmClient, TemplateRegistry registry, RiskBudgetEnvelope envelope, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.registry = registry;
        this.envelope = envelope;
        this.validator = new PlanValidator(registry, envelope);
        this.mapper = mapper;
    }

    public WeeklyPlan plan(WeeklyIntelBrief brief, String briefRef) throws IOException, LlmException, PlannerValidationException {
        String briefJson = WeeklyIntelBriefIO.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(brief);
        String registryJson = mapper.writeValueAsString(registrySummary());
        String envelopeJson = mapper.writeValueAsString(envelope);
        String weekId = WeekBounds.weekId(brief.weekStart());

        String plannerSystem = loadPrompt("/prompts/planner.txt");
        String plannerUser = """
            weekId: %s
            briefRef: %s

            RiskBudgetEnvelope:
            %s

            TemplateRegistry summary:
            %s

            WeeklyIntelBrief:
            %s
            """.formatted(weekId, briefRef, envelopeJson, registryJson, briefJson);

        String draftJson = stripMarkdownFences(llmClient.complete(plannerSystem, plannerUser, PLANNER_TEMPERATURE));
        JsonNode draftNode = parseJson(draftJson);

        String reviewerSystem = loadPrompt("/prompts/reviewer.txt");
        String reviewerUser = """
            weekId: %s
            briefRef: %s

            WeeklyIntelBrief:
            %s

            Planner draft:
            %s
            """.formatted(weekId, briefRef, briefJson, draftJson);

        String reviewedJson = stripMarkdownFences(
            llmClient.complete(reviewerSystem, reviewerUser, REVIEWER_TEMPERATURE));
        JsonNode reviewedNode = parseJson(reviewedJson);

        PlanValidator.Result schema = validator.validateSchema(reviewedNode);
        if (!schema.valid()) {
            throw new PlannerValidationException(schema.message(), true);
        }

        WeeklyPlan plan = mapper.treeToValue(reviewedNode, WeeklyPlan.class);
        if (plan.weekId() == null || plan.weekId().isBlank()) {
            plan = new WeeklyPlan(weekId, plan.picks(), plan.reviewerStatus(), briefRef, envelope);
        }
        if (plan.briefRef() == null || plan.briefRef().isBlank()) {
            plan = new WeeklyPlan(plan.weekId(), plan.picks(), plan.reviewerStatus(), briefRef, envelope);
        }
        if (plan.riskEnvelopeSnapshot() == null) {
            plan = new WeeklyPlan(plan.weekId(), plan.picks(), plan.reviewerStatus(), plan.briefRef(), envelope);
        }
        if (plan.reviewerStatus() == ReviewerStatus.APPROVED) {
            PlanValidator.Result business = validator.validateApprovedPlan(plan);
            if (!business.valid()) {
                throw new PlannerValidationException(business.message(), false);
            }
        }
        return plan;
    }

    private Object registrySummary() {
        return registry.templateIds().stream()
            .sorted()
            .map(id -> {
                var entry = registry.require(id);
                return java.util.Map.of(
                    "id", id,
                    "name", entry.name(),
                    "requiredParams", entry.requiredParams(),
                    "allowedDirections", entry.allowedDirections()
                );
            })
            .toList();
    }

    private JsonNode parseJson(String json) throws PlannerValidationException {
        try {
            return mapper.readTree(json);
        } catch (IOException ex) {
            throw new PlannerValidationException("Invalid JSON from LLM: " + ex.getMessage(), true);
        }
    }

    static String stripMarkdownFences(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        return trimmed;
    }

    private static String loadPrompt(String resourcePath) throws IOException {
        try (InputStream in = WeeklyPlanner.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing prompt resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
