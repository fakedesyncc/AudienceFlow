.PHONY: bootstrap test desktop desktop-image up worker down smoke

bootstrap:
	./scripts/bootstrap-env.sh

test:
	go -C services/ingest-gateway test ./...
	mvn -q -f services/analytics-api/pom.xml test
	mvn -q -f services/desktop-client/pom.xml package
	python3 -m py_compile services/vision-worker/app/main.py
	npm --prefix services/web run build

desktop:
	mvn -f services/desktop-client/pom.xml javafx:run

desktop-image:
	mvn -q -f services/desktop-client/pom.xml javafx:jlink

up:
	docker compose up --build

worker:
	docker compose --profile worker up --build vision-worker

down:
	docker compose down

smoke:
	./scripts/smoke-event.sh
