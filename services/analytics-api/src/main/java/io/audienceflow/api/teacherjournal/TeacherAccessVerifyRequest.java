package io.audienceflow.api.teacherjournal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TeacherAccessVerifyRequest(
        @NotNull @Min(1) Integer teacherId,
        @NotBlank @Size(min = 12, max = 256) String accessKey
) {
}
