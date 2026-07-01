package io.audienceflow.api.users;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        Role role,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
