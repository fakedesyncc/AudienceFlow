from __future__ import annotations

import json
import math
import os
import random
import socket
import statistics
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Protocol
from urllib.parse import parse_qs, urlparse

import requests


class Detector(Protocol):
    def detect(self, frame: Any | None) -> "DetectionResult":
        """Return detected people, confidence, and overlay metadata."""


@dataclass(frozen=True)
class Detection:
    x: int
    y: int
    width: int
    height: int
    confidence: float
    track_id: int | None = None

    def scaled(self, ratio: float) -> "Detection":
        return Detection(
            x=int(self.x * ratio),
            y=int(self.y * ratio),
            width=int(self.width * ratio),
            height=int(self.height * ratio),
            confidence=self.confidence,
            track_id=self.track_id,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "x": self.x,
            "y": self.y,
            "width": self.width,
            "height": self.height,
            "confidence": self.confidence,
            "track_id": self.track_id,
        }


@dataclass(frozen=True)
class DetectionResult:
    count: int
    confidence: float
    detections: list[Detection]


@dataclass(frozen=True)
class Config:
    gateway_url: str
    ingest_api_key: str
    room_id: int
    worker_id: str
    camera_source: str
    detector: str
    send_interval_seconds: float
    stabilization_window: int
    confidence_threshold: float
    request_timeout_seconds: float
    yolo_model: str
    preview_enabled: bool
    preview_addr: str
    preview_token: str
    preview_width: int
    preview_jpeg_quality: int
    line_config: tuple[int, int, int, int] | None

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            gateway_url=env("GATEWAY_URL", "http://localhost:8081/v1/events"),
            ingest_api_key=env("INGEST_API_KEY", ""),
            room_id=int(env("ROOM_ID", "1")),
            worker_id=env("WORKER_ID", f"{socket.gethostname()}-vision"),
            camera_source=env("CAMERA_SOURCE", "simulation"),
            detector=env("DETECTOR", "simulation").lower(),
            send_interval_seconds=float(env("SEND_INTERVAL_SECONDS", "5")),
            stabilization_window=int(env("STABILIZATION_WINDOW", "5")),
            confidence_threshold=float(env("CONFIDENCE_THRESHOLD", "0.45")),
            request_timeout_seconds=float(env("REQUEST_TIMEOUT_SECONDS", "5")),
            yolo_model=env("YOLO_MODEL", "yolov8n.pt"),
            preview_enabled=env_bool("PREVIEW_ENABLED", "true"),
            preview_addr=env("PREVIEW_ADDR", "127.0.0.1:8090"),
            preview_token=env("PREVIEW_TOKEN", ""),
            preview_width=int(env("PREVIEW_WIDTH", "960")),
            preview_jpeg_quality=int(env("PREVIEW_JPEG_QUALITY", "82")),
            line_config=parse_line_config(env("LINE_CONFIG", "")),
        )

    def validate(self) -> None:
        if not self.gateway_url:
            raise ValueError("GATEWAY_URL is required")
        if len(self.ingest_api_key) < 24:
            raise ValueError("INGEST_API_KEY must be at least 24 characters")
        if self.room_id < 1:
            raise ValueError("ROOM_ID must be positive")
        if self.send_interval_seconds <= 0:
            raise ValueError("SEND_INTERVAL_SECONDS must be positive")
        if self.stabilization_window < 1:
            raise ValueError("STABILIZATION_WINDOW must be positive")
        if not 0 <= self.confidence_threshold <= 1:
            raise ValueError("CONFIDENCE_THRESHOLD must be between 0 and 1")
        if self.detector not in {"simulation", "hog", "yolo"}:
            raise ValueError("DETECTOR must be one of: simulation, hog, yolo")
        if self.preview_width < 320:
            raise ValueError("PREVIEW_WIDTH must be at least 320")
        if not 20 <= self.preview_jpeg_quality <= 95:
            raise ValueError("PREVIEW_JPEG_QUALITY must be between 20 and 95")


class CountStabilizer:
    def __init__(self, window_size: int, confidence_threshold: float) -> None:
        self.samples: deque[int] = deque(maxlen=window_size)
        self.confidences: deque[float] = deque(maxlen=window_size)
        self.confidence_threshold = confidence_threshold

    def push(self, raw_count: int, confidence: float) -> tuple[int, float]:
        if confidence >= self.confidence_threshold or not self.samples:
            self.samples.append(max(0, raw_count))
            self.confidences.append(max(0.0, min(1.0, confidence)))

        stable_count = int(round(statistics.median(self.samples))) if self.samples else 0
        stable_confidence = statistics.fmean(self.confidences) if self.confidences else 0.0
        return stable_count, round(stable_confidence, 3)


class SimulationDetector:
    def __init__(self) -> None:
        self.started = time.monotonic()

    def detect(self, frame: Any | None) -> DetectionResult:
        elapsed = time.monotonic() - self.started
        baseline = 22 + 11 * math.sin(elapsed / 32)
        rush = 8 * math.sin(elapsed / 9)
        noise = random.randint(-3, 3)
        count = max(0, int(round(baseline + rush + noise)))
        confidence = round(random.uniform(0.78, 0.96), 3)
        return DetectionResult(count, confidence, simulation_detections(count, elapsed))


class HogDetector:
    def __init__(self) -> None:
        import cv2

        self.cv2 = cv2
        self.hog = cv2.HOGDescriptor()
        self.hog.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())

    def detect(self, frame: Any | None) -> DetectionResult:
        if frame is None:
            return DetectionResult(0, 0.0, [])

        detection_frame, ratio = resize_for_detection(self.cv2, frame)
        boxes, weights = self.hog.detectMultiScale(
            detection_frame,
            winStride=(8, 8),
            padding=(8, 8),
            scale=1.05,
        )
        detections = [
            Detection(
                x=int(x / ratio),
                y=int(y / ratio),
                width=int(w / ratio),
                height=int(h / ratio),
                confidence=round(float(weights[index]), 3) if len(weights) > index else 0.0,
            )
            for index, (x, y, w, h) in enumerate(boxes)
        ]
        count = len(detections)
        if len(weights) == 0:
            return DetectionResult(count, 0.0, detections)
        confidence = min(1.0, max(0.0, float(sum(weights) / len(weights))))
        return DetectionResult(count, round(confidence, 3), detections)


class YoloDetector:
    def __init__(self, model_name: str) -> None:
        try:
            from ultralytics import YOLO
        except ImportError as exc:
            raise RuntimeError(
                "YOLO detector requires `pip install -r requirements-yolo.txt`"
            ) from exc

        self.model = YOLO(model_name)

    def detect(self, frame: Any | None) -> DetectionResult:
        if frame is None:
            return DetectionResult(0, 0.0, [])

        result = self.model(frame, classes=[0], verbose=False)[0]
        boxes = result.boxes
        if boxes is None or boxes.conf is None:
            return DetectionResult(0, 0.0, [])

        confidences = boxes.conf.detach().cpu().numpy().tolist()
        coordinates = boxes.xyxy.detach().cpu().numpy().tolist() if boxes.xyxy is not None else []
        detections = [
            Detection(
                x=int(left),
                y=int(top),
                width=int(right - left),
                height=int(bottom - top),
                confidence=round(float(confidences[index]), 3),
            )
            for index, (left, top, right, bottom) in enumerate(coordinates)
        ]
        count = len(confidences)
        confidence = float(sum(confidences) / count) if count else 0.0
        return DetectionResult(count, round(confidence, 3), detections)


class PreviewServer:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.host, self.port = parse_preview_addr(cfg.preview_addr)
        self.lock = threading.Lock()
        self.jpeg: bytes | None = None
        self.state: dict[str, Any] = {
            "ready": False,
            "worker_id": cfg.worker_id,
            "room_id": cfg.room_id,
            "source": cfg.camera_source,
            "detector": cfg.detector,
            "count": 0,
            "raw_count": 0,
            "confidence": 0.0,
            "fps": 0.0,
            "detections": [],
            "updated_at": None,
        }
        self.server: ThreadingHTTPServer | None = None

    def start(self) -> None:
        handler = self._handler()
        self.server = ThreadingHTTPServer((self.host, self.port), handler)
        thread = threading.Thread(target=self.server.serve_forever, name="preview-server", daemon=True)
        thread.start()
        print(json.dumps({
            "event": "preview_started",
            "addr": f"{self.host}:{self.port}",
            "auth": bool(self.cfg.preview_token),
        }), flush=True)

    def update(self, frame: Any, state: dict[str, Any]) -> None:
        import cv2

        ok, buffer = cv2.imencode(
            ".jpg",
            frame,
            [int(cv2.IMWRITE_JPEG_QUALITY), self.cfg.preview_jpeg_quality],
        )
        if not ok:
            return
        with self.lock:
            self.jpeg = buffer.tobytes()
            self.state = {"ready": True, **state}

    def snapshot(self) -> tuple[bytes | None, dict[str, Any]]:
        with self.lock:
            return self.jpeg, dict(self.state)

    def _authorized(self, path: str, headers: Any) -> bool:
        if not self.cfg.preview_token:
            return True
        parsed = urlparse(path)
        token = headers.get("X-Preview-Token") or parse_qs(parsed.query).get("token", [""])[0]
        return token == self.cfg.preview_token

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        preview = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "AudienceFlowPreview/1.0"

            def do_GET(self) -> None:
                parsed = urlparse(self.path)
                if parsed.path == "/healthz":
                    self._send_json({"status": "ok"})
                    return
                if not preview._authorized(self.path, self.headers):
                    self.send_error(401, "preview token required")
                    return
                if parsed.path == "/v1/state":
                    _, state = preview.snapshot()
                    self._send_json(state)
                    return
                if parsed.path == "/v1/frame.jpg":
                    jpeg, _ = preview.snapshot()
                    if jpeg is None:
                        self.send_error(503, "frame is not ready")
                        return
                    self._send_frame(jpeg)
                    return
                if parsed.path == "/v1/stream.mjpg":
                    self._send_stream()
                    return
                self.send_error(404)

            def _send_json(self, payload: dict[str, Any]) -> None:
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _send_frame(self, jpeg: bytes) -> None:
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(jpeg)))
                self.end_headers()
                self.wfile.write(jpeg)

            def _send_stream(self) -> None:
                boundary = "audienceflow"
                self.send_response(200)
                self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={boundary}")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                while True:
                    jpeg, _ = preview.snapshot()
                    if jpeg is None:
                        time.sleep(0.2)
                        continue
                    try:
                        self.wfile.write(f"--{boundary}\r\n".encode("ascii"))
                        self.wfile.write(b"Content-Type: image/jpeg\r\n")
                        self.wfile.write(f"Content-Length: {len(jpeg)}\r\n\r\n".encode("ascii"))
                        self.wfile.write(jpeg)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    time.sleep(0.1)

            def log_message(self, format: str, *args: Any) -> None:
                return

        return Handler


class GatewayClient:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.session = requests.Session()
        self.session.headers.update({
            "Content-Type": "application/json",
            "X-Ingest-Key": cfg.ingest_api_key,
        })

    def send(self, count: int, confidence: float) -> None:
        payload = {
            "room_id": self.cfg.room_id,
            "ts": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "count": count,
            "confidence": confidence,
            "worker_id": self.cfg.worker_id,
        }
        response = self.session.post(
            self.cfg.gateway_url,
            data=json.dumps(payload),
            timeout=self.cfg.request_timeout_seconds,
        )
        response.raise_for_status()
        print(json.dumps({"event": "sent", **payload}, ensure_ascii=False), flush=True)


def main() -> int:
    cfg = Config.from_env()
    cfg.validate()

    detector = create_detector(cfg)
    stabilizer = CountStabilizer(cfg.stabilization_window, cfg.confidence_threshold)
    gateway = GatewayClient(cfg)
    preview = PreviewServer(cfg) if cfg.preview_enabled else None
    if preview:
        preview.start()

    print(json.dumps({
        "event": "worker_started",
        "worker_id": cfg.worker_id,
        "room_id": cfg.room_id,
        "source": cfg.camera_source,
        "detector": cfg.detector,
    }, ensure_ascii=False), flush=True)

    if cfg.camera_source == "simulation" or cfg.detector == "simulation":
        return run_simulation_loop(cfg, detector, stabilizer, gateway, preview)

    return run_camera_loop(cfg, detector, stabilizer, gateway, preview)


def run_simulation_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    gateway: GatewayClient,
    preview: PreviewServer | None,
) -> int:
    import cv2

    last_frame_at = time.monotonic()
    last_sent = 0.0
    while True:
        result = detector.detect(None)
        stable_count, stable_confidence = stabilizer.push(result.count, result.confidence)
        now = time.monotonic()
        fps = 1 / max(now - last_frame_at, 0.001)
        last_frame_at = now
        if preview:
            frame = build_simulation_frame(result.detections)
            preview.update(
                draw_overlay(cv2, frame, result.detections, stable_count, stable_confidence, cfg, fps),
                preview_state(cfg, result, stable_count, stable_confidence, fps),
            )
        if now - last_sent >= cfg.send_interval_seconds:
            safe_send(gateway, stable_count, stable_confidence)
            last_sent = now
        time.sleep(0.1)


def run_camera_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    gateway: GatewayClient,
    preview: PreviewServer | None,
) -> int:
    import cv2

    source: int | str = int(cfg.camera_source) if cfg.camera_source.isdigit() else cfg.camera_source
    capture = cv2.VideoCapture(source)
    if not capture.isOpened():
        raise RuntimeError(f"Cannot open camera source: {cfg.camera_source}")

    last_sent = 0.0
    last_frame_at = time.monotonic()
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                print(json.dumps({"event": "frame_read_failed"}), flush=True)
                time.sleep(1)
                continue

            result = detector.detect(frame)
            stable_count, stable_confidence = stabilizer.push(result.count, result.confidence)

            now = time.monotonic()
            fps = 1 / max(now - last_frame_at, 0.001)
            last_frame_at = now
            if preview:
                preview.update(
                    draw_overlay(cv2, frame, result.detections, stable_count, stable_confidence, cfg, fps),
                    preview_state(cfg, result, stable_count, stable_confidence, fps),
                )
            if now - last_sent >= cfg.send_interval_seconds:
                safe_send(gateway, stable_count, stable_confidence)
                last_sent = now
    finally:
        capture.release()


def safe_send(gateway: GatewayClient, count: int, confidence: float) -> None:
    try:
        gateway.send(count, confidence)
    except requests.RequestException as exc:
        print(json.dumps({"event": "send_failed", "error": str(exc)}), file=sys.stderr, flush=True)


def create_detector(cfg: Config) -> Detector:
    if cfg.detector == "simulation":
        return SimulationDetector()
    if cfg.detector == "hog":
        return HogDetector()
    return YoloDetector(cfg.yolo_model)


def resize_for_detection(cv2: Any, frame: Any, max_width: int = 960) -> tuple[Any, float]:
    height, width = frame.shape[:2]
    if width <= max_width:
        return frame, 1.0
    ratio = max_width / width
    return cv2.resize(frame, (max_width, int(height * ratio))), ratio


def draw_overlay(
    cv2: Any,
    frame: Any,
    detections: list[Detection],
    stable_count: int,
    stable_confidence: float,
    cfg: Config,
    fps: float,
) -> Any:
    output, ratio = resize_for_preview(cv2, frame, cfg.preview_width)
    scaled_detections = [detection.scaled(ratio) for detection in detections]

    for index, detection in enumerate(scaled_detections, start=1):
        left, top = detection.x, detection.y
        right, bottom = detection.x + detection.width, detection.y + detection.height
        cv2.rectangle(output, (left, top), (right, bottom), (12, 65, 194), 2)
        label = f"#{detection.track_id or index} {detection.confidence:.2f}"
        cv2.rectangle(output, (left, max(0, top - 24)), (left + 96, top), (12, 65, 194), -1)
        cv2.putText(output, label, (left + 6, max(16, top - 7)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

    if cfg.line_config:
        x1, y1, x2, y2 = [int(value * ratio) for value in cfg.line_config]
        cv2.line(output, (x1, y1), (x2, y2), (15, 118, 110), 3)
        cv2.putText(output, "line", (x1 + 8, y1 - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (15, 118, 110), 2)

    cv2.rectangle(output, (16, 16), (430, 86), (28, 25, 23), -1)
    cv2.putText(output, f"AudienceFlow  people: {stable_count}", (32, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 255, 255), 2)
    cv2.putText(output, f"conf {stable_confidence:.2f}   fps {fps:.1f}", (32, 72), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (254, 243, 199), 1)
    return output


def resize_for_preview(cv2: Any, frame: Any, max_width: int) -> tuple[Any, float]:
    height, width = frame.shape[:2]
    if width <= max_width:
        return frame.copy(), 1.0
    ratio = max_width / width
    return cv2.resize(frame, (max_width, int(height * ratio))), ratio


def build_simulation_frame(detections: list[Detection]) -> Any:
    import cv2
    import numpy as np

    frame = np.full((540, 960, 3), (249, 250, 251), dtype=np.uint8)
    cv2.rectangle(frame, (48, 58), (912, 482), (232, 238, 244), 2)
    for x in range(100, 880, 90):
        cv2.line(frame, (x, 110), (x + 34, 440), (225, 229, 235), 1)
    for y in range(140, 430, 72):
        cv2.line(frame, (78, y), (882, y), (225, 229, 235), 1)
    for detection in detections:
        cx = detection.x + detection.width // 2
        cy = detection.y + detection.height // 2
        cv2.circle(frame, (cx, cy), max(10, detection.width // 3), (45, 55, 72), -1)
    cv2.putText(frame, "simulation camera", (62, 520), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (87, 83, 78), 1)
    return frame


def simulation_detections(count: int, elapsed: float) -> list[Detection]:
    detections: list[Detection] = []
    visible = min(count, 28)
    for index in range(visible):
        column = index % 7
        row = index // 7
        drift = int(12 * math.sin(elapsed / 4 + index))
        x = 112 + column * 112 + drift
        y = 122 + row * 82 + int(8 * math.cos(elapsed / 5 + index))
        detections.append(Detection(x=x, y=y, width=38, height=58, confidence=0.86, track_id=index + 1))
    return detections


def preview_state(
    cfg: Config,
    result: DetectionResult,
    stable_count: int,
    stable_confidence: float,
    fps: float,
) -> dict[str, Any]:
    return {
        "worker_id": cfg.worker_id,
        "room_id": cfg.room_id,
        "source": cfg.camera_source,
        "detector": cfg.detector,
        "count": stable_count,
        "raw_count": result.count,
        "confidence": stable_confidence,
        "fps": round(fps, 1),
        "detections": [detection.to_json() for detection in result.detections],
        "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def parse_preview_addr(value: str) -> tuple[str, int]:
    if value.isdigit():
        return "127.0.0.1", int(value)
    if value.startswith(":"):
        return "0.0.0.0", int(value[1:])
    host, port = value.rsplit(":", 1)
    return host, int(port)


def parse_line_config(value: str) -> tuple[int, int, int, int] | None:
    if not value.strip():
        return None
    parts = [part.strip() for part in value.split(",")]
    if len(parts) != 4:
        raise ValueError("LINE_CONFIG must contain x1,y1,x2,y2")
    return tuple(int(part) for part in parts)  # type: ignore[return-value]


def env_bool(key: str, default: str) -> bool:
    value = env(key, default).strip().lower()
    return value in {"1", "true", "yes", "on"}


def env(key: str, default: str) -> str:
    value = os.getenv(key)
    return value if value is not None and value != "" else default


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print(json.dumps({"event": "worker_stopped"}), flush=True)
        raise SystemExit(0)
