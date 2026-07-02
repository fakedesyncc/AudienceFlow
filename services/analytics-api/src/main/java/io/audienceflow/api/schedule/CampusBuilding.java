package io.audienceflow.api.schedule;

import java.time.Instant;

public record CampusBuilding(
        int id,
        String code,
        String name,
        String address,
        String roomRanges,
        double mapX,
        double mapY,
        String color,
        String sourceUrl,
        Instant updatedAt
) {
}
