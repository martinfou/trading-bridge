package com.martinfou.trading.intelligence.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI-compatible DeepSeek chat completions client (Epic 22.2). */
public final class HttpDeepSeekClient implements LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final String ENV_API_KEY = "DEEPSEEK_API_KEY";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI completionsUri;
    private final String apiKey;
    private final String model;

    public HttpDeepSeekClient() {
        this(fromEnv(ENV_API_KEY), DEFAULT_BASE_URL, DEFAULT_MODEL, HttpClient.newHttpClient(), new ObjectMapper());
    }

    HttpDeepSeekClient(String apiKey, String baseUrl, String model, HttpClient httpClient, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.completionsUri = URI.create(baseUrl.replaceAll("/$", "") + "/v1/chat/completions");
        this.model = model;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature) throws LlmException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("Missing " + ENV_API_KEY);
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder(completionsUri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("DeepSeek HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("DeepSeek returned empty content");
            }
            return content.asText();
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmException("DeepSeek request failed", ex);
        }
    }

    private static String fromEnv(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
