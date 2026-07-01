package io.audienceflow.api.rooms;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 120) String building,
        @NotBlank @Size(max = 24) String floor,
        @Min(1) @Max(10000) int capacity
) {
}
