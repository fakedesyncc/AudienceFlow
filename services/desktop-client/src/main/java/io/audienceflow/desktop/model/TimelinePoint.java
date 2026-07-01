package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TimelinePoint(
        Instant bucket,
        int avgCount,
        int peakCount,
        double avgConfidence
) {
}
