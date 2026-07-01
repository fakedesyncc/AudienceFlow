package io.audienceflow.api.cameras;

import java.time.Instant;

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
    public Camera masked() {
        return new Camera(id, roomId, roomName, name, null, streamType, status, enabled, lastSeenAt, createdAt, updatedAt);
    }
}
