# Архитектура AudienceFlow

AudienceFlow считает посещаемость не «внутри одного большого приложения», а через цепочку небольших сервисов. Так проще объяснять систему на защите и проще менять отдельные части: камеру, способ детекции, API или frontend.

## Сервисы

| Сервис | Язык | Ответственность |
| --- | --- | --- |
| `vision-worker` | Python | Получает кадры, считает людей, ведёт line-crossing счётчики, стабилизирует значение, отправляет событие |
| `ingest-gateway` | Go | Принимает события от worker-ов, проверяет ingest key, держит очередь, пишет батчами |
| `analytics-api` | Java / Spring Boot | Авторизация, роли, аудитории, камеры, пользователи, агрегаты, live WebSocket |
| `desktop-client` | Java / JavaFX | Основной рабочий клиент для Windows, macOS и Linux: live-видео, zoom, снимки, линия входа/выхода, мониторинг, отчёты |
| `web` | TypeScript / React | Статическая демонстрационная панель для GitHub Pages |
| `postgres` | PostgreSQL | Хранение комнат, камер, пользователей, измерений и агрегатов |

## Поток данных

```mermaid
sequenceDiagram
    participant C as Камера
    participant W as Python worker
    participant G as Go gateway
    participant P as PostgreSQL
    participant A as Java API
    participant D as Desktop/Web client

    C->>W: кадры
    W->>W: detector + line crossing + sliding median
    W->>G: AttendanceEvent
    G->>G: validation + queue
    G->>P: batch insert
    D->>W: preview frame/state + line controls
    W-->>D: JPEG frame + detections + line counters
    D->>A: REST запросы
    A->>P: SQL агрегаты
    A-->>D: JSON
    A-->>D: WebSocket live snapshot
```

Событие worker-а:

```json
{
  "room_id": 1,
  "ts": "2026-07-01T10:32:15Z",
  "count": 47,
  "confidence": 0.83,
  "worker_id": "cam-aud-305"
}
```

## Почему так

Python остаётся рядом с OpenCV и YOLO. Go принимает много коротких I/O-запросов и не держит тяжёлой бизнес-логики. Java API отвечает за правила доступа и агрегаты, потому что это стабильный слой приложения. Desktop-клиент тоже написан на Java: он использует тот же контракт API, WebSocket live-канал и не зависит от браузера. React отделён от backend и остаётся статической демо-панелью для GitHub Pages.

Видео не прокидывается через Analytics API: desktop подключается к preview endpoint конкретного `vision-worker` и получает уже размеченный JPEG/MJPEG поток. Через этот же защищённый preview API desktop настраивает виртуальную линию входа/выхода и сбрасывает счётчики. Так API не становится видеопрокси, а события посещаемости остаются в обычном контуре `worker → Go → PostgreSQL → Java API`.

Такой разрез делает проект удобным для практики: каждый сервис можно показать отдельно, но вместе они дают цельный поток «камера → событие → база → аналитика → панель».

## База данных

Основные таблицы:

- `rooms` — аудитории и вместимость;
- `cameras` — источники камер и их статус;
- `app_users` — пользователи и роли;
- `attendance` — сырые измерения;
- `audit_log` — место для последующего аудита действий;
- `attendance_5min` — view с 5-минутными агрегатами.

PostgreSQL выбран из-за нормальной работы со временем, индексов и оконных/агрегатных запросов. Если на стенде PostgreSQL не получится поднять, ближайший практичный fallback — перейти на SQLite для одиночного demo-режима или на MySQL/MariaDB для контейнерного режима. В таком случае придётся адаптировать SQL-тип `TIMESTAMPTZ`, view и driver-ы в Go/Java.

## Live-обновления

`analytics-api` отдаёт текущую картину через REST endpoint и WebSocket. Desktop-клиент сначала загружает снимок через REST, затем держит WebSocket и автоматически возвращается к polling, если live-канал временно недоступен:

- REST нужен для первичной загрузки и fallback polling;
- WebSocket нужен для живой панели без постоянных ручных обновлений.

WebSocket принимает JWT в query-параметре. Это не идеальная схема для публичного production, но для учебного MVP она проста и понятна. Для промышленного режима лучше перейти на короткоживущие socket tokens или cookie-based auth.

## Границы MVP

Проект уже работает как демонстрационный distributed MVP. Что стоит делать дальше:

- собрать подписанные desktop-релизы через `jpackage`/код-подпись, если проект выйдет за рамки учебного стенда;
- заменить лёгкий centroid tracker на ByteTrack/DeepSORT для сложных сцен с пересечениями людей;
- вынести миграции в Flyway или Liquibase;
- добавить audit events для действий администратора;
- прикрутить refresh tokens;
- вынести backend на публичный хостинг и подключить Pages-панель к реальному API.
