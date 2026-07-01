package io.audienceflow.api.attendance;

import java.time.Instant;

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
