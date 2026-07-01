package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveAttendanceMessage(
        Instant generatedAt,
        List<CurrentAttendance> rooms
) {
}
