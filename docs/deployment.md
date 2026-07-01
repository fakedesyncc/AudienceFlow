# Запуск и деплой

Этот документ нужен не для красивой схемы, а для повторяемого запуска. Если другой человек клонирует репозиторий, он должен поднять проект без угадывания переменных.

## Локальный запуск

1. Сгенерировать `.env`:

   ```bash
   ./scripts/bootstrap-env.sh
   ```

2. Запустить сервисы:

   ```bash
   docker compose up --build
   ```

3. Открыть панель:

   ```text
   http://localhost:3000
   ```

4. Запустить основной desktop-клиент:

   ```bash
   make desktop
   ```

5. Проверить ingest вручную:

   ```bash
   make smoke
   ```

## Что создаёт bootstrap

`scripts/bootstrap-env.sh` создаёт `.env` и печатает случайные стартовые данные:

- `ADMIN_EMAIL` / `ADMIN_PASSWORD`;
- `TECHNICIAN_EMAIL` / `TECHNICIAN_PASSWORD`;
- `TEACHER_EMAIL` / `TEACHER_PASSWORD`.

Также генерируются `POSTGRES_PASSWORD`, `INGEST_API_KEY` и `JWT_SECRET`. Файл `.env` добавлен в `.gitignore`.

Эти email и пароли не лежат в репозитории. Их видит только человек, который запустил bootstrap-скрипт.

## Worker

Симулятор:

```bash
docker compose --profile worker up --build vision-worker
```

Preview-канал worker-а будет доступен для desktop-клиента:

```text
http://localhost:8090
```

Локальная камера:

```bash
cd services/vision-worker
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
PREVIEW_ADDR=127.0.0.1:8090 CAMERA_SOURCE=0 DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

RTSP/HTTP камера:

```bash
PREVIEW_ADDR=127.0.0.1:8090 CAMERA_SOURCE=rtsp://your-camera-host:8554/live DETECTOR=hog python -m app.main
```

Для preview доступны:

- `/v1/frame.jpg` — последний JPEG-кадр с рамками;
- `/v1/stream.mjpg` — MJPEG поток;
- `/v1/state` — счётчик, confidence, FPS, список детекций и состояние линии;
- `POST /v1/line` — включение/выключение виртуальной линии потока;
- `POST /v1/counters/reset` — сброс счётчиков входа/выхода.

Если preview открывается в сеть, задай `PREVIEW_TOKEN` и введи его во вкладке `Камера` desktop-клиента.

## GitHub Pages

GitHub Pages публикует только демонстрационный frontend из workflow `.github/workflows/deploy-pages.yml`.

Сайт проекта:

```text
https://fakedesyncc.github.io/AudienceFlow/
```

Для первого включения Pages в пустом репозитории может потребоваться один раз включить источник `GitHub Actions` в Settings → Pages или выполнить:

```bash
gh api -X POST repos/fakedesyncc/AudienceFlow/pages -f build_type=workflow
```

После этого push в `main` запускает:

- CI;
- frontend build;
- upload Pages artifact;
- deploy.

## Подключение реального API к Pages

По умолчанию Pages-панель открывает презентационный мониторинг без логинов и паролей. Это нужно потому, что GitHub Pages не запускает backend.

Чтобы панель ходила в публичный API, можно прямо на экране входа выбрать `API` и ввести:

- API URL;
- email пользователя;
- пароль пользователя.

Repository variable можно добавить только для удобства, чтобы API URL был предзаполнен:

```text
VITE_API_URL=https://your-api.example.com/api
```

После изменения переменной перезапусти `Deploy GitHub Pages`.

JWT хранится в `sessionStorage` браузера. Пароль не сохраняется в приложении.

## Desktop-клиент

Основной клиент находится в `services/desktop-client`. Это JavaFX-приложение: оно подключается к тому же Spring API, показывает live-состояние через WebSocket и не хранит пароль после отправки формы входа.

Для живого видео открой вкладку `Камера`, укажи `Preview URL` worker-а, например `http://localhost:8090`, и нажми `Подключить`. Снимки сохраняются в `~/Pictures/AudienceFlow`.

Для демонстрации счёта потока на симуляторе включи линию `470,80 → 470,460` в блоке `Линия потока`. Desktop покажет счётчики `Вошло`, `Вышло`, `Баланс` и активные треки, а та же линия будет отрисована на кадре.

Запуск из исходников:

```bash
mvn -f services/desktop-client/pom.xml javafx:run
```

Сборка runtime-образа для текущей ОС:

```bash
make desktop-image
```

Результат появится в:

```text
services/desktop-client/target/audienceflow-desktop
```

JavaFX runtime-образ собирается под ту ОС, на которой запущена сборка. Для Windows, macOS и Linux в репозитории есть ручной workflow `Build Desktop Clients`: он запускает matrix-сборку на трёх GitHub Actions runner-ах и прикладывает артефакты к workflow run.

Пароли, JWT secret, ingest key и адреса камер не вшиваются в desktop-сборку. Пользователь вводит `API URL`, email и пароль на экране входа.

## Где держать backend

Подходящие варианты:

- VPS с Docker Compose;
- сервер кафедры или лаборатории;
- Render/Railway/Fly.io;
- любой Kubernetes/VM стенд, если нужно показать «взрослый» деплой.

Для MVP достаточно Docker Compose. Для публичной эксплуатации нужно отдельно настроить HTTPS, CORS, резервное копирование PostgreSQL и нормальное хранение секретов.
