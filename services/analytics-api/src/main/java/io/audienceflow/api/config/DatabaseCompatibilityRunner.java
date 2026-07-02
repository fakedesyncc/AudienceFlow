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
                    room_ranges TEXT NOT NULL DEFAULT '',
                    map_x       REAL NOT NULL CHECK (map_x >= 0 AND map_x <= 100),
                    map_y       REAL NOT NULL CHECK (map_y >= 0 AND map_y <= 100),
                    color       TEXT NOT NULL DEFAULT '#D2691E',
                    source_url  TEXT,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("ALTER TABLE campus_buildings ADD COLUMN IF NOT EXISTS room_ranges TEXT NOT NULL DEFAULT ''");
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
                INSERT INTO campus_buildings (code, name, address, room_ranges, map_x, map_y, color, source_url)
                VALUES
                    ('K1', 'Корпус 1', '398055, Россия, г. Липецк, ул. Московская, д. 30', '100-111, 204-239, 309-346, 408-440', 45, 34, '#D2691E', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('K2', 'Корпус 2', '398055, Россия, г. Липецк, ул. Московская, д. 30', '112-115, 240-263, 347-382, 441-478', 74, 35, '#2F6F7A', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('K3', 'Корпус 3', '398055, Россия, г. Липецк, ул. Московская, д. 30', '117-121, 264-272', 34, 48, '#2E7D5B', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('K4', 'Корпус 4', '398055, Россия, г. Липецк, ул. Московская, д. 30', '122-130, 273-282', 18, 28, '#8F420F', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('K5', 'Корпус 5', '398055, Россия, г. Липецк, ул. Московская, д. 30', '131-146, 283-299, 383-399, 479-499, 504-514', 11, 53, '#245863', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('K9', 'Корпус 9', '398055, Россия, г. Липецк, ул. Московская, д. 30', '9 104-110, 9 206-216, 9 303-315, 9 401, 9 406, 9 408-512', 89, 44, '#6E340C', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('AUD', 'Аудиторный корпус', '398055, Россия, г. Липецк, ул. Московская, д. 30', 'Л-1, Л-2, Л-3, Л-4', 59, 31, '#D2691E', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('ADM', 'Административный корпус', '398055, Россия, г. Липецк, ул. Московская, д. 30', 'Ректорат, профком, актовый зал, деканат', 59, 55, '#2F6F7A', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('DORM', 'Общежитие', '398055, Россия, г. Липецк, ул. Московская, д. 30', 'Медпункт, вход в общежитие', 22, 55, '#8A7C71', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('SPORT', 'Спортивный комплекс', '398055, Россия, г. Липецк, ул. Московская, д. 30', 'Спортзал - 1 этаж, бассейн - 2 этаж', 36, 65, '#2E7D5B', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('CAFE', 'Деревяшка / ВРЕМЯКОФЕ', '398055, Россия, г. Липецк, ул. Московская, д. 30', 'Деревяшка - 3 этаж, ВРЕМЯКОФЕ - 2 этаж', 38, 17, '#B4551A', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('B', 'Корпус Б', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5', 'Корпус Б, отдельный учебный корпус', 72, 80, '#2F6F7A', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html'),
                    ('C', 'Корпус C', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5', 'Корпус C, отдельный учебный корпус', 66, 86, '#8F420F', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html')
                ON CONFLICT (code) DO UPDATE
                SET name = EXCLUDED.name,
                    address = EXCLUDED.address,
                    room_ranges = EXCLUDED.room_ranges,
                    map_x = EXCLUDED.map_x,
                    map_y = EXCLUDED.map_y,
                    color = EXCLUDED.color,
                    source_url = EXCLUDED.source_url,
                    updated_at = now()
                """);
        jdbcTemplate.update("""
                UPDATE rooms
                SET building_id = cb.id
                FROM campus_buildings cb
                WHERE (
                    (rooms.building = 'Корпус 1' AND cb.code = 'K1')
                    OR (rooms.building = 'Корпус 2' AND cb.code = 'K2')
                    OR (rooms.building = 'Корпус 3' AND cb.code = 'K3')
                    OR (rooms.building = 'Корпус 4' AND cb.code = 'K4')
                    OR (rooms.building = 'Корпус 5' AND cb.code = 'K5')
                    OR (rooms.building = 'Корпус 9' AND cb.code = 'K9')
                    OR (rooms.building = 'Аудиторный корпус' AND cb.code = 'AUD')
                    OR (rooms.building = 'Административный корпус' AND cb.code = 'ADM')
                    OR (rooms.building = 'Общежитие' AND cb.code = 'DORM')
                    OR (rooms.building = 'Спортивный комплекс' AND cb.code = 'SPORT')
                    OR (rooms.building = 'Деревяшка / ВРЕМЯКОФЕ' AND cb.code = 'CAFE')
                    OR (rooms.building = 'Корпус Б' AND cb.code = 'B')
                    OR (rooms.building = 'Корпус C' AND cb.code = 'C')
                    OR (rooms.building IN ('Главный корпус', 'Главный кампус', 'Технопарк') AND cb.code = 'K1')
                )
                """);
    }
}
