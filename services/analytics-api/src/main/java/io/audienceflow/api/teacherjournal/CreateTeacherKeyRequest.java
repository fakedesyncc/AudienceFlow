package io.audienceflow.api.teacherjournal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTeacherKeyRequest(
        @NotNull @Min(1) Integer teacherId,
        @Size(max = 120) String label
) {
}
