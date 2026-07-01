package io.audienceflow.api.rooms;

import java.time.Instant;

public record Room(
        int id,
        String name,
        String building,
        String floor,
        int capacity,
        Instant createdAt,
        Instant updatedAt
) {
}
