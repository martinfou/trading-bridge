package com.martinfou.trading.intelligence.llm;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * Adapts a LangChain4j ChatLanguageModel to the legacy LlmClient interface.
 * Replaces the deprecated HttpDeepSeekClient.
 */
public class LangChainLlmClientAdapter implements LlmClient {

    private final ChatLanguageModel model;

    public LangChainLlmClientAdapter(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature) throws LlmException {
        try {
            // Note: If temperature needs to be passed dynamically, it requires recreating the model
            // or using an advanced request API. For now, we rely on the model's configured temperature.
            return model.generate(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
            )).content().text();
        } catch (Exception e) {
            throw new LlmException(e.getMessage());
        }
    }
}
