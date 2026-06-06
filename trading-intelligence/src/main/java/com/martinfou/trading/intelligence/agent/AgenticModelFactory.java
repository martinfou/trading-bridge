package com.martinfou.trading.intelligence.agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for creating ChatLanguageModel instances dynamically based on the environment (Epic 25.1).
 * Supports DeepSeek API (using OpenAI compatibility layer) and local Ollama.
 */
public final class AgenticModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgenticModelFactory.class);

    // Configuration keys (system property and environment variable)
    public static final String SYS_DEEPSEEK_API_KEY = "deepseek.api.key";
    public static final String ENV_DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";

    public static final String SYS_DEEPSEEK_MODEL = "deepseek.model";
    public static final String ENV_DEEPSEEK_MODEL = "DEEPSEEK_MODEL";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";

    public static final String SYS_DEEPSEEK_API_URL = "deepseek.api.url";
    public static final String ENV_DEEPSEEK_API_URL = "DEEPSEEK_API_URL";
    public static final String DEFAULT_DEEPSEEK_API_URL = "https://api.deepseek.com";

    public static final String SYS_OLLAMA_MODEL = "ollama.model";
    public static final String ENV_OLLAMA_MODEL = "OLLAMA_MODEL";
    public static final String DEFAULT_OLLAMA_MODEL = "deepseek-r1:8b";

    public static final String SYS_OLLAMA_HOST = "ollama.host";
    public static final String ENV_OLLAMA_HOST = "OLLAMA_HOST";
    public static final String DEFAULT_OLLAMA_HOST = "http://localhost:11434";

    public static final String SYS_AGENTIC_TIMEOUT = "agentic.timeout";
    public static final String ENV_AGENTIC_TIMEOUT = "AGENTIC_TIMEOUT";
    public static final int DEFAULT_AGENTIC_TIMEOUT = 40;

    private AgenticModelFactory() {
        // Prevent instantiation
    }

    /**
     * Creates a ChatLanguageModel using environment variables and JVM system properties.
     * Overrides are prioritised: System properties > Environment variables.
     *
     * @return the ChatLanguageModel instance
     */
    public static ChatLanguageModel createChatModel() {
        String apiKey = getSetting(SYS_DEEPSEEK_API_KEY, ENV_DEEPSEEK_API_KEY, null);
        String deepseekModel = getSetting(SYS_DEEPSEEK_MODEL, ENV_DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL);
        String deepseekUrl = getSetting(SYS_DEEPSEEK_API_URL, ENV_DEEPSEEK_API_URL, DEFAULT_DEEPSEEK_API_URL);
        
        String ollamaHost = getSetting(SYS_OLLAMA_HOST, ENV_OLLAMA_HOST, DEFAULT_OLLAMA_HOST);
        String ollamaModel = getSetting(SYS_OLLAMA_MODEL, ENV_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL);
        
        String timeoutStr = getSetting(SYS_AGENTIC_TIMEOUT, ENV_AGENTIC_TIMEOUT, null);
        int timeoutSeconds = DEFAULT_AGENTIC_TIMEOUT;
        if (timeoutStr != null) {
            try {
                timeoutSeconds = Integer.parseInt(timeoutStr.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid timeout value '{}', using default of {}s", timeoutStr, DEFAULT_AGENTIC_TIMEOUT);
            }
        }

        return createChatModel(apiKey, deepseekModel, deepseekUrl, ollamaHost, ollamaModel, timeoutSeconds);
    }

    /**
     * Creates a ChatLanguageModel using direct parameters (internal/test entry point).
     */
    static ChatLanguageModel createChatModel(
            String apiKey,
            String deepseekModel,
            String deepseekUrl,
            String ollamaHost,
            String ollamaModel,
            int timeoutSeconds) {

        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.trim().equalsIgnoreCase("local")) {
            String cleanApiKey = apiKey.trim();
            String cleanUrl = deepseekUrl.trim();
            String cleanModel = deepseekModel.trim();
            log.info("Creating OpenAiChatModel pointing to DeepSeek API at {} (model: {}, timeout: {}s)", 
                    cleanUrl, cleanModel, timeoutSeconds);
            return OpenAiChatModel.builder()
                    .apiKey(cleanApiKey)
                    .baseUrl(cleanUrl)
                    .modelName(cleanModel)
                    .timeout(timeout)
                    .build();
        } else {
            String host = ollamaHost != null ? ollamaHost.trim() : DEFAULT_OLLAMA_HOST;
            if (host.isEmpty()) {
                host = DEFAULT_OLLAMA_HOST;
            }
            // Add scheme prefix if missing
            if (!host.contains("://")) {
                host = "http://" + host;
            }
            String cleanModel = ollamaModel != null ? ollamaModel.trim() : DEFAULT_OLLAMA_MODEL;
            if (cleanModel.isEmpty()) {
                cleanModel = DEFAULT_OLLAMA_MODEL;
            }

            if (apiKey == null || apiKey.trim().isEmpty()) {
                log.info("DEEPSEEK_API_KEY is absent. Defaulting to local Ollama at {} (model: {}, timeout: {}s)", 
                        host, cleanModel, timeoutSeconds);
            } else {
                log.info("DEEPSEEK_API_KEY is configured as local. Creating local Ollama model at {} (model: {}, timeout: {}s)", 
                        host, cleanModel, timeoutSeconds);
            }

            return OllamaChatModel.builder()
                    .baseUrl(host)
                    .modelName(cleanModel)
                    .timeout(timeout)
                    .build();
        }
    }

    private static String getSetting(String sysProp, String envVar, String defaultValue) {
        String val = System.getProperty(sysProp);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        val = System.getenv(envVar);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        return defaultValue;
    }
}
