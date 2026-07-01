package io.audienceflow.api.live;

import io.audienceflow.api.attendance.CurrentAttendance;
import java.time.Instant;
import java.util.List;

public record LiveAttendanceMessage(
        Instant generatedAt,
        List<CurrentAttendance> rooms
) {
}
