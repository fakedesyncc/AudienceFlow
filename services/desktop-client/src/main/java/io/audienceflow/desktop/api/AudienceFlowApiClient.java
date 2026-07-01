package io.audienceflow.desktop.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.audienceflow.desktop.model.AuthSession;
import io.audienceflow.desktop.model.AttendanceSummary;
import io.audienceflow.desktop.model.Camera;
import io.audienceflow.desktop.model.CameraRequest;
import io.audienceflow.desktop.model.CreateRoomRequest;
import io.audienceflow.desktop.model.CreateUserRequest;
import io.audienceflow.desktop.model.CurrentAttendance;
import io.audienceflow.desktop.model.LiveAttendanceMessage;
import io.audienceflow.desktop.model.LoginRequest;
import io.audienceflow.desktop.model.Room;
import io.audienceflow.desktop.model.TimelinePoint;
import io.audienceflow.desktop.model.UserView;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class AudienceFlowApiClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private volatile String apiBase;
    private volatile String token;

    public CompletableFuture<AuthSession> login(String apiUrl, String email, String password) {
        String normalizedBase = normalizeApiBase(apiUrl);
        this.token = null;
        return send(
                request(normalizedBase, "/auth/login")
                        .header("Content-Type", "application/json")
                        .POST(jsonBody(new LoginRequest(email, password)))
                        .build(),
                AuthSession.class
        ).thenApply(session -> {
            this.apiBase = normalizedBase;
            this.token = session.token();
            return session;
        });
    }

    public void clearSession() {
        token = null;
    }

    public CompletableFuture<List<CurrentAttendance>> current() {
        return get("/attendance/current", new TypeReference<>() {
        });
    }

    public CompletableFuture<AttendanceSummary> attendanceSummary(int roomId, Instant from, Instant to) {
        return get(
                "/attendance/summary?roomId=" + roomId
                        + "&from=" + url(from.toString())
                        + "&to=" + url(to.toString()),
                AttendanceSummary.class
        );
    }

    public CompletableFuture<List<TimelinePoint>> timeline(int roomId, int bucketMinutes, Instant from, Instant to) {
        return get(
                "/attendance/timeline?roomId=" + roomId
                        + "&bucketMinutes=" + bucketMinutes
                        + "&from=" + url(from.toString())
                        + "&to=" + url(to.toString()),
                new TypeReference<>() {
                }
        );
    }

    public CompletableFuture<List<Room>> rooms() {
        return get("/rooms", new TypeReference<>() {
        });
    }

    public CompletableFuture<Room> createRoom(CreateRoomRequest payload) {
        return post("/rooms", payload, Room.class);
    }

    public CompletableFuture<List<Camera>> cameras() {
        return get("/cameras", new TypeReference<>() {
        });
    }

    public CompletableFuture<Camera> createCamera(CameraRequest payload) {
        return post("/cameras", payload, Camera.class);
    }

    public CompletableFuture<Camera> updateCamera(int id, CameraRequest payload) {
        return put("/cameras/" + id, payload, Camera.class);
    }

    public CompletableFuture<List<UserView>> users() {
        return get("/admin/users", new TypeReference<>() {
        });
    }

    public CompletableFuture<UserView> createUser(CreateUserRequest payload) {
        return post("/admin/users", payload, UserView.class);
    }

    public CompletableFuture<LiveConnection> openLive(
            Consumer<List<CurrentAttendance>> onSnapshot,
            Consumer<String> onState
    ) {
        if (apiBase == null || token == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("API session is not open"));
        }

        WebSocket.Listener listener = new AttendanceSocketListener(objectMapper, onSnapshot, onState);
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(liveUri(), listener)
                .thenApply(LiveConnection::new);
    }

    private <T> CompletableFuture<T> get(String path, TypeReference<T> type) {
        return send(request(path).GET().build(), type);
    }

    private <T> CompletableFuture<T> get(String path, Class<T> type) {
        return send(request(path).GET().build(), type);
    }

    private <T> CompletableFuture<T> post(String path, Object payload, Class<T> type) {
        return send(
                request(path)
                        .header("Content-Type", "application/json")
                        .POST(jsonBody(payload))
                        .build(),
                type
        );
    }

    private <T> CompletableFuture<T> put(String path, Object payload, Class<T> type) {
        return send(
                request(path)
                        .header("Content-Type", "application/json")
                        .PUT(jsonBody(payload))
                        .build(),
                type
        );
    }

    private HttpRequest.Builder request(String path) {
        if (apiBase == null) {
            throw new IllegalStateException("API URL is not configured");
        }
        return request(apiBase, path);
    }

    private HttpRequest.Builder request(String base, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpRequest.BodyPublisher jsonBody(Object value) {
        try {
            return HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(value));
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private <T> CompletableFuture<T> send(HttpRequest request, Class<T> type) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(response, type));
    }

    private <T> CompletableFuture<T> send(HttpRequest request, TypeReference<T> type) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(response, type));
    }

    private <T> T parseResponse(HttpResponse<String> response, Class<T> type) {
        ensureSuccess(response);
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private <T> T parseResponse(HttpResponse<String> response, TypeReference<T> type) {
        ensureSuccess(response);
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        throw new CompletionException(new IOException("HTTP " + response.statusCode() + ": " + errorMessage(response.body())));
    }

    private String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "сервер не вернул описание ошибки";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            for (String field : List.of("detail", "message", "error")) {
                if (node.hasNonNull(field)) {
                    return node.get(field).asText();
                }
            }
        } catch (IOException ignored) {
            return body;
        }
        return body;
    }

    private URI liveUri() {
        URI base = URI.create(Objects.requireNonNull(apiBase));
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        String path = trimTrailingSlash(base.getPath()) + "/ws/live";
        try {
            return new URI(scheme, base.getUserInfo(), base.getHost(), base.getPort(), path, "token=" + token, null);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private static String normalizeApiBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Укажите API URL");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        return trimTrailingSlash(value);
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class AttendanceSocketListener implements WebSocket.Listener {
        private final ObjectMapper objectMapper;
        private final Consumer<List<CurrentAttendance>> onSnapshot;
        private final Consumer<String> onState;
        private final StringBuilder buffer = new StringBuilder();

        private AttendanceSocketListener(
                ObjectMapper objectMapper,
                Consumer<List<CurrentAttendance>> onSnapshot,
                Consumer<String> onState
        ) {
            this.objectMapper = objectMapper;
            this.onSnapshot = onSnapshot;
            this.onState = onState;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            onState.accept("live");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                try {
                    LiveAttendanceMessage message = objectMapper.readValue(payload, LiveAttendanceMessage.class);
                    onSnapshot.accept(message.rooms() == null ? List.of() : message.rooms());
                } catch (IOException e) {
                    onState.accept("live decode error");
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onState.accept("polling");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onState.accept("polling");
        }
    }
}
