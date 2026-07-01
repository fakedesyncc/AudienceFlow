package io.audienceflow.api.attendance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceRepository {
    private final JdbcTemplate jdbcTemplate;

    public AttendanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CurrentAttendance> current() {
        return jdbcTemplate.query(
                """
                SELECT
                    r.id AS room_id,
                    r.name AS room_name,
                    r.building,
                    r.floor,
                    r.capacity,
                    COALESCE(latest.count, 0) AS current_count,
                    COALESCE(latest.confidence, 0) AS confidence,
                    latest.ts
                FROM rooms r
                LEFT JOIN LATERAL (
                    SELECT count, confidence, ts
                    FROM attendance a
                    WHERE a.room_id = r.id
                    ORDER BY ts DESC
                    LIMIT 1
                ) latest ON true
                ORDER BY r.building, r.floor, r.name
                """,
                this::mapCurrent
        );
    }

    public AttendanceSummary summary(int roomId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*)::int AS samples,
                    COALESCE(round(avg(count)), 0)::int AS average_count,
                    COALESCE(max(count), 0)::int AS peak_count,
                    COALESCE(round(avg(confidence)::numeric, 3), 0)::float8 AS average_confidence
                FROM attendance
                WHERE room_id = ? AND ts >= ? AND ts <= ?
                """,
                (rs, rowNum) -> new AttendanceSummary(
                        roomId,
                        from,
                        to,
                        rs.getInt("samples"),
                        rs.getInt("average_count"),
                        rs.getInt("peak_count"),
                        rs.getDouble("average_confidence")
                ),
                roomId,
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

    public List<TimelinePoint> timeline(int roomId, Instant from, Instant to, int bucketMinutes) {
        int bucketSeconds = bucketMinutes * 60;
        return jdbcTemplate.query(
                """
                SELECT
                    to_timestamp(floor(extract(epoch FROM ts) / ?) * ?) AS bucket,
                    round(avg(count))::int AS avg_count,
                    max(count)::int AS peak_count,
                    round(avg(confidence)::numeric, 3)::float8 AS avg_confidence
                FROM attendance
                WHERE room_id = ? AND ts >= ? AND ts <= ?
                GROUP BY bucket
                ORDER BY bucket
                """,
                this::mapTimeline,
                bucketSeconds,
                bucketSeconds,
                roomId,
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

    private CurrentAttendance mapCurrent(ResultSet rs, int rowNum) throws SQLException {
        int capacity = rs.getInt("capacity");
        int count = rs.getInt("current_count");
        int percent = capacity <= 0 ? 0 : (int) Math.round((count * 100.0) / capacity);
        return new CurrentAttendance(
                rs.getInt("room_id"),
                rs.getString("room_name"),
                rs.getString("building"),
                rs.getString("floor"),
                capacity,
                count,
                rs.getDouble("confidence"),
                toInstant(rs.getTimestamp("ts")),
                percent,
                status(percent)
        );
    }

    private TimelinePoint mapTimeline(ResultSet rs, int rowNum) throws SQLException {
        return new TimelinePoint(
                toInstant(rs.getTimestamp("bucket")),
                rs.getInt("avg_count"),
                rs.getInt("peak_count"),
                rs.getDouble("avg_confidence")
        );
    }

    private static String status(int occupancyPercent) {
        if (occupancyPercent >= 95) {
            return "full";
        }
        if (occupancyPercent >= 80) {
            return "warning";
        }
        return "normal";
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
