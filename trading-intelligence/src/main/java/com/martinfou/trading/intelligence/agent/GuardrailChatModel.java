package com.martinfou.trading.intelligence.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decorator for LangChain4j ChatLanguageModel to enforce safety guardrails:
 * - Restricts ReAct loop to a maximum of 4 iterations.
 * - Interrupts execution if estimated token costs exceed $0.50 USD.
 */
public class GuardrailChatModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(GuardrailChatModel.class);

    private final ChatLanguageModel delegate;
    private final String modelName;
    private final java.util.concurrent.atomic.AtomicInteger iterations = new java.util.concurrent.atomic.AtomicInteger(0);
    private final Object costLock = new Object();
    private double accumulatedCost = 0.0;

    public GuardrailChatModel(ChatLanguageModel delegate) {
        this.delegate = delegate;
        this.modelName = resolveModelName();
    }

    // Constructor for testing
    public GuardrailChatModel(ChatLanguageModel delegate, String modelName) {
        this.delegate = delegate;
        this.modelName = modelName != null ? modelName.toLowerCase() : "deepseek-chat";
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        checkGuardrailsBefore();
        Response<AiMessage> response = delegate.generate(messages);
        checkGuardrailsAfter(response);
        return response;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        checkGuardrailsBefore();
        Response<AiMessage> response = delegate.generate(messages, toolSpecifications);
        checkGuardrailsAfter(response);
        return response;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        checkGuardrailsBefore();
        Response<AiMessage> response = delegate.generate(messages, toolSpecification);
        checkGuardrailsAfter(response);
        return response;
    }

    public int getIterations() {
        return iterations.get();
    }

    public double getAccumulatedCost() {
        synchronized (costLock) {
            return accumulatedCost;
        }
    }

    private void checkGuardrailsBefore() {
        int currentIteration = iterations.incrementAndGet();
        log.info("ReAct loop iteration #{}", currentIteration);
        if (currentIteration > 4) {
            log.error("ReAct loop iteration limit exceeded: {}", currentIteration);
            throw new IterationLimitExceededException("ReAct loop exceeded maximum of 4 iterations");
        }
    }

    private void checkGuardrailsAfter(Response<AiMessage> response) {
        if (response != null && response.tokenUsage() != null) {
            Integer in = response.tokenUsage().inputTokenCount();
            Integer out = response.tokenUsage().outputTokenCount();
            int inputTokens = in != null ? in : 0;
            int outputTokens = out != null ? out : 0;

            // Rates per 1M tokens
            double inputRate = 0.14 / 1_000_000.0;
            double outputRate = 0.28 / 1_000_000.0;

             if (modelName.contains("gpt-4")) {
                 inputRate = 2.50 / 1_000_000.0;
                 outputRate = 10.00 / 1_000_000.0;
             }

            double cost = (inputTokens * inputRate) + (outputTokens * outputRate);
            double currentAccumulatedCost;
            synchronized (costLock) {
                accumulatedCost += cost;
                currentAccumulatedCost = accumulatedCost;
            }
            log.info("Iteration cost: ${} (Input: {}, Output: {}), Accumulated: ${}", 
                    String.format("%.6f", cost), inputTokens, outputTokens, String.format("%.6f", currentAccumulatedCost));

            if (currentAccumulatedCost > 0.50) {
                log.error("Budget exceeded: accumulated cost is ${}", String.format("%.6f", currentAccumulatedCost));
                throw new BudgetExceededException("Accumulated token cost of $" + String.format("%.4f", currentAccumulatedCost) + " exceeded cost limit of $0.50");
            }
        }
    }

    private static String resolveModelName() {
        String model = System.getProperty("deepseek.model");
        if (model == null || model.trim().isEmpty()) {
            model = System.getenv("DEEPSEEK_MODEL");
        }
        if (model == null || model.trim().isEmpty()) {
            model = "deepseek-chat";
        }
        return model.trim().toLowerCase();
    }
}
