package io.audienceflow.api.users;

import java.util.UUID;

public record UserView(
        UUID id,
        String email,
        String displayName,
        Role role,
        boolean active
) {
    public static UserView from(UserAccount user) {
        return new UserView(user.id(), user.email(), user.displayName(), user.role(), user.active());
    }
}
