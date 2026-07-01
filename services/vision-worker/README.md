# Vision Worker

The worker reads a camera source, estimates the number of people, stabilizes the count with a sliding median, sends events to the Go ingest gateway, and can expose a local live preview for the JavaFX desktop client.

Supported sources:

- `simulation` for Docker/demo mode.
- `0`, `1`, etc. for local device cameras.
- RTSP/HTTP URLs, including phone IP-camera apps.

Supported detectors:

- `simulation` generates realistic changing counts without a camera.
- `hog` uses OpenCV's built-in HOG person detector.
- `yolo` uses Ultralytics YOLO when `requirements-yolo.txt` is installed.

Example:

```bash
CAMERA_SOURCE=0 DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

## Live preview for desktop

Preview is enabled by default and serves:

- `GET /v1/frame.jpg` — latest JPEG frame with detection overlay;
- `GET /v1/stream.mjpg` — MJPEG stream for simple viewers;
- `GET /v1/state` — JSON state: count, confidence, FPS, detections, line counters;
- `POST /v1/line` — configure or disable the virtual flow line;
- `POST /v1/counters/reset` — reset entered/exited counters;
- `GET /healthz` — health check.

Secure preview with a random token when the worker is reachable outside localhost:

```bash
PREVIEW_TOKEN=$(openssl rand -hex 24)
PREVIEW_ADDR=127.0.0.1:8090 \
CAMERA_SOURCE=0 \
DETECTOR=hog \
ROOM_ID=1 \
GATEWAY_URL=http://localhost:8081/v1/events \
python -m app.main
```

In the desktop client open `Камера`, set `Preview URL` to `http://localhost:8090`, and enter the same preview token if one was configured.

Line-counting example:

```bash
curl -H "X-Preview-Token: $PREVIEW_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"enabled":true,"x1":470,"y1":80,"x2":470,"y2":460}' \
  http://localhost:8090/v1/line
```

The line is drawn on the preview frame. Crossings from one side to the other update `entered`, `exited`, `balance`, and `active_tracks` in `/v1/state`.
