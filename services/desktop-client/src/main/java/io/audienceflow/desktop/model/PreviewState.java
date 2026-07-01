package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewState(
        boolean ready,
        String workerId,
        int roomId,
        String source,
        String detector,
        int count,
        int rawCount,
        double confidence,
        double fps,
        List<PreviewDetection> detections,
        Instant updatedAt
) {
    public static PreviewState empty() {
        return new PreviewState(false, "", 0, "", "", 0, 0, 0.0, 0.0, List.of(), null);
    }
}
