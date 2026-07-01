package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewLine(
        int x1,
        int y1,
        int x2,
        int y2
) {
    public String label() {
        return x1 + "," + y1 + " -> " + x2 + "," + y2;
    }
}
