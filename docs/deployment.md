# Deployment

## Local stack

Generate local secrets:

```bash
./scripts/bootstrap-env.sh
```

Start the core system:

```bash
docker compose up --build
```

Open:

- Dashboard: http://localhost:3000
- Analytics API: http://localhost:8080/api
- Ingest gateway: http://localhost:8081/healthz

Start the simulated vision worker:

```bash
docker compose --profile worker up --build vision-worker
```

Run the worker against a device camera outside Docker:

```bash
cd services/vision-worker
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
CAMERA_SOURCE=0 DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python app/main.py
```

Phones are usually connected through an IP-camera app that exposes RTSP or HTTP MJPEG. Put that URL into `CAMERA_SOURCE` or add it through the technician/admin panel.

## GitHub Pages

The frontend is deployed by `.github/workflows/deploy-pages.yml` on every push to `main`. Without a configured backend URL it runs in demo mode. Demo accounts are visible only in the static frontend and are separate from generated local backend credentials.

To connect GitHub Pages to a public API, set the repository variable `VITE_API_URL` to the API base URL, for example:

```text
https://api.example.com/api
```

GitHub Pages hosts only static frontend files. The backend services are containerized and can be deployed later to a VPS, Render, Railway, Fly.io, or a university server.
