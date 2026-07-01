package io.audienceflow.api.users;

import io.audienceflow.api.security.PasswordPolicy;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class UserSeeder implements ApplicationRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final List<SeedUser> seedUsers;

    public UserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.admin-email}") String adminEmail,
            @Value("${app.seed.admin-password}") String adminPassword,
            @Value("${app.seed.technician-email}") String technicianEmail,
            @Value("${app.seed.technician-password}") String technicianPassword,
            @Value("${app.seed.teacher-email}") String teacherEmail,
            @Value("${app.seed.teacher-password}") String teacherPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedUsers = List.of(
                new SeedUser(adminEmail, "Оператор системы", Role.ADMIN, adminPassword),
                new SeedUser(technicianEmail, "Инженер камер", Role.TECHNICIAN, technicianPassword),
                new SeedUser(teacherEmail, "Учебный просмотр", Role.TEACHER, teacherPassword)
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SeedUser user : seedUsers) {
            validateSeedUser(user);
            userRepository.upsertSeedUser(
                    user.email(),
                    user.displayName(),
                    user.role(),
                    passwordEncoder.encode(user.password())
            );
        }
    }

    private static void validateSeedUser(SeedUser user) {
        if (!StringUtils.hasText(user.email()) || !StringUtils.hasText(user.password())) {
            throw new IllegalStateException(user.role() + " seed email and password are required");
        }
        try {
            PasswordPolicy.requireStrongUserPassword(user.email(), user.password());
        } catch (IllegalArgumentException exc) {
            throw new IllegalStateException(user.role() + " seed credential rejected: " + exc.getMessage(), exc);
        }
    }

    private record SeedUser(String email, String displayName, Role role, String password) {
    }
}
