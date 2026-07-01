package io.audienceflow.api.attendance;

import java.time.Instant;

public record TimelinePoint(
        Instant bucket,
        int avgCount,
        int peakCount,
        double avgConfidence
) {
}
