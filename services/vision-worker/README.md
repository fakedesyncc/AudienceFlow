# Vision Worker

The worker reads a camera source, estimates the number of people, stabilizes the count with a sliding median, and sends events to the Go ingest gateway.

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
