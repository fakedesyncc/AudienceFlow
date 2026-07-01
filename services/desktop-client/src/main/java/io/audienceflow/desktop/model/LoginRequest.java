package io.audienceflow.desktop.model;

public record LoginRequest(
        String email,
        String password
) {
}
