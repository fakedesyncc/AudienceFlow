package io.audienceflow.api.attendance;

import java.time.Instant;

public record CurrentAttendance(
        int roomId,
        String roomName,
        String building,
        String floor,
        int capacity,
        int count,
        double confidence,
        Instant timestamp,
        int occupancyPercent,
        String status
) {
}
