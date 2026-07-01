package io.audienceflow.api.users;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> users = jdbcTemplate.query(
                """
                SELECT id, email, display_name, password_hash, role, active, created_at, updated_at
                FROM app_users
                WHERE lower(email) = lower(?)
                """,
                this::mapUser,
                email
        );
        return users.stream().findFirst();
    }

    public List<UserAccount> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, email, display_name, password_hash, role, active, created_at, updated_at
                FROM app_users
                ORDER BY role, display_name
                """,
                this::mapUser
        );
    }

    public UserAccount create(String email, String displayName, Role role, String passwordHash) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_users (email, display_name, role, password_hash)
                VALUES (?, ?, ?, ?)
                RETURNING id, email, display_name, password_hash, role, active, created_at, updated_at
                """,
                this::mapUser,
                email.trim().toLowerCase(),
                displayName.trim(),
                role.name(),
                passwordHash
        );
    }

    public void upsertSeedUser(String email, String displayName, Role role, String passwordHash) {
        Optional<UserAccount> existing = findByEmail(email);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE app_users
                    SET display_name = ?, role = ?, password_hash = ?, active = true, updated_at = now()
                    WHERE id = ?
                    """,
                    displayName,
                    role.name(),
                    passwordHash,
                    existing.get().id()
            );
            return;
        }
        create(email, displayName, role, passwordHash);
    }

    private UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
