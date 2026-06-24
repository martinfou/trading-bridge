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
        String finalWeekId = (plan.weekId() == null || plan.weekId().isBlank()) ? weekId : plan.weekId();
        String finalBriefRef = (plan.briefRef() == null || plan.briefRef().isBlank()) ? briefRef : plan.briefRef();
        RiskBudgetEnvelope finalEnvelope = plan.riskEnvelopeSnapshot() == null ? envelope : plan.riskEnvelopeSnapshot();
        plan = new WeeklyPlan(finalWeekId, plan.picks(), plan.reviewerStatus(), finalBriefRef, finalEnvelope);
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
                var map = new java.util.HashMap<String, Object>();
                map.put("id", id);
                map.put("name", entry.name() != null ? entry.name() : "");
                map.put("requiredParams", entry.requiredParams() != null ? entry.requiredParams() : java.util.List.of());
                map.put("allowedDirections", entry.allowedDirections() != null ? entry.allowedDirections() : java.util.List.of());
                return map;
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
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int firstFence = trimmed.indexOf("```");
        if (firstFence != -1) {
            int secondFence = trimmed.indexOf("```", firstFence + 3);
            if (secondFence != -1) {
                String content = trimmed.substring(firstFence + 3, secondFence);
                if (content.toLowerCase().startsWith("json")) {
                    content = content.substring(4);
                }
                return content.trim();
            }
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
