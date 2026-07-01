package io.audienceflow.desktop.model;

public record CameraRequest(
        int roomId,
        String name,
        String sourceUrl,
        String streamType,
        String status,
        Boolean enabled
) {
}
