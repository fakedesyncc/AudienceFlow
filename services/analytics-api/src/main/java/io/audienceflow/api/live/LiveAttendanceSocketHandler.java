package io.audienceflow.api.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.audienceflow.api.attendance.AttendanceRepository;
import io.audienceflow.api.security.JwtService;
import io.audienceflow.api.users.UserRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LiveAttendanceSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
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
        sessions.put(session.getId(), session);
        sendSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
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
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                sessions.remove(session.getId());
            }
        });
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
