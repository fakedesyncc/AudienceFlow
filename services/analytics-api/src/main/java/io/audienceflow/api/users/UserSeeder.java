package io.audienceflow.api.users;

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
                new SeedUser(adminEmail, "Администратор AudienceFlow", Role.ADMIN, adminPassword),
                new SeedUser(technicianEmail, "Техник AudienceFlow", Role.TECHNICIAN, technicianPassword),
                new SeedUser(teacherEmail, "Преподаватель AudienceFlow", Role.TEACHER, teacherPassword)
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
        if ("admin".equalsIgnoreCase(user.email()) || "admin".equals(user.password())) {
            throw new IllegalStateException("Default admin/admin style credentials are not allowed");
        }
        if (user.password().length() < 12) {
            throw new IllegalStateException(user.role() + " seed password must be at least 12 characters");
        }
    }

    private record SeedUser(String email, String displayName, Role role, String password) {
    }
}
