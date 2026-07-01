package io.audienceflow.api.cameras;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CameraRepository {
    private final JdbcTemplate jdbcTemplate;

    public CameraRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Camera> findAll() {
        return jdbcTemplate.query(
                """
                SELECT
                    c.id,
                    c.room_id,
                    r.name AS room_name,
                    c.name,
                    c.source_url,
                    c.stream_type,
                    c.status,
                    c.enabled,
                    c.last_seen_at,
                    c.created_at,
                    c.updated_at
                FROM cameras c
                JOIN rooms r ON r.id = c.room_id
                ORDER BY r.name, c.name
                """,
                this::mapCamera
        );
    }

    public Camera create(CameraRequest request) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO cameras (room_id, name, source_url, stream_type, status, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING
                    id,
                    room_id,
                    (SELECT name FROM rooms WHERE id = cameras.room_id) AS room_name,
                    name,
                    source_url,
                    stream_type,
                    status,
                    enabled,
                    last_seen_at,
                    created_at,
                    updated_at
                """,
                this::mapCamera,
                request.roomId(),
                request.name().trim(),
                request.sourceUrl().trim(),
                request.streamType(),
                request.status(),
                request.enabled()
        );
    }

    public Camera update(int id, CameraRequest request) {
        return jdbcTemplate.queryForObject(
                """
                UPDATE cameras
                SET room_id = ?, name = ?, source_url = ?, stream_type = ?, status = ?, enabled = ?, updated_at = now()
                WHERE id = ?
                RETURNING
                    id,
                    room_id,
                    (SELECT name FROM rooms WHERE id = cameras.room_id) AS room_name,
                    name,
                    source_url,
                    stream_type,
                    status,
                    enabled,
                    last_seen_at,
                    created_at,
                    updated_at
                """,
                this::mapCamera,
                request.roomId(),
                request.name().trim(),
                request.sourceUrl().trim(),
                request.streamType(),
                request.status(),
                request.enabled(),
                id
        );
    }

    public void delete(int id) {
        jdbcTemplate.update("DELETE FROM cameras WHERE id = ?", id);
    }

    private Camera mapCamera(ResultSet rs, int rowNum) throws SQLException {
        return new Camera(
                rs.getInt("id"),
                rs.getInt("room_id"),
                rs.getString("room_name"),
                rs.getString("name"),
                rs.getString("source_url"),
                rs.getString("stream_type"),
                rs.getString("status"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("last_seen_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
