package io.audienceflow.api.schedule;

import java.util.List;

public record ScheduleDirectory(
        List<NamedEntity> groups,
        List<NamedEntity> teachers,
        List<NamedEntity> disciplines
) {
    public record NamedEntity(int id, String name, String detail) {
    }
}
