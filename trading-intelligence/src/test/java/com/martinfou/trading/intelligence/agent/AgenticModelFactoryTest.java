package com.martinfou.trading.intelligence.agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgenticModelFactoryTest {

    @BeforeEach
    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(AgenticModelFactory.SYS_DEEPSEEK_API_KEY);
        System.clearProperty(AgenticModelFactory.SYS_DEEPSEEK_MODEL);
        System.clearProperty(AgenticModelFactory.SYS_DEEPSEEK_API_URL);
        System.clearProperty(AgenticModelFactory.SYS_OLLAMA_MODEL);
        System.clearProperty(AgenticModelFactory.SYS_OLLAMA_HOST);
        System.clearProperty(AgenticModelFactory.SYS_AGENTIC_TIMEOUT);
    }

    @Test
    void testCreateOpenAiChatModelWithApiKey() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel("sk-test-key", "deepseek-chat", "https://api.deepseek.com", "http://localhost:11434", "deepseek-r1:8b", 40);
        assertNotNull(model);
        assertTrue(model instanceof OpenAiChatModel, "Expected OpenAiChatModel when API key is provided");
        
        assertEquals("deepseek-chat", getField(model, "modelName"));
        // DeepOpenAiClient normalizes URL with trailing slash, so we check startsWith or normalise
        assertTrue(getBaseUrl(model).startsWith("https://api.deepseek.com"), "Base URL did not match DeepSeek API");
        assertEquals(Duration.ofSeconds(40), getTimeout(model));
    }

    @Test
    void testCreateOllamaChatModelWhenApiKeyIsAbsent() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel(null, "deepseek-chat", "https://api.deepseek.com", "http://localhost:11434", "deepseek-r1:8b", 40);
        assertNotNull(model);
        assertTrue(model instanceof OllamaChatModel, "Expected OllamaChatModel when API key is absent");

        assertEquals("deepseek-r1:8b", getField(model, "modelName"));
        assertTrue(getBaseUrl(model).startsWith("http://localhost:11434"), "Base URL did not match Ollama host");
        assertEquals(Duration.ofSeconds(40), getTimeout(model));
    }

    @Test
    void testCreateOllamaChatModelWhenApiKeyIsLocalWithWhitespace() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel("  local  ", "deepseek-chat", "https://api.deepseek.com", "http://localhost:11434", "deepseek-r1:8b", 40);
        assertNotNull(model);
        assertTrue(model instanceof OllamaChatModel, "Expected OllamaChatModel when API key is 'local' (whitespace trimmed)");
    }

    @Test
    void testCreateOllamaChatModelWithNullHostUsesDefault() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel(null, null, null, null, null, 40);
        assertNotNull(model);
        assertTrue(model instanceof OllamaChatModel, "Expected OllamaChatModel when API key and host are null");
        assertTrue(getBaseUrl(model).startsWith("http://localhost:11434"));
    }

    @Test
    void testOllamaHostAutoPrefixesHttpScheme() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel(null, null, null, "localhost:11434", null, 40);
        assertNotNull(model);
        assertTrue(getBaseUrl(model).startsWith("http://localhost:11434"), "Should auto-prefix http:// scheme");
    }

    @Test
    void testOllamaHostPreservesHttpsScheme() {
        ChatLanguageModel model = AgenticModelFactory.createChatModel(null, null, null, "https://my-ollama-server:11434", null, 40);
        assertNotNull(model);
        assertTrue(getBaseUrl(model).startsWith("https://my-ollama-server:11434"), "Should preserve https:// scheme");
    }

    @Test
    void testDefaultEntrypointWithSystemPropertiesOverride() {
        System.setProperty(AgenticModelFactory.SYS_DEEPSEEK_API_KEY, "sk-sys-prop-key");
        System.setProperty(AgenticModelFactory.SYS_DEEPSEEK_MODEL, "deepseek-reasoner");
        System.setProperty(AgenticModelFactory.SYS_DEEPSEEK_API_URL, "https://api.custom.deepseek.com");
        System.setProperty(AgenticModelFactory.SYS_AGENTIC_TIMEOUT, "25");

        ChatLanguageModel model = AgenticModelFactory.createChatModel();
        assertNotNull(model);
        assertTrue(model instanceof OpenAiChatModel, "Expected OpenAiChatModel from overridden properties");
        assertEquals("deepseek-reasoner", getField(model, "modelName"));
        assertTrue(getBaseUrl(model).startsWith("https://api.custom.deepseek.com"));
        assertEquals(Duration.ofSeconds(25), getTimeout(model));
    }

    @Test
    void testDefaultEntrypointOllamaOverride() {
        System.setProperty(AgenticModelFactory.SYS_DEEPSEEK_API_KEY, "local");
        System.setProperty(AgenticModelFactory.SYS_OLLAMA_MODEL, "llama3:latest");
        System.setProperty(AgenticModelFactory.SYS_OLLAMA_HOST, "localhost:8080");
        System.setProperty(AgenticModelFactory.SYS_AGENTIC_TIMEOUT, "10");

        ChatLanguageModel model = AgenticModelFactory.createChatModel();
        assertNotNull(model);
        assertTrue(model instanceof OllamaChatModel, "Expected OllamaChatModel from overridden properties");
        assertEquals("llama3:latest", getField(model, "modelName"));
        assertTrue(getBaseUrl(model).startsWith("http://localhost:8080"));
        assertEquals(Duration.ofSeconds(10), getTimeout(model));
    }

    private Object getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException e) {
            // Check superclass
            try {
                Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Exception ex) {
                throw new RuntimeException("Could not find field " + fieldName + " on class " + target.getClass().getName(), ex);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getBaseUrl(ChatLanguageModel model) {
        if (model instanceof OpenAiChatModel) {
            Object client = getField(model, "client");
            return (String) getField(client, "baseUrl");
        } else if (model instanceof OllamaChatModel) {
            Object client = getField(model, "client");
            Object api = getField(client, "ollamaApi");
            return getRetrofitBaseUrl(api);
        }
        return null;
    }

    private String getRetrofitBaseUrl(Object apiProxy) {
        try {
            java.lang.reflect.InvocationHandler handler = java.lang.reflect.Proxy.getInvocationHandler(apiProxy);
            Field retrofitField = handler.getClass().getDeclaredField("this$0");
            retrofitField.setAccessible(true);
            Object retrofit = retrofitField.get(handler);
            Object baseUrl = getField(retrofit, "baseUrl");
            return baseUrl.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Duration getTimeout(ChatLanguageModel model) {
        if (model instanceof OpenAiChatModel) {
            Object client = getField(model, "client");
            okhttp3.OkHttpClient httpClient = (okhttp3.OkHttpClient) getField(client, "okHttpClient");
            return Duration.ofMillis(httpClient.connectTimeoutMillis());
        } else if (model instanceof OllamaChatModel) {
            Object client = getField(model, "client");
            Object api = getField(client, "ollamaApi");
            try {
                java.lang.reflect.InvocationHandler handler = java.lang.reflect.Proxy.getInvocationHandler(api);
                Field retrofitField = handler.getClass().getDeclaredField("this$0");
                retrofitField.setAccessible(true);
                Object retrofit = retrofitField.get(handler);
                Object callFactory = getField(retrofit, "callFactory");
                if (callFactory instanceof okhttp3.OkHttpClient) {
                    return Duration.ofMillis(((okhttp3.OkHttpClient) callFactory).connectTimeoutMillis());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }
}
