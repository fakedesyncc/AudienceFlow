package io.audienceflow.api.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleEntryView(
        long id,
        LocalDate date,
        int weekday,
        String weekType,
        LocalTime startsAt,
        LocalTime endsAt,
        String lessonType,
        String subgroup,
        int roomId,
        String roomName,
        String building,
        String floor,
        Integer buildingId,
        String buildingCode,
        String buildingName,
        int groupId,
        String groupName,
        String institute,
        int teacherId,
        String teacherName,
        String department,
        int disciplineId,
        String disciplineName,
        int capacity,
        Integer actualCount,
        Integer occupancyPercent,
        Double confidence,
        Instant measuredAt
) {
}
