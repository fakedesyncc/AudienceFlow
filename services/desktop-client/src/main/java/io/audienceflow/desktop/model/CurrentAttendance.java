package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
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
