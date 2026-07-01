package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AttendanceSummary(
        int roomId,
        Instant from,
        Instant to,
        int samples,
        int averageCount,
        int peakCount,
        double averageConfidence
) {
}
