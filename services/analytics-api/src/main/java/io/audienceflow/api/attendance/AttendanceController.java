package io.audienceflow.api.attendance;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/attendance")
@PreAuthorize("hasAnyRole('TEACHER', 'TECHNICIAN', 'ADMIN')")
public class AttendanceController {
    private final AttendanceRepository attendanceRepository;

    public AttendanceController(AttendanceRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    @GetMapping("/current")
    public List<CurrentAttendance> current() {
        return attendanceRepository.current();
    }

    @GetMapping("/summary")
    public AttendanceSummary summary(
            @RequestParam int roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Instant resolvedTo = to == null ? Instant.now() : to;
        Instant resolvedFrom = from == null ? resolvedTo.minus(24, ChronoUnit.HOURS) : from;
        return attendanceRepository.summary(roomId, resolvedFrom, resolvedTo);
    }

    @GetMapping("/timeline")
    public List<TimelinePoint> timeline(
            @RequestParam int roomId,
            @RequestParam(defaultValue = "5") int bucketMinutes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Instant resolvedTo = to == null ? Instant.now() : to;
        Instant resolvedFrom = from == null ? resolvedTo.minus(24, ChronoUnit.HOURS) : from;
        int resolvedBucket = Math.max(1, Math.min(bucketMinutes, 120));
        return attendanceRepository.timeline(roomId, resolvedFrom, resolvedTo, resolvedBucket);
    }
}
