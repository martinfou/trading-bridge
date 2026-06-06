package com.martinfou.trading.intelligence.llm;

/** Abstraction for LLM chat completion (Epic 22.2). */
public interface LlmClient {

    /**
     * @param systemPrompt system instructions
     * @param userPrompt user payload (brief + context)
     * @param temperature sampling temperature
     */
    String complete(String systemPrompt, String userPrompt, double temperature) throws LlmException;
}
