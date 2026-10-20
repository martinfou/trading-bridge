package com.martinfou.trading.runtime.hermes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts JSON to hermes-web with the bearer token that VerifyHealthToken checks. */
public final class HttpHermesSender implements HermesIbkrPublisher.Sender {

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpHermesSender(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("HERMES_WEB_URL and HERMES_WEB_TOKEN are required");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public Response post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
