CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS rooms (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
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
    stream_type  TEXT NOT NULL DEFAULT 'rtsp' CHECK (stream_type IN ('rtsp', 'http', 'device', 'simulation')),
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

INSERT INTO rooms (name, building, floor, capacity)
VALUES
    ('Аудитория 305', 'Главный корпус', '3', 64),
    ('Лекционный зал 101', 'Главный корпус', '1', 120),
    ('Лаборатория ИБ-2', 'Технопарк', '2', 28)
ON CONFLICT (name) DO NOTHING;

INSERT INTO cameras (room_id, name, source_url, stream_type, status)
SELECT id, 'Симулятор камеры', 'simulation', 'simulation', 'online'
FROM rooms
WHERE name = 'Аудитория 305'
ON CONFLICT (room_id, name) DO NOTHING;
