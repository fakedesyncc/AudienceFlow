package io.audienceflow.api.schedule;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbcTemplate;

    public ScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CampusBuilding> buildings() {
        return jdbcTemplate.query(
                """
                SELECT id, code, name, address, room_ranges, map_x, map_y, color, source_url, updated_at
                FROM campus_buildings
                ORDER BY CASE code
                    WHEN 'K1' THEN 1
                    WHEN 'K2' THEN 2
                    WHEN 'K3' THEN 3
                    WHEN 'K4' THEN 4
                    WHEN 'K5' THEN 5
                    WHEN 'K9' THEN 6
                    WHEN 'AUD' THEN 7
                    WHEN 'ADM' THEN 8
                    WHEN 'DORM' THEN 9
                    WHEN 'SPORT' THEN 10
                    WHEN 'CAFE' THEN 11
                    WHEN 'B' THEN 12
                    WHEN 'C' THEN 13
                    ELSE 99
                END, name
                """,
                this::mapBuilding
        );
    }

    public List<ScheduleEntryView> entries(LocalDate date, Integer buildingId, Integer roomId, Integer teacherId, Integer groupId) {
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        StringBuilder sql = new StringBuilder("""
                SELECT
                    se.id,
                    COALESCE(se.lesson_date, ?::date) AS lesson_date,
                    se.weekday,
                    se.week_type,
                    se.starts_at,
                    se.ends_at,
                    se.lesson_type,
                    se.subgroup,
                    r.id AS room_id,
                    r.name AS room_name,
                    r.building,
                    r.floor,
                    r.capacity,
                    cb.id AS building_id,
                    cb.code AS building_code,
                    cb.name AS building_name,
                    g.id AS group_id,
                    g.name AS group_name,
                    g.institute,
                    t.id AS teacher_id,
                    t.full_name AS teacher_name,
                    t.department,
                    d.id AS discipline_id,
                    d.name AS discipline_name,
                    latest.count AS actual_count,
                    latest.confidence,
                    latest.ts AS measured_at
                FROM schedule_entries se
                JOIN rooms r ON r.id = se.room_id
                LEFT JOIN campus_buildings cb ON cb.id = r.building_id
                JOIN student_groups g ON g.id = se.group_id
                JOIN teachers t ON t.id = se.teacher_id
                JOIN disciplines d ON d.id = se.discipline_id
                LEFT JOIN LATERAL (
                    SELECT a.count, a.confidence, a.ts
                    FROM attendance a
                    WHERE a.room_id = se.room_id
                      AND a.ts::date = ?::date
                      AND a.ts::time BETWEEN se.starts_at AND se.ends_at
                    ORDER BY a.ts DESC
                    LIMIT 1
                ) latest ON true
                WHERE (
                    se.lesson_date = ?::date
                    OR (
                        se.lesson_date IS NULL
                        AND se.weekday = EXTRACT(ISODOW FROM ?::date)::int
                        AND se.valid_from <= ?::date
                        AND (se.valid_to IS NULL OR se.valid_to >= ?::date)
                    )
                )
                """);
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        appendFilter(sql, args, "r.building_id", buildingId);
        appendFilter(sql, args, "r.id", roomId);
        appendFilter(sql, args, "t.id", teacherId);
        appendFilter(sql, args, "g.id", groupId);
        sql.append(" ORDER BY se.starts_at, r.building, r.floor, r.name, g.name");
        return jdbcTemplate.query(sql.toString(), this::mapEntry, args.toArray());
    }

    public List<ScheduleAnalyticsRow> analytics(LocalDate date, String dimension) {
        Dimension resolved = Dimension.from(dimension);
        return jdbcTemplate.query(
                """
                SELECT
                    ? AS dimension,
                    %s AS dimension_id,
                    %s AS dimension_name,
                    COUNT(se.id)::int AS lessons,
                    COALESCE(SUM(r.capacity), 0)::int AS planned_capacity,
                    COUNT(latest.count)::int AS measured_lessons,
                    COALESCE(round(avg(latest.count)), 0)::int AS average_attendance,
                    COALESCE(max(latest.count), 0)::int AS peak_attendance,
                    COALESCE(round(avg(LEAST(100, (latest.count::numeric * 100) / NULLIF(r.capacity, 0))), 1), 0)::float8 AS average_occupancy_percent,
                    COALESCE(round(avg(latest.confidence)::numeric, 3), 0)::float8 AS average_confidence
                FROM schedule_entries se
                JOIN rooms r ON r.id = se.room_id
                JOIN student_groups g ON g.id = se.group_id
                JOIN teachers t ON t.id = se.teacher_id
                JOIN disciplines d ON d.id = se.discipline_id
                LEFT JOIN LATERAL (
                    SELECT a.count, a.confidence, a.ts
                    FROM attendance a
                    WHERE a.room_id = se.room_id
                      AND a.ts::date = ?::date
                      AND a.ts::time BETWEEN se.starts_at AND se.ends_at
                    ORDER BY a.ts DESC
                    LIMIT 1
                ) latest ON true
                WHERE (
                    se.lesson_date = ?::date
                    OR (
                        se.lesson_date IS NULL
                        AND se.weekday = EXTRACT(ISODOW FROM ?::date)::int
                        AND se.valid_from <= ?::date
                        AND (se.valid_to IS NULL OR se.valid_to >= ?::date)
                    )
                )
                GROUP BY %s, %s
                ORDER BY average_occupancy_percent DESC, lessons DESC, dimension_name
                """.formatted(resolved.idExpression, resolved.nameExpression, resolved.idExpression, resolved.nameExpression),
                this::mapAnalytics,
                resolved.apiName,
                Date.valueOf(date),
                Date.valueOf(date),
                Date.valueOf(date),
                Date.valueOf(date),
                Date.valueOf(date)
        );
    }

    public ScheduleDirectory directory() {
        return new ScheduleDirectory(
                namedEntities("SELECT id, name, institute AS detail FROM student_groups ORDER BY name"),
                namedEntities("SELECT id, full_name AS name, department AS detail FROM teachers ORDER BY full_name"),
                namedEntities("SELECT id, name, '' AS detail FROM disciplines ORDER BY name")
        );
    }

    @Transactional
    public int importRows(List<ImportedScheduleRow> rows, String source) {
        int imported = 0;
        for (ImportedScheduleRow row : rows) {
            int groupId = upsertGroup(row.groupName(), row.institute());
            int teacherId = upsertTeacher(row.teacherName(), row.department());
            int disciplineId = upsertDiscipline(row.disciplineName());
            int roomId = upsertRoom(row.roomName(), row.building(), row.floor(), row.capacity());
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO schedule_entries (
                        group_id, teacher_id, discipline_id, room_id, lesson_date, weekday,
                        week_type, starts_at, ends_at, lesson_type, subgroup, source, valid_from, valid_to
                    )
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM schedule_entries
                        WHERE group_id = ?
                          AND teacher_id = ?
                          AND discipline_id = ?
                          AND room_id = ?
                          AND COALESCE(lesson_date, DATE '1900-01-01') = COALESCE(?::date, DATE '1900-01-01')
                          AND weekday = ?
                          AND starts_at = ?
                          AND ends_at = ?
                    )
                    """,
                    groupId,
                    teacherId,
                    disciplineId,
                    roomId,
                    row.lessonDate() == null ? null : Date.valueOf(row.lessonDate()),
                    row.weekday(),
                    row.weekType(),
                    Time.valueOf(row.startsAt()),
                    Time.valueOf(row.endsAt()),
                    row.lessonType(),
                    row.subgroup(),
                    source,
                    Date.valueOf(row.validFrom()),
                    row.validTo() == null ? null : Date.valueOf(row.validTo()),
                    groupId,
                    teacherId,
                    disciplineId,
                    roomId,
                    row.lessonDate() == null ? null : Date.valueOf(row.lessonDate()),
                    row.weekday(),
                    Time.valueOf(row.startsAt()),
                    Time.valueOf(row.endsAt())
            );
            imported += inserted;
        }
        return imported;
    }

    private int upsertGroup(String name, String institute) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO student_groups (name, institute)
                VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE
                SET institute = CASE WHEN EXCLUDED.institute <> '' THEN EXCLUDED.institute ELSE student_groups.institute END,
                    updated_at = now()
                RETURNING id
                """,
                Integer.class,
                name,
                institute
        );
    }

    private int upsertTeacher(String fullName, String department) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO teachers (full_name, department, source_url)
                VALUES (?, ?, 'excel')
                ON CONFLICT (full_name) DO UPDATE
                SET department = CASE WHEN EXCLUDED.department <> '' THEN EXCLUDED.department ELSE teachers.department END,
                    updated_at = now()
                RETURNING id
                """,
                Integer.class,
                fullName,
                department
        );
    }

    private int upsertDiscipline(String name) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO disciplines (name)
                VALUES (?)
                ON CONFLICT (name) DO UPDATE SET updated_at = now()
                RETURNING id
                """,
                Integer.class,
                name
        );
    }

    private int upsertRoom(String name, String building, String floor, int capacity) {
        Integer buildingId = jdbcTemplate.query(
                "SELECT id FROM campus_buildings WHERE lower(name) = lower(?) OR lower(code) = lower(?) LIMIT 1",
                rs -> rs.next() ? rs.getInt("id") : null,
                building,
                building
        );
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO rooms (name, building_id, building, floor, capacity)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE
                SET building_id = COALESCE(EXCLUDED.building_id, rooms.building_id),
                    building = CASE WHEN EXCLUDED.building <> '' THEN EXCLUDED.building ELSE rooms.building END,
                    floor = CASE WHEN EXCLUDED.floor <> '' THEN EXCLUDED.floor ELSE rooms.floor END,
                    capacity = GREATEST(rooms.capacity, EXCLUDED.capacity),
                    updated_at = now()
                RETURNING id
                """,
                Integer.class,
                name,
                buildingId,
                building,
                floor,
                capacity
        );
    }

    private List<ScheduleDirectory.NamedEntity> namedEntities(String sql) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ScheduleDirectory.NamedEntity(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("detail")
        ));
    }

    private static void appendFilter(StringBuilder sql, List<Object> args, String column, Integer value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private CampusBuilding mapBuilding(ResultSet rs, int rowNum) throws SQLException {
        return new CampusBuilding(
                rs.getInt("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getString("room_ranges"),
                rs.getDouble("map_x"),
                rs.getDouble("map_y"),
                rs.getString("color"),
                rs.getString("source_url"),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private ScheduleEntryView mapEntry(ResultSet rs, int rowNum) throws SQLException {
        int capacity = rs.getInt("capacity");
        Integer actualCount = nullableInt(rs, "actual_count");
        Integer occupancyPercent = actualCount == null || capacity <= 0
                ? null
                : clampPercent((int) Math.round((actualCount * 100.0) / capacity));
        return new ScheduleEntryView(
                rs.getLong("id"),
                rs.getDate("lesson_date").toLocalDate(),
                rs.getInt("weekday"),
                rs.getString("week_type"),
                rs.getTime("starts_at").toLocalTime(),
                rs.getTime("ends_at").toLocalTime(),
                rs.getString("lesson_type"),
                rs.getString("subgroup"),
                rs.getInt("room_id"),
                rs.getString("room_name"),
                rs.getString("building"),
                rs.getString("floor"),
                nullableInt(rs, "building_id"),
                rs.getString("building_code"),
                rs.getString("building_name"),
                rs.getInt("group_id"),
                rs.getString("group_name"),
                rs.getString("institute"),
                rs.getInt("teacher_id"),
                rs.getString("teacher_name"),
                rs.getString("department"),
                rs.getInt("discipline_id"),
                rs.getString("discipline_name"),
                capacity,
                actualCount,
                occupancyPercent,
                nullableDouble(rs, "confidence"),
                toInstant(rs.getTimestamp("measured_at"))
        );
    }

    private ScheduleAnalyticsRow mapAnalytics(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleAnalyticsRow(
                rs.getString("dimension"),
                rs.getInt("dimension_id"),
                rs.getString("dimension_name"),
                rs.getInt("lessons"),
                rs.getInt("planned_capacity"),
                rs.getInt("measured_lessons"),
                rs.getInt("average_attendance"),
                rs.getInt("peak_attendance"),
                rs.getDouble("average_occupancy_percent"),
                rs.getDouble("average_confidence")
        );
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private enum Dimension {
        TEACHER("teacher", "t.id", "t.full_name"),
        DISCIPLINE("discipline", "d.id", "d.name"),
        GROUP("group", "g.id", "g.name");

        private final String apiName;
        private final String idExpression;
        private final String nameExpression;

        Dimension(String apiName, String idExpression, String nameExpression) {
            this.apiName = apiName;
            this.idExpression = idExpression;
            this.nameExpression = nameExpression;
        }

        private static Dimension from(String value) {
            for (Dimension dimension : values()) {
                if (dimension.apiName.equalsIgnoreCase(value)) {
                    return dimension;
                }
            }
            return TEACHER;
        }
    }

    public record ImportedScheduleRow(
            String groupName,
            String institute,
            String teacherName,
            String department,
            String disciplineName,
            String roomName,
            String building,
            String floor,
            int capacity,
            LocalDate lessonDate,
            int weekday,
            String weekType,
            LocalTime startsAt,
            LocalTime endsAt,
            String lessonType,
            String subgroup,
            LocalDate validFrom,
            LocalDate validTo
    ) {
    }
}
