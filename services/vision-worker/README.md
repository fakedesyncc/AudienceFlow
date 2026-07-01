# Vision Worker

The worker reads a camera source, estimates the number of people, stabilizes the count with a sliding median, sends events to the Go ingest gateway, and can expose a local live preview for the JavaFX desktop client.

Supported sources:

- `sample` for the public sample video configured by `SAMPLE_VIDEO_URL`.
- `sample:https://.../video.mp4` for a one-off internet video URL.
- `device:0`, `0`, `1`, etc. for local device cameras.
- `phone:http://host:port/video` for phone IP-camera apps.
- `rtsp://...`, `http://...`, `https://...`, and MJPEG URLs supported by OpenCV.
- `/absolute/path/video.mp4` or `file:///absolute/path/video.mp4` for local files.
- `simulation` only as a fallback synthetic source.

Supported detectors:

- `simulation` generates realistic changing counts without a camera.
- `hog` uses OpenCV's built-in HOG person detector.
- `yolo` uses Ultralytics YOLO when `requirements-yolo.txt` is installed.

Public video example:

```bash
CAMERA_SOURCE=sample DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

Local camera example:

```bash
CAMERA_SOURCE=device:0 DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

Phone as camera example:

```bash
CAMERA_SOURCE=phone:http://192.168.1.10:8080/video DETECTOR=hog ROOM_ID=1 GATEWAY_URL=http://localhost:8081/v1/events python -m app.main
```

The worker reconnects to live streams after read failures. Video files and the public sample loop by default; set `VIDEO_LOOP=false` if you need reconnect-style behavior instead.

Docker Desktop on macOS usually cannot access the built-in webcam directly. For the device camera on macOS, run the worker on the host. For Linux, pass `/dev/video*` into the container.

## Photo/video analysis for tests

Use the same worker to count people in a saved image or video without starting a camera stream:

```bash
python -m app.main analyze ./samples/auditorium.jpg \
  --detector hog \
  --output ./samples/auditorium-annotated.jpg \
  --json-output ./samples/auditorium.json
```

For a video:

```bash
python -m app.main analyze ./samples/lecture.mp4 \
  --detector hog \
  --frame-step 5 \
  --max-frames 300 \
  --output ./samples/lecture-annotated.mp4 \
  --json-output ./samples/lecture.json
```

The JSON report includes:

- `count` — image count or median video count;
- `average_count`, `peak_count`, `last_count` for video;
- `confidence` / `average_confidence`;
- sampled frame counts and optional line-crossing counters.

For better recognition quality install YOLO dependencies and switch the detector:

```bash
pip install -r requirements-yolo.txt
python -m app.main analyze ./samples/auditorium.jpg --detector yolo --output ./samples/auditorium-yolo.jpg
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
