package com.martinfou.trading.intelligence.llm;

/** Deterministic LLM stub for unit tests (Epic 22.2). */
public final class StubLlmClient implements LlmClient {

    private final String plannerResponse;
    private final String reviewerResponse;

    public StubLlmClient(String plannerResponse, String reviewerResponse) {
        this.plannerResponse = plannerResponse;
        this.reviewerResponse = reviewerResponse;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature) {
        if (systemPrompt != null && systemPrompt.contains("Reviewer")) {
            return reviewerResponse;
        }
        return plannerResponse;
    }
}
