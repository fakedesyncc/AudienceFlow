package io.audienceflow.api.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    void acceptsStrongUserPassword() {
        assertThatCode(() -> PasswordPolicy.requireStrongUserPassword(
                "teacher@example.edu",
                "Af!2026SecureRoom"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsDefaultAdministratorLogin() {
        assertThatThrownBy(() -> PasswordPolicy.requireStrongUserPassword("admin", "Af!2026SecureRoom"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default administrator");
    }

    @Test
    void rejectsCommonPassword() {
        assertThatThrownBy(() -> PasswordPolicy.requireStrong("password123456!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void rejectsPasswordContainingEmailLocalPart() {
        assertThatThrownBy(() -> PasswordPolicy.requireStrongUserPassword(
                "operator@example.edu",
                "Operator!2026Pass"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email local part");
    }
}
