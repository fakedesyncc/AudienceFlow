package io.audienceflow.api.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCompatibilityRunner implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseCompatibilityRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("ALTER TABLE cameras DROP CONSTRAINT IF EXISTS cameras_stream_type_check");
        jdbcTemplate.execute("""
                ALTER TABLE cameras
                ADD CONSTRAINT cameras_stream_type_check
                CHECK (stream_type IN ('rtsp', 'http', 'mjpeg', 'device', 'file', 'sample', 'simulation'))
                """);
        ensureScheduleSchema();
        seedCampusDirectory();
    }

    private void ensureScheduleSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS campus_buildings (
                    id          SERIAL PRIMARY KEY,
                    code        TEXT NOT NULL UNIQUE,
                    name        TEXT NOT NULL,
                    address     TEXT NOT NULL,
                    map_x       REAL NOT NULL CHECK (map_x >= 0 AND map_x <= 100),
                    map_y       REAL NOT NULL CHECK (map_y >= 0 AND map_y <= 100),
                    color       TEXT NOT NULL DEFAULT '#D2691E',
                    source_url  TEXT,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("ALTER TABLE rooms ADD COLUMN IF NOT EXISTS building_id INTEGER REFERENCES campus_buildings(id) ON DELETE SET NULL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS student_groups (
                    id          SERIAL PRIMARY KEY,
                    name        TEXT NOT NULL UNIQUE,
                    institute   TEXT NOT NULL DEFAULT '',
                    course      INTEGER CHECK (course IS NULL OR (course >= 1 AND course <= 6)),
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teachers (
                    id          SERIAL PRIMARY KEY,
                    full_name   TEXT NOT NULL UNIQUE,
                    department  TEXT NOT NULL DEFAULT '',
                    position    TEXT NOT NULL DEFAULT '',
                    source_url  TEXT,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS disciplines (
                    id          SERIAL PRIMARY KEY,
                    name        TEXT NOT NULL UNIQUE,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schedule_entries (
                    id             BIGSERIAL PRIMARY KEY,
                    group_id       INTEGER NOT NULL REFERENCES student_groups(id) ON DELETE CASCADE,
                    teacher_id     INTEGER NOT NULL REFERENCES teachers(id) ON DELETE CASCADE,
                    discipline_id  INTEGER NOT NULL REFERENCES disciplines(id) ON DELETE CASCADE,
                    room_id        INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
                    lesson_date    DATE,
                    weekday        SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
                    week_type      TEXT NOT NULL DEFAULT 'any' CHECK (week_type IN ('any', 'odd', 'even', 'green', 'white')),
                    starts_at      TIME NOT NULL,
                    ends_at        TIME NOT NULL,
                    lesson_type    TEXT NOT NULL DEFAULT 'занятие',
                    subgroup       TEXT NOT NULL DEFAULT '',
                    source         TEXT NOT NULL DEFAULT 'manual',
                    valid_from     DATE NOT NULL DEFAULT CURRENT_DATE,
                    valid_to       DATE,
                    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CHECK (ends_at > starts_at)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_schedule_date_room ON schedule_entries (lesson_date, room_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_schedule_weekday_room ON schedule_entries (weekday, room_id, starts_at)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_schedule_teacher ON schedule_entries (teacher_id, weekday, starts_at)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_schedule_group ON schedule_entries (group_id, weekday, starts_at)");
    }

    private void seedCampusDirectory() {
        jdbcTemplate.update("""
                INSERT INTO campus_buildings (code, name, address, map_x, map_y, color, source_url)
                VALUES
                    ('MAIN', 'Главный кампус', '398055, Россия, г. Липецк, ул. Московская, д. 30', 42, 54, '#D2691E', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('B', 'Корпус Б', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5', 78, 34, '#2F6F7A', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('TECH', 'Технопарк / лабораторный контур', 'г. Липецк, кампус ЛГТУ', 58, 24, '#2E7D5B', 'https://www.stu.lipetsk.ru/')
                ON CONFLICT (code) DO UPDATE
                SET name = EXCLUDED.name,
                    address = EXCLUDED.address,
                    map_x = EXCLUDED.map_x,
                    map_y = EXCLUDED.map_y,
                    color = EXCLUDED.color,
                    source_url = EXCLUDED.source_url,
                    updated_at = now()
                """);
        jdbcTemplate.update("""
                UPDATE rooms
                SET building_id = (SELECT id FROM campus_buildings WHERE code = 'MAIN')
                WHERE building IN ('Главный корпус', 'Главный кампус') AND building_id IS NULL
                """);
        jdbcTemplate.update("""
                UPDATE rooms
                SET building_id = (SELECT id FROM campus_buildings WHERE code = 'B')
                WHERE building = 'Корпус Б' AND building_id IS NULL
                """);
        jdbcTemplate.update("""
                UPDATE rooms
                SET building_id = (SELECT id FROM campus_buildings WHERE code = 'TECH')
                WHERE building = 'Технопарк' AND building_id IS NULL
                """);
    }
}
