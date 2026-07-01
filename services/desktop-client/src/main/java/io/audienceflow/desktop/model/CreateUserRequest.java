package io.audienceflow.desktop.model;

public record CreateUserRequest(
        String email,
        String displayName,
        Role role,
        String password
) {
}
