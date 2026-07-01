package io.audienceflow.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserView(
        UUID id,
        String email,
        String displayName,
        Role role,
        boolean active
) {
}
