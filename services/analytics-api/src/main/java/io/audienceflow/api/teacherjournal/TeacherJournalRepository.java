package io.audienceflow.api.teacherjournal;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

@Repository
public class TeacherJournalRepository {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public TeacherJournalRepository(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<TeacherAccessVerification> verify(int teacherId, String accessKey) {
        List<TeacherKeyRow> rows = jdbcTemplate.query(
                """
                SELECT k.id, k.key_hash, t.full_name
                FROM teacher_access_keys k
                JOIN teachers t ON t.id = k.teacher_id
                WHERE k.teacher_id = ? AND k.active = true
                ORDER BY k.created_at DESC
                """,
                this::mapKey,
                teacherId
        );
        for (TeacherKeyRow row : rows) {
            if (passwordEncoder.matches(accessKey, row.keyHash())) {
                jdbcTemplate.update("UPDATE teacher_access_keys SET last_used_at = now() WHERE id = ?", row.id());
                return Optional.of(new TeacherAccessVerification(true, teacherId, row.teacherName()));
            }
        }
        return Optional.empty();
    }

    public TeacherKeyIssueResponse issueKey(int teacherId, String label) {
        String teacherName = jdbcTemplate.query(
                "SELECT full_name FROM teachers WHERE id = ?",
                rs -> rs.next() ? rs.getString("full_name") : null,
                teacherId
        );
        if (teacherName == null) {
            throw new IllegalArgumentException("Teacher not found");
        }
        String accessKey = generateKey();
        jdbcTemplate.update(
                """
                INSERT INTO teacher_access_keys (teacher_id, key_hash, label)
                VALUES (?, ?, ?)
                """,
                teacherId,
                passwordEncoder.encode(accessKey),
                label == null ? "" : label.trim()
        );
        return new TeacherKeyIssueResponse(teacherId, teacherName, accessKey);
    }

    private String generateKey() {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return "AULA-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private TeacherKeyRow mapKey(ResultSet rs, int rowNum) throws SQLException {
        return new TeacherKeyRow(
                rs.getLong("id"),
                rs.getString("key_hash"),
                rs.getString("full_name")
        );
    }

    private record TeacherKeyRow(long id, String keyHash, String teacherName) {
    }
}
