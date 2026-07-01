# Работа с репозиторием

## Ветки

`main` — стабильная ветка. Из неё собирается GitHub Pages dashboard.

Новая работа должна идти в отдельных ветках:

- `feat/<short-name>` — новая функциональность;
- `fix/<short-name>` — исправление;
- `docs/<short-name>` — документация;
- `chore/<short-name>` — инфраструктура, сборка, housekeeping.

Прямой push в `main` допустим только для первичного bootstrap пустого репозитория или срочного исправления deploy. Обычный путь: branch → commit → push → PR → merge.

## Коммиты

Формат:

```text
type(scope): short imperative summary
```

Примеры:

```text
feat(api): add role-based analytics service
feat(worker): add camera vision event producer
fix(ci): enable GitHub Pages deployment
docs: rewrite Russian project guide
```

Один коммит должен отвечать за один слой или одну понятную правку. Не стоит смешивать frontend, миграции и документацию в одном коммите без причины.

## Проверки перед PR

```bash
make test
```

Команда запускает:

- `go test` для ingest gateway;
- `mvn test` для Analytics API;
- `py_compile` для vision worker;
- production build React dashboard.

Если менялся Dockerfile или Compose, дополнительно:

```bash
docker compose config --quiet
docker compose build
```

## Секреты

Не коммитить:

- `.env`;
- реальные RTSP URL;
- пароли;
- токены;
- скриншоты с чувствительными данными.

Для локального окружения использовать:

```bash
./scripts/bootstrap-env.sh
```
