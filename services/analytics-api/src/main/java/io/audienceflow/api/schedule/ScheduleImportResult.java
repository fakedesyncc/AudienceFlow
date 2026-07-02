package io.audienceflow.api.schedule;

import java.util.List;

public record ScheduleImportResult(
        int parsedRows,
        int importedRows,
        int skippedRows,
        List<String> warnings
) {
}
