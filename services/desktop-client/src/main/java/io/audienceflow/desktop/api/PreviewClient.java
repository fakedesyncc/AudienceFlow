package io.audienceflow.desktop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.audienceflow.desktop.model.PreviewState;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PreviewClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final String baseUrl;
    private final String token;

    public PreviewClient(String baseUrl, String token) {
        this.baseUrl = normalize(baseUrl);
        this.token = token == null ? "" : token.trim();
    }

    public CompletableFuture<byte[]> frame() {
        return httpClient.sendAsync(request("/v1/frame.jpg"), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode(), response.body().length + " bytes");
                    return response.body();
                });
    }

    public CompletableFuture<PreviewState> state() {
        return httpClient.sendAsync(request("/v1/state"), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode(), response.body());
                    try {
                        return objectMapper.readValue(response.body(), PreviewState.class);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private HttpRequest request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(4))
                .GET();
        if (!token.isBlank()) {
            builder.header("X-Preview-Token", token);
        }
        return builder.build();
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new CompletionException(new IOException("Preview HTTP " + statusCode + ": " + body));
    }

    private static String normalize(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isBlank()) {
            throw new IllegalArgumentException("Укажите Preview URL");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/") && url.length() > 1) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
