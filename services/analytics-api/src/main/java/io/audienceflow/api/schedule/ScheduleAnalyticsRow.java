package io.audienceflow.api.schedule;

public record ScheduleAnalyticsRow(
        String dimension,
        int id,
        String name,
        int lessons,
        int plannedCapacity,
        int measuredLessons,
        int averageAttendance,
        int peakAttendance,
        double averageOccupancyPercent,
        double averageConfidence
) {
}
