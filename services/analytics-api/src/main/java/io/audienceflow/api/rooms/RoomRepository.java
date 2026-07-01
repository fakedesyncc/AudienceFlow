package io.audienceflow.api.rooms;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRepository {
    private final JdbcTemplate jdbcTemplate;

    public RoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Room> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, name, building, floor, capacity, created_at, updated_at
                FROM rooms
                ORDER BY building, floor, name
                """,
                this::mapRoom
        );
    }

    public Room create(CreateRoomRequest request) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO rooms (name, building, floor, capacity)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, building, floor, capacity, created_at, updated_at
                """,
                this::mapRoom,
                request.name().trim(),
                request.building().trim(),
                request.floor().trim(),
                request.capacity()
        );
    }

    public Room update(int id, CreateRoomRequest request) {
        return jdbcTemplate.queryForObject(
                """
                UPDATE rooms
                SET name = ?, building = ?, floor = ?, capacity = ?, updated_at = now()
                WHERE id = ?
                RETURNING id, name, building, floor, capacity, created_at, updated_at
                """,
                this::mapRoom,
                request.name().trim(),
                request.building().trim(),
                request.floor().trim(),
                request.capacity(),
                id
        );
    }

    public void delete(int id) {
        jdbcTemplate.update("DELETE FROM rooms WHERE id = ?", id);
    }

    private Room mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new Room(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("building"),
                rs.getString("floor"),
                rs.getInt("capacity"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
