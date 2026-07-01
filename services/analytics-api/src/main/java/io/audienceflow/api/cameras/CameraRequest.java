package io.audienceflow.api.cameras;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CameraRequest(
        @Positive int roomId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 1000) String sourceUrl,
        @NotBlank @Pattern(regexp = "rtsp|http|device|simulation") String streamType,
        @NotBlank @Pattern(regexp = "online|offline|maintenance") String status,
        @NotNull Boolean enabled
) {
}
