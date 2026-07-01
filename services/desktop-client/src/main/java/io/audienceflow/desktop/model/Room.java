package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Room(
        int id,
        String name,
        String building,
        String floor,
        int capacity,
        Instant createdAt,
        Instant updatedAt
) {
    @Override
    public String toString() {
        return name;
    }
}
