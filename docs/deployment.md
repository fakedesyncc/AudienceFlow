# Запуск и деплой AULA

> AULA — продукт; AudienceFlow — платформа и техническое имя (пакеты, репозиторий, каталоги). Технические идентификаторы ниже (URL репозитория, GitHub Pages, каталоги `~/Pictures/AudienceFlow` и `~/Documents/AudienceFlow`, имя артефакта) остаются без изменений.

Этот документ нужен не для красивой схемы, а для повторяемого запуска. Если другой человек клонирует репозиторий, он должен поднять проект без угадывания переменных.

## Локальный запуск

1. Сгенерировать `.env`. Для реального показа используй интерактивный режим:

   ```bash
   INTERACTIVE=1 ./scripts/bootstrap-env.sh
   ```

   Автоматический режим оставлен для локального тестового стенда:

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

`scripts/bootstrap-env.sh` создаёт `.env` с правами `600`. В режиме `INTERACTIVE=1` скрипт спрашивает реальные email:

- `ADMIN_EMAIL` / `ADMIN_PASSWORD`;
- `TECHNICIAN_EMAIL` / `TECHNICIAN_PASSWORD`;
- `TEACHER_EMAIL` / `TEACHER_PASSWORD`.

Также генерируются `POSTGRES_PASSWORD`, `INGEST_API_KEY` и `JWT_SECRET`. Файл `.env` добавлен в `.gitignore`.

Эти email и пароли не лежат в репозитории. Их видит только человек, который запустил bootstrap-скрипт. Слабые пароли и дефолтный логин `admin` отклоняются на уровне bootstrap и backend API.

## Worker

Публичное sample video для демонстрации:

```bash
docker compose --profile worker up --build vision-worker
```

По умолчанию profile запускает `CAMERA_SOURCE=sample`, `DETECTOR=hog` и зацикливает публичное видео. Своё видео можно указать так:

```bash
CAMERA_SOURCE=sample SAMPLE_VIDEO_URL=https://example.com/people.mp4 docker compose --profile worker up --build vision-worker
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
PREVIEW_ADDR=127.0.0.1:8090 CAMERA_SOURCE=device:0 DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

RTSP/HTTP/MJPEG камера или телефон:

```bash
PREVIEW_ADDR=127.0.0.1:8090 CAMERA_SOURCE=rtsp://your-camera-host:8554/live DETECTOR=hog python -m app.main
PREVIEW_ADDR=127.0.0.1:8090 CAMERA_SOURCE=phone:http://192.168.1.10:8080/video DETECTOR=hog python -m app.main
```

На macOS Docker Desktop обычно не даёт контейнеру доступ к встроенной камере, поэтому `device:0` запускай worker-ом на host. На Linux можно пробросить `/dev/video*` в compose.

Проверка по готовому фото или видео без запуска камеры:

```bash
cd services/vision-worker
python -m app.main analyze ./samples/auditorium.jpg --detector hog --output ./samples/auditorium-annotated.jpg
python -m app.main analyze ./samples/lecture.mp4 --detector hog --frame-step 5 --max-frames 300 --output ./samples/lecture-annotated.mp4 --json-output ./samples/lecture.json
```

Для реальных аудиторий лучше поставить YOLO и запускать `--detector yolo`; HOG оставлен как лёгкий вариант без ML-модели.

Для preview доступны:

- `/v1/frame.jpg` — последний JPEG-кадр с рамками;
- `/v1/stream.mjpg` — MJPEG поток;
- `/v1/state` — счётчик, confidence, FPS, список детекций и состояние линии;
- `POST /v1/line` — включение/выключение виртуальной линии потока;
- `POST /v1/counters/reset` — сброс счётчиков входа/выхода.

Если preview открывается в сеть, задай `PREVIEW_TOKEN` и введи его во вкладке `Камера` desktop-клиента.

## GitHub Pages

GitHub Pages публикует два независимых frontend-контура из workflow `.github/workflows/deploy-pages.yml`.

```text
https://fakedesyncc.github.io/AudienceFlow/       # demo-витрина
https://fakedesyncc.github.io/AudienceFlow/work/  # рабочая web-консоль
```

Demo-витрина собирается с `VITE_PUBLIC_CONTOUR=demo`: в ней нет рабочих секретов и API-сессия не восстанавливается.

Рабочая консоль собирается с `VITE_PUBLIC_CONTOUR=work`: в bundle не попадают demo-расписание, demo-ключи и mock-аудитории. Для предзаполненного API URL можно задать repository variable:

```text
VITE_WORK_API_URL=https://your-domain.example/api
```

Если `VITE_WORK_API_URL` не задан, рабочая консоль всё равно откроется, но пользователь должен ввести `API URL` на экране входа.

Для первого включения Pages в пустом репозитории может потребоваться один раз включить источник `GitHub Actions` в Settings → Pages или выполнить:

```bash
gh api -X POST repos/fakedesyncc/AudienceFlow/pages -f build_type=workflow
```

После этого push в `main` запускает:

- CI;
- demo frontend build;
- work frontend build;
- upload Pages artifact;
- deploy.

## Web build modes

Локальная demo-сборка:

```bash
cd services/web
VITE_PUBLIC_CONTOUR=demo VITE_BASE_PATH=/AudienceFlow/ npm run build:demo
```

Локальная рабочая сборка:

```bash
cd services/web
VITE_PUBLIC_CONTOUR=work VITE_BASE_PATH=/ VITE_API_URL=http://localhost:8080/api npm run build:work
```

Docker image `audienceflow-web` по умолчанию собирает рабочий контур:

```text
docker build \
  --build-arg VITE_PUBLIC_CONTOUR=work \
  --build-arg VITE_API_URL=https://your-domain.example/api \
  -t audienceflow-web services/web
```

JWT хранится в `sessionStorage` браузера с отдельным ключом для каждого контура. Пароль не сохраняется в приложении.

## Расписание из Excel

Импорт расписания находится в разделе `Карта и расписание` и доступен администратору/технику. Backend принимает два формата:

- нормализованная таблица с колонками `группа`, `преподаватель`, `дисциплина`, `аудитория`, `день недели`, `время`;
- широкий файл ЛГТУ вроде `ikn-bak.xlsx`, где на листах стоят пары колонок `Группа ...` / `Аудито рия`, а дисциплина и преподаватель записаны в одной ячейке.

Для широкого формата сервис сам определяет день недели, время пары, группу, аудиторию, тип занятия, корпус и семестр. Если занятие стоит в будущем, аналитика не придумывает фактическую посещаемость: факт появляется только по данным камер.

## Desktop-клиент

Основной клиент находится в `services/desktop-client`. Это JavaFX-приложение: оно подключается к тому же Spring API, показывает live-состояние через WebSocket, формирует отчёты по аудиториям и не хранит пароль после отправки формы входа.

Для живого видео открой вкладку `Камера`, укажи `Preview URL` worker-а, например `http://localhost:8090`, и нажми `Подключить`. Снимки сохраняются в `~/Pictures/AudienceFlow`.

Для демонстрации счёта потока включи линию `470,80 → 470,460` в блоке `Линия потока`. Desktop покажет счётчики `Вошло`, `Вышло`, `Баланс` и активные треки, а та же линия будет отрисована на кадре.

Для выгрузки данных открой вкладку `Отчёты`, выбери аудиторию и период. CSV сохраняется в `~/Documents/AudienceFlow` и содержит summary-блок плюс строки timeline-агрегатов.

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
