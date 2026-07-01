package io.audienceflow.api.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 14;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "admin",
            "administrator",
            "password",
            "password123",
            "qwerty",
            "qwerty123",
            "123456789012",
            "audienceflow",
            "audienceflow2026"
    );

    private PasswordPolicy() {
    }

    public static void requireStrongUserPassword(String email, String password) {
        if (!StringUtils.hasText(email) || "admin".equalsIgnoreCase(email.trim())) {
            throw new IllegalArgumentException("User email must not be a default administrator login");
        }
        requireStrong(password);

        String localPart = email.trim().toLowerCase(Locale.ROOT).split("@", 2)[0];
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        if (localPart.length() >= 4 && normalizedPassword.contains(localPart)) {
            throw new IllegalArgumentException("Password must not contain the email local part");
        }
    }

    public static void requireStrong(String password) {
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Password must be between 14 and 128 characters");
        }

        String normalized = password.trim().toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(normalized)
                || normalized.contains("password")
                || normalized.contains("qwerty")) {
            throw new IllegalArgumentException("Password is too common");
        }

        int classes = 0;
        classes += password.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch)) ? 1 : 0;
        if (classes < 3) {
            throw new IllegalArgumentException("Password must use at least three character classes");
        }
    }
}
