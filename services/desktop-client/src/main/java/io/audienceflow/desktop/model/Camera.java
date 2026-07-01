package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Camera(
        int id,
        int roomId,
        String roomName,
        String name,
        String sourceUrl,
        String streamType,
        String status,
        boolean enabled,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {
}
