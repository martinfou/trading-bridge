package com.martinfou.trading.intelligence.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates planner/reviewer JSON before hot-folder write (Epic 22.3). */
public final class PlanValidator {

    public enum Outcome {
        VALID,
        SCHEMA_ERROR,
        BUSINESS_RULE_VIOLATION
    }

    public record Result(Outcome outcome, String message) {
        public boolean valid() {
            return outcome == Outcome.VALID;
        }

        public boolean dlq() {
            return outcome == Outcome.SCHEMA_ERROR;
        }
    }

    private final TemplateRegistry registry;
    private final RiskBudgetEnvelope envelope;

    public PlanValidator(TemplateRegistry registry, RiskBudgetEnvelope envelope) {
        this.registry = registry;
        this.envelope = envelope;
    }

    public static PlanValidator fromRegistry(TemplateRegistry registry) {
        return new PlanValidator(registry, registry.defaultEnvelope());
    }

    /** Structural validation — malformed JSON fields → dlq. */
    public Result validateSchema(JsonNode root) {
        if (root == null || !root.isObject()) {
            return new Result(Outcome.SCHEMA_ERROR, "Plan root must be an object");
        }
        if (!root.hasNonNull("weekId") || root.get("weekId").asText().isBlank()) {
            return new Result(Outcome.SCHEMA_ERROR, "Missing weekId");
        }
        if (!root.has("picks") || !root.get("picks").isArray()) {
            return new Result(Outcome.SCHEMA_ERROR, "Missing picks array");
        }
        if (!root.has("reviewerStatus")) {
            return new Result(Outcome.SCHEMA_ERROR, "Missing reviewerStatus");
        }
        try {
            ReviewerStatus.valueOf(root.get("reviewerStatus").asText());
        } catch (IllegalArgumentException ex) {
            return new Result(Outcome.SCHEMA_ERROR, "Invalid reviewerStatus");
        }
        for (JsonNode pick : root.get("picks")) {
            Result pickResult = validatePickSchema(pick);
            if (!pickResult.valid()) {
                return pickResult;
            }
        }
        return new Result(Outcome.VALID, "ok");
    }

    /** Business rules after reviewer approval. */
    public Result validateBusinessRules(WeeklyPlan plan) {
        if (plan.picks().size() > envelope.maxPicks()) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION,
                "Too many picks: " + plan.picks().size() + " > " + envelope.maxPicks());
        }

        Set<String> pairDirectionKeys = new HashSet<>();
        for (WeeklyPlan.Pick pick : plan.picks()) {
            Result pickResult = validatePickBusiness(pick);
            if (!pickResult.valid()) {
                return pickResult;
            }
            if (pick.templateId().equals("T8")) {
                continue;
            }
            String key = pick.pair() + "|" + normalizeDirection(pick.direction());
            if (!pairDirectionKeys.add(key)) {
                return new Result(Outcome.BUSINESS_RULE_VIOLATION,
                    "Duplicate pair+direction: " + key);
            }
        }
        return new Result(Outcome.VALID, "ok");
    }

    public Result validateApprovedPlan(WeeklyPlan plan) {
        if (plan.reviewerStatus() != ReviewerStatus.APPROVED) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION, "Plan not approved");
        }
        return validateBusinessRules(plan);
    }

    private Result validatePickSchema(JsonNode pick) {
        if (!pick.hasNonNull("templateId")) {
            return new Result(Outcome.SCHEMA_ERROR, "Pick missing templateId");
        }
        if (!pick.has("params") || !pick.get("params").isObject()) {
            return new Result(Outcome.SCHEMA_ERROR, "Pick missing params object");
        }
        if (!pick.has("sources") || !pick.get("sources").isArray()) {
            return new Result(Outcome.SCHEMA_ERROR, "Pick missing sources array");
        }
        return new Result(Outcome.VALID, "ok");
    }

    private Result validatePickBusiness(WeeklyPlan.Pick pick) {
        if (!registry.templateIds().contains(pick.templateId())) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION, "Unknown templateId: " + pick.templateId());
        }
        TemplateRegistry.TemplateEntry entry = registry.require(pick.templateId());

        if (entry.codegenHandler() == TemplateRegistry.CodegenHandler.NO_TRADE) {
            return new Result(Outcome.VALID, "ok");
        }

        if (pick.pair() == null || pick.pair().isBlank()) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION, "Pick missing pair for " + pick.templateId());
        }
        if (!envelope.whitelistPairs().contains(pick.pair())) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION, "Pair not whitelisted: " + pick.pair());
        }

        String direction = normalizeDirection(pick.direction());
        if (direction.isBlank()) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION, "Pick missing direction for " + pick.templateId());
        }
        if (!entry.allowedDirections().isEmpty() && !entry.allowedDirections().contains(direction)) {
            return new Result(Outcome.BUSINESS_RULE_VIOLATION,
                "Direction not allowed for " + pick.templateId() + ": " + direction);
        }

        Map<String, Object> params = pick.params() == null ? Map.of() : pick.params();
        for (String required : entry.requiredParams()) {
            if (!params.containsKey(required) || params.get(required) == null) {
                return new Result(Outcome.BUSINESS_RULE_VIOLATION,
                    "Missing required param " + required + " for " + pick.templateId());
            }
        }
        return new Result(Outcome.VALID, "ok");
    }

    private static String normalizeDirection(String direction) {
        return direction == null ? "" : direction.trim().toUpperCase();
    }
}
