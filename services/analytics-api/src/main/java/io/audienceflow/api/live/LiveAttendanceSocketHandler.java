package io.audienceflow.api.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.audienceflow.api.attendance.AttendanceRepository;
import io.audienceflow.api.security.JwtService;
import io.audienceflow.api.users.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LiveAttendanceSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveAttendanceSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionTokens = new ConcurrentHashMap<>();
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AttendanceRepository attendanceRepository;
    private final ObjectMapper objectMapper;

    public LiveAttendanceSocketHandler(
            JwtService jwtService,
            UserRepository userRepository,
            AttendanceRepository attendanceRepository,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.attendanceRepository = attendanceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = tokenFrom(session.getUri());
        if (!validToken(token)) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid token"));
            return;
        }
        // Wrap the raw session so slow/stalled consumers cannot block the shared scheduler thread
        // and concurrent sends (broadcast + snapshot) are serialized instead of throwing.
        WebSocketSession managed =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        sessionTokens.put(session.getId(), token);
        sessions.put(session.getId(), managed);
        sendSnapshot(managed);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionTokens.remove(session.getId());
    }

    @Scheduled(fixedDelayString = "${app.live.interval-ms:5000}")
    public void broadcast() {
        if (sessions.isEmpty()) {
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(snapshot());
        } catch (IOException e) {
            return;
        }

        sessions.values().forEach(session -> {
            // Re-validate the token on every tick: a JWT that expired or a user that was
            // revoked/deactivated after connect must be dropped instead of streamed to.
            if (!validToken(sessionTokens.get(session.getId()))) {
                closeAndEvict(session, CloseStatus.NOT_ACCEPTABLE.withReason("session no longer authorized"));
                return;
            }
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                // One bad/slow session (IOException, IllegalStateException from a full send
                // buffer, etc.) must never abort the broadcast to the remaining sessions.
                log.warn("Evicting live session {} after send failure: {}", session.getId(), e.toString());
                closeAndEvict(session, CloseStatus.SESSION_NOT_RELIABLE);
            }
        });
    }

    private void closeAndEvict(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionTokens.remove(session.getId());
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // Best-effort close; the session is already removed from the broadcast set.
        }
    }

    private void sendSnapshot(WebSocketSession session) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(snapshot())));
    }

    private LiveAttendanceMessage snapshot() {
        return new LiveAttendanceMessage(Instant.now(), attendanceRepository.current());
    }

    private boolean validToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            String email = jwtService.subject(token);
            return userRepository.findByEmail(email).filter(user -> user.active()).isPresent();
        } catch (ExpiredJwtException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String tokenFrom(URI uri) {
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals("token")) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
