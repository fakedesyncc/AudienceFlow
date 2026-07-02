CREATE EXTENSION IF NOT EXISTS pgcrypto;

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
);

CREATE TABLE IF NOT EXISTS rooms (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    building_id INTEGER REFERENCES campus_buildings(id) ON DELETE SET NULL,
    building    TEXT NOT NULL DEFAULT '',
    floor       TEXT NOT NULL DEFAULT '',
    capacity    INTEGER NOT NULL CHECK (capacity > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cameras (
    id           SERIAL PRIMARY KEY,
    room_id      INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    source_url   TEXT NOT NULL,
    stream_type  TEXT NOT NULL DEFAULT 'rtsp' CHECK (stream_type IN ('rtsp', 'http', 'mjpeg', 'device', 'file', 'sample', 'simulation')),
    status       TEXT NOT NULL DEFAULT 'offline' CHECK (status IN ('online', 'offline', 'maintenance')),
    enabled      BOOLEAN NOT NULL DEFAULT true,
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (room_id, name)
);

CREATE TABLE IF NOT EXISTS app_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('TEACHER', 'TECHNICIAN', 'ADMIN')),
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_users_email_lower ON app_users (lower(email));

CREATE TABLE IF NOT EXISTS attendance (
    id          BIGSERIAL PRIMARY KEY,
    room_id     INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    count       INTEGER NOT NULL CHECK (count >= 0),
    confidence  REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    worker_id   TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_attendance_room_ts ON attendance (room_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_attendance_ts ON attendance (ts DESC);

CREATE TABLE IF NOT EXISTS student_groups (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    institute   TEXT NOT NULL DEFAULT '',
    course      INTEGER CHECK (course IS NULL OR (course >= 1 AND course <= 6)),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS teachers (
    id          SERIAL PRIMARY KEY,
    full_name   TEXT NOT NULL UNIQUE,
    department  TEXT NOT NULL DEFAULT '',
    position    TEXT NOT NULL DEFAULT '',
    source_url  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS disciplines (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
);

CREATE INDEX IF NOT EXISTS idx_schedule_date_room ON schedule_entries (lesson_date, room_id);
CREATE INDEX IF NOT EXISTS idx_schedule_weekday_room ON schedule_entries (weekday, room_id, starts_at);
CREATE INDEX IF NOT EXISTS idx_schedule_teacher ON schedule_entries (teacher_id, weekday, starts_at);
CREATE INDEX IF NOT EXISTS idx_schedule_group ON schedule_entries (group_id, weekday, starts_at);

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID REFERENCES app_users(id) ON DELETE SET NULL,
    action      TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT,
    details     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE VIEW attendance_5min AS
SELECT
    room_id,
    to_timestamp(floor(extract(epoch FROM ts) / 300) * 300) AS bucket,
    round(avg(count))::INTEGER AS avg_count,
    max(count) AS peak_count,
    round(avg(confidence)::numeric, 3)::REAL AS avg_confidence
FROM attendance
GROUP BY room_id, bucket;

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
    updated_at = now();

INSERT INTO rooms (name, building, floor, capacity)
VALUES
    ('Аудитория 305', 'Главный корпус', '3', 64),
    ('Лекционный зал 101', 'Главный корпус', '1', 120),
    ('Лаборатория ИБ-2', 'Технопарк', '2', 28)
ON CONFLICT (name) DO NOTHING;

UPDATE rooms
SET building_id = (SELECT id FROM campus_buildings WHERE code = 'MAIN')
WHERE building IN ('Главный корпус', 'Главный кампус') AND building_id IS NULL;

UPDATE rooms
SET building_id = (SELECT id FROM campus_buildings WHERE code = 'B')
WHERE building = 'Корпус Б' AND building_id IS NULL;

UPDATE rooms
SET building_id = (SELECT id FROM campus_buildings WHERE code = 'TECH')
WHERE building = 'Технопарк' AND building_id IS NULL;

INSERT INTO cameras (room_id, name, source_url, stream_type, status)
SELECT id, 'Public sample video', 'sample', 'sample', 'online'
FROM rooms
WHERE name = 'Аудитория 305'
ON CONFLICT (room_id, name) DO NOTHING;

INSERT INTO student_groups (name, institute, course)
VALUES
    ('ПИ-24-1', 'Институт компьютерных наук', 2),
    ('ПИ-23-1', 'Институт компьютерных наук', 3),
    ('АС-24-1', 'Институт компьютерных наук', 2),
    ('БИ-23-1', 'Институт социальных наук, экономики и права', 3)
ON CONFLICT (name) DO UPDATE
SET institute = EXCLUDED.institute,
    course = EXCLUDED.course,
    updated_at = now();

INSERT INTO teachers (full_name, department, position, source_url)
VALUES
    ('Ткаченко Светлана Владимировна', 'Прикладная математика и системный анализ', 'Старший преподаватель', 'https://www.stu.lipetsk.ru/struct/management/rector/sub/hr/pps/table.html?op=all'),
    ('Богомолова Елена Владимировна', 'Экономика и управление', 'Доцент', 'https://www.stu.lipetsk.ru/struct/management/rector/sub/hr/pps/table.html?op=all'),
    ('Кирсанов Филипп Александрович', 'Транспортные средства и техносферная безопасность', 'Доцент', 'https://www.stu.lipetsk.ru/struct/management/rector/sub/hr/pps/table.html?op=all')
ON CONFLICT (full_name) DO UPDATE
SET department = EXCLUDED.department,
    position = EXCLUDED.position,
    source_url = EXCLUDED.source_url,
    updated_at = now();

INSERT INTO disciplines (name)
VALUES
    ('Дискретная математика'),
    ('Экономика предприятия'),
    ('Метрология, стандартизация и сертификация'),
    ('Проектирование информационных систем')
ON CONFLICT (name) DO NOTHING;

INSERT INTO schedule_entries (
    group_id, teacher_id, discipline_id, room_id, weekday, starts_at, ends_at, lesson_type, source, valid_from
)
SELECT g.id, t.id, d.id, r.id, item.weekday, item.starts_at::time, item.ends_at::time, item.lesson_type, 'seed:lgtu', DATE '2026-02-01'
FROM (
    VALUES
        ('ПИ-24-1', 'Ткаченко Светлана Владимировна', 'Дискретная математика', 'Аудитория 305', 1, '09:40', '11:10', 'практика'),
        ('ПИ-23-1', 'Ткаченко Светлана Владимировна', 'Проектирование информационных систем', 'Лекционный зал 101', 2, '11:20', '12:50', 'лекция'),
        ('БИ-23-1', 'Богомолова Елена Владимировна', 'Экономика предприятия', 'Лекционный зал 101', 3, '13:20', '14:50', 'лекция'),
        ('АС-24-1', 'Кирсанов Филипп Александрович', 'Метрология, стандартизация и сертификация', 'Лаборатория ИБ-2', 4, '15:00', '16:30', 'лабораторная')
) AS item(group_name, teacher_name, discipline_name, room_name, weekday, starts_at, ends_at, lesson_type)
JOIN student_groups g ON g.name = item.group_name
JOIN teachers t ON t.full_name = item.teacher_name
JOIN disciplines d ON d.name = item.discipline_name
JOIN rooms r ON r.name = item.room_name
WHERE NOT EXISTS (
    SELECT 1
    FROM schedule_entries se
    WHERE se.group_id = g.id
      AND se.teacher_id = t.id
      AND se.discipline_id = d.id
      AND se.room_id = r.id
      AND se.weekday = item.weekday
      AND se.starts_at = item.starts_at::time
);
