package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewDetection(
        int x,
        int y,
        int width,
        int height,
        double confidence,
        Integer trackId
) {
}
