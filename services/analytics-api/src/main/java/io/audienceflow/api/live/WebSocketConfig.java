package io.audienceflow.api.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final LiveAttendanceSocketHandler liveAttendanceSocketHandler;

    public WebSocketConfig(LiveAttendanceSocketHandler liveAttendanceSocketHandler) {
        this.liveAttendanceSocketHandler = liveAttendanceSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveAttendanceSocketHandler, "/ws/live")
                .setAllowedOriginPatterns("*");
    }
}
