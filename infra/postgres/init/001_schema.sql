CREATE EXTENSION IF NOT EXISTS pgcrypto;

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

CREATE TABLE IF NOT EXISTS teacher_access_keys (
    id           BIGSERIAL PRIMARY KEY,
    teacher_id   INTEGER NOT NULL REFERENCES teachers(id) ON DELETE CASCADE,
    key_hash     TEXT NOT NULL,
    label        TEXT NOT NULL DEFAULT '',
    active       BOOLEAN NOT NULL DEFAULT true,
    last_used_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_teacher_access_keys_teacher
    ON teacher_access_keys (teacher_id)
    WHERE active;

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
    ('С', 'Корпус С', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5', 'Корпус С, отдельный учебный корпус', 66, 86, '#8F420F', 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html')
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    address = EXCLUDED.address,
    room_ranges = EXCLUDED.room_ranges,
    map_x = EXCLUDED.map_x,
    map_y = EXCLUDED.map_y,
    color = EXCLUDED.color,
    source_url = EXCLUDED.source_url,
    updated_at = now();

INSERT INTO rooms (name, building, floor, capacity)
VALUES
    ('Аудитория 305', 'Корпус 9', '3', 64),
    ('Лекционный зал 101', 'Корпус 1', '1', 120),
    ('Лаборатория ИБ-2', 'Корпус 1', '2', 28),
    ('Аудитория 254', 'Корпус 2', '2', 72),
    ('Аудитория 117', 'Корпус 3', '1', 48),
    ('Аудитория 122', 'Корпус 4', '1', 44),
    ('Аудитория 146', 'Корпус 5', '1', 36),
    ('Л-1', 'Аудиторный корпус', '1', 180),
    ('Актовый зал', 'Административный корпус', '1', 220),
    ('Спортзал', 'Спортивный комплекс', '1', 80),
    ('Поточная аудитория Б-204', 'Корпус Б', '2', 90),
    ('Корпус С · учебная аудитория', 'Корпус С', '1', 40)
ON CONFLICT (name) DO UPDATE
SET building = EXCLUDED.building,
    floor = EXCLUDED.floor,
    capacity = EXCLUDED.capacity,
    updated_at = now();

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
    OR (rooms.building = 'Корпус С' AND cb.code = 'С')
    OR (rooms.building IN ('Главный корпус', 'Главный кампус') AND cb.code = 'K1')
    OR (rooms.building = 'Технопарк' AND cb.code = 'K1')
);

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
