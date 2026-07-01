from __future__ import annotations

import argparse
import hmac
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
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import parse_qs, urlparse

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

DEFAULT_SAMPLE_VIDEO_URL = "https://raw.githubusercontent.com/intel-iot-devkit/sample-videos/master/people-detection.mp4"


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

    def with_track(self, track_id: int) -> "Detection":
        return Detection(
            x=self.x,
            y=self.y,
            width=self.width,
            height=self.height,
            confidence=self.confidence,
            track_id=track_id,
        )

    def center(self) -> tuple[float, float]:
        return self.x + self.width / 2, self.y + self.height / 2

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
class OverlayOptions:
    preview_width: int


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
    sample_video_url: str
    video_loop: bool
    source_reconnect_seconds: float
    source_read_failure_limit: int

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
            sample_video_url=env("SAMPLE_VIDEO_URL", DEFAULT_SAMPLE_VIDEO_URL),
            video_loop=env_bool("VIDEO_LOOP", "true"),
            source_reconnect_seconds=float(env("SOURCE_RECONNECT_SECONDS", "3")),
            source_read_failure_limit=int(env("SOURCE_READ_FAILURE_LIMIT", "12")),
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
        if self.source_reconnect_seconds <= 0:
            raise ValueError("SOURCE_RECONNECT_SECONDS must be positive")
        if self.source_read_failure_limit < 1:
            raise ValueError("SOURCE_READ_FAILURE_LIMIT must be positive")
        if self.preview_enabled:
            parse_preview_addr(self.preview_addr)


@dataclass(frozen=True)
class VideoSource:
    raw: str
    capture_source: int | str
    kind: str
    label: str
    loop: bool

    @property
    def is_live(self) -> bool:
        return self.kind in {"device", "rtsp", "mjpeg", "phone"}


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


class LineCrossingCounter:
    def __init__(self, initial_line: tuple[int, int, int, int] | None) -> None:
        self.lock = threading.Lock()
        self.line_config = initial_line
        self.entered = 0
        self.exited = 0
        self.next_track_id = 100_000
        self.tracks: dict[int, dict[str, Any]] = {}
        self.max_distance = 120.0
        self.max_age_seconds = 2.5
        self.dead_zone_px = 6.0

    def update(self, detections: list[Detection]) -> tuple[list[Detection], dict[str, Any]]:
        now = time.monotonic()
        with self.lock:
            self._expire(now)
            assigned: set[int] = set()
            tracked: list[Detection] = []
            for detection in detections:
                track_id = self._assign_track_id(detection, assigned)
                assigned.add(track_id)
                tracked.append(self._update_track(track_id, detection, now))
            return tracked, self._snapshot_unlocked()

    def set_line(self, line_config: tuple[int, int, int, int] | None) -> dict[str, Any]:
        with self.lock:
            self.line_config = line_config
            self.tracks.clear()
            return self._snapshot_unlocked()

    def reset(self) -> dict[str, Any]:
        with self.lock:
            self.entered = 0
            self.exited = 0
            self.tracks.clear()
            return self._snapshot_unlocked()

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            self._expire(time.monotonic())
            return self._snapshot_unlocked()

    def _assign_track_id(self, detection: Detection, assigned: set[int]) -> int:
        if detection.track_id is not None:
            return detection.track_id

        cx, cy = detection.center()
        best_track_id: int | None = None
        best_distance = self.max_distance
        for track_id, track in self.tracks.items():
            if track_id in assigned:
                continue
            tx, ty = track["center"]
            distance = math.hypot(cx - tx, cy - ty)
            if distance < best_distance:
                best_distance = distance
                best_track_id = track_id

        if best_track_id is not None:
            return best_track_id

        track_id = self.next_track_id
        self.next_track_id += 1
        return track_id

    def _update_track(self, track_id: int, detection: Detection, now: float) -> Detection:
        center = detection.center()
        side = self._side(center)
        previous = self.tracks.get(track_id)
        previous_side = int(previous["side"]) if previous else 0

        if (
            self.line_config
            and previous_side != 0
            and side != 0
            and previous_side != side
            and self._inside_segment(center)
        ):
            if previous_side < side:
                self.entered += 1
            else:
                self.exited += 1

        self.tracks[track_id] = {
            "center": center,
            "side": side if side != 0 else previous_side,
            "last_seen": now,
        }
        return detection.with_track(track_id)

    def _side(self, center: tuple[float, float]) -> int:
        if not self.line_config:
            return 0
        x1, y1, x2, y2 = self.line_config
        dx = x2 - x1
        dy = y2 - y1
        length = math.hypot(dx, dy)
        if length < 1:
            return 0
        cx, cy = center
        signed_distance = (dx * (cy - y1) - dy * (cx - x1)) / length
        if abs(signed_distance) < self.dead_zone_px:
            return 0
        return 1 if signed_distance > 0 else -1

    def _inside_segment(self, center: tuple[float, float]) -> bool:
        if not self.line_config:
            return False
        x1, y1, x2, y2 = self.line_config
        dx = x2 - x1
        dy = y2 - y1
        length_squared = dx * dx + dy * dy
        if length_squared < 1:
            return False
        cx, cy = center
        projection = ((cx - x1) * dx + (cy - y1) * dy) / length_squared
        return -0.08 <= projection <= 1.08

    def _expire(self, now: float) -> None:
        expired = [
            track_id
            for track_id, track in self.tracks.items()
            if now - float(track["last_seen"]) > self.max_age_seconds
        ]
        for track_id in expired:
            del self.tracks[track_id]

    def _snapshot_unlocked(self) -> dict[str, Any]:
        return {
            "line": line_to_json(self.line_config),
            "entered": self.entered,
            "exited": self.exited,
            "balance": self.entered - self.exited,
            "active_tracks": len(self.tracks),
        }


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
    def __init__(self, cfg: Config, line_counter: LineCrossingCounter) -> None:
        self.cfg = cfg
        self.line_counter = line_counter
        self.host, self.port = parse_preview_addr(cfg.preview_addr)
        self.lock = threading.Lock()
        self.jpeg: bytes | None = None
        self.state: dict[str, Any] = {
            "ready": False,
            "worker_id": cfg.worker_id,
            "room_id": cfg.room_id,
            "source": source_label(cfg),
            "detector": cfg.detector,
            "count": 0,
            "raw_count": 0,
            "confidence": 0.0,
            "fps": 0.0,
            "detections": [],
            "updated_at": None,
            **line_counter.snapshot(),
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

    def merge_state(self, changes: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            self.state = {**self.state, **changes}
            return dict(self.state)

    def _authorized(self, path: str, headers: Any) -> bool:
        if not self.cfg.preview_token:
            return True
        parsed = urlparse(path)
        token = headers.get("X-Preview-Token") or parse_qs(parsed.query).get("token", [""])[0]
        return hmac.compare_digest(token or "", self.cfg.preview_token)

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

            def do_OPTIONS(self) -> None:
                self.send_response(204)
                self._send_cors_headers()
                self.end_headers()

            def do_POST(self) -> None:
                parsed = urlparse(self.path)
                if not preview._authorized(self.path, self.headers):
                    self.send_error(401, "preview token required")
                    return
                try:
                    if parsed.path == "/v1/line":
                        payload = self._read_json()
                        state = preview.merge_state(preview.line_counter.set_line(parse_line_payload(payload)))
                        self._send_json(state)
                        return
                    if parsed.path == "/v1/counters/reset":
                        self._read_json(allow_empty=True)
                        self._send_json(preview.merge_state(preview.line_counter.reset()))
                        return
                except ValueError as exc:
                    self.send_error(400, str(exc))
                    return
                self.send_error(404)

            def _read_json(self, allow_empty: bool = False) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0:
                    if allow_empty:
                        return {}
                    raise ValueError("JSON body is required")
                if length > 4096:
                    raise ValueError("JSON body is too large")
                raw = self.rfile.read(length)
                try:
                    payload = json.loads(raw.decode("utf-8"))
                except json.JSONDecodeError as exc:
                    raise ValueError("Invalid JSON body") from exc
                if not isinstance(payload, dict):
                    raise ValueError("JSON body must be an object")
                return payload

            def _send_json(self, payload: dict[str, Any]) -> None:
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _send_frame(self, jpeg: bytes) -> None:
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(jpeg)))
                self.end_headers()
                self.wfile.write(jpeg)

            def _send_stream(self) -> None:
                boundary = "audienceflow"
                self.send_response(200)
                self._send_cors_headers()
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

            def _send_cors_headers(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Preview-Token")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

        return Handler


class GatewayClient:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.session = requests.Session()
        self.session.headers.update({
            "Content-Type": "application/json",
            "X-Ingest-Key": cfg.ingest_api_key,
        })
        # Bounded exponential-backoff retry with jitter so a brief gateway blip
        # doesn't drop the interval's event. Keep the total capped well under the
        # send interval to avoid stacking sends.
        retry = Retry(
            total=2,
            backoff_factor=0.3,
            backoff_jitter=0.2,
            status_forcelist=(500, 502, 503, 504),
            allowed_methods=frozenset({"POST"}),
            raise_on_status=False,
        )
        adapter = HTTPAdapter(max_retries=retry)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

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
    line_counter = LineCrossingCounter(cfg.line_config)
    gateway = GatewayClient(cfg)
    preview = PreviewServer(cfg, line_counter) if cfg.preview_enabled else None
    if preview:
        preview.start()

    print(json.dumps({
        "event": "worker_started",
        "worker_id": cfg.worker_id,
        "room_id": cfg.room_id,
        "source": source_label(cfg),
        "detector": cfg.detector,
    }, ensure_ascii=False), flush=True)

    if cfg.camera_source == "simulation" or cfg.detector == "simulation":
        return run_simulation_loop(cfg, detector, stabilizer, line_counter, gateway, preview)

    return run_camera_loop(cfg, detector, stabilizer, line_counter, gateway, preview)


def run_simulation_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    line_counter: LineCrossingCounter,
    gateway: GatewayClient,
    preview: PreviewServer | None,
) -> int:
    import cv2

    last_frame_at = time.monotonic()
    last_sent = 0.0
    while True:
        result = detector.detect(None)
        tracked_detections, line_state = line_counter.update(result.detections)
        tracked_result = DetectionResult(result.count, result.confidence, tracked_detections)
        stable_count, stable_confidence = stabilizer.push(result.count, result.confidence)
        now = time.monotonic()
        fps = 1 / max(now - last_frame_at, 0.001)
        last_frame_at = now
        if preview:
            frame = build_simulation_frame(tracked_detections)
            preview.update(
                draw_overlay(cv2, frame, tracked_detections, stable_count, stable_confidence, cfg, fps, line_state),
                preview_state(cfg, tracked_result, stable_count, stable_confidence, fps, line_state),
            )
        if now - last_sent >= cfg.send_interval_seconds:
            safe_send(gateway, stable_count, stable_confidence)
            last_sent = now
        time.sleep(0.1)


def run_camera_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    line_counter: LineCrossingCounter,
    gateway: GatewayClient,
    preview: PreviewServer | None,
) -> int:
    import cv2

    source = resolve_video_source(cfg)
    print(json.dumps({
        "event": "source_resolved",
        "kind": source.kind,
        "source": source.label,
        "loop": source.loop,
    }, ensure_ascii=False), flush=True)

    if source.is_live:
        configure_live_ffmpeg_options(source)

    last_sent = 0.0
    last_frame_at = time.monotonic()
    while True:
        capture = cv2.VideoCapture(source.capture_source)
        if source.is_live:
            apply_live_capture_timeouts(cv2, capture)
        if not capture.isOpened():
            if preview:
                preview.merge_state({"ready": False, "stale": True})
            print(json.dumps({
                "event": "source_open_failed",
                "kind": source.kind,
                "source": source.label,
                "retry_seconds": cfg.source_reconnect_seconds,
            }, ensure_ascii=False), file=sys.stderr, flush=True)
            time.sleep(cfg.source_reconnect_seconds)
            continue

        read_failures = 0
        while True:
            ok, frame = capture.read()
            if not ok:
                if source.loop and not source.is_live:
                    capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    read_failures = 0
                    print(json.dumps({
                        "event": "source_looped",
                        "kind": source.kind,
                        "source": source.label,
                    }, ensure_ascii=False), flush=True)
                    time.sleep(0.05)
                    continue

                read_failures += 1
                print(json.dumps({
                    "event": "frame_read_failed",
                    "kind": source.kind,
                    "source": source.label,
                    "failures": read_failures,
                }, ensure_ascii=False), file=sys.stderr, flush=True)
                if read_failures >= cfg.source_read_failure_limit:
                    break
                time.sleep(0.25)
                continue

            if read_failures and preview:
                preview.merge_state({"ready": True, "stale": False})
            read_failures = 0

            result = detector.detect(frame)
            tracked_detections, line_state = line_counter.update(result.detections)
            tracked_result = DetectionResult(result.count, result.confidence, tracked_detections)
            stable_count, stable_confidence = stabilizer.push(result.count, result.confidence)

            now = time.monotonic()
            fps = 1 / max(now - last_frame_at, 0.001)
            last_frame_at = now
            if preview:
                preview.update(
                    draw_overlay(cv2, frame, tracked_detections, stable_count, stable_confidence, cfg, fps, line_state),
                    preview_state(cfg, tracked_result, stable_count, stable_confidence, fps, line_state),
                )
            if now - last_sent >= cfg.send_interval_seconds:
                safe_send(gateway, stable_count, stable_confidence)
                last_sent = now
        capture.release()
        if preview:
            preview.merge_state({"ready": False, "stale": True})
        print(json.dumps({
            "event": "source_reconnect",
            "kind": source.kind,
            "source": source.label,
            "retry_seconds": cfg.source_reconnect_seconds,
        }, ensure_ascii=False), file=sys.stderr, flush=True)
        time.sleep(cfg.source_reconnect_seconds)


def configure_live_ffmpeg_options(source: VideoSource) -> None:
    """Set FFmpeg capture options so opens/reads on a live source time out instead
    of blocking forever, letting the reconnect logic engage on a stalled stream.

    Only applied to live kinds (rtsp/mjpeg/http/phone/device); files and the sample
    video are left untouched. Uses setdefault so an operator-provided override wins.
    """
    if source.kind == "rtsp":
        # 5s socket timeout (microseconds), prefer TCP transport.
        os.environ.setdefault(
            "OPENCV_FFMPEG_CAPTURE_OPTIONS",
            "rtsp_transport;tcp|stimeout;5000000",
        )
    else:
        os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "timeout;5000000")


def apply_live_capture_timeouts(cv2: Any, capture: Any) -> None:
    """Apply open/read timeouts on a VideoCapture for live sources when the
    OpenCV build exposes them (older builds lack these constants)."""
    for attr, milliseconds in (
        ("CAP_PROP_OPEN_TIMEOUT_MSEC", 5000),
        ("CAP_PROP_READ_TIMEOUT_MSEC", 5000),
    ):
        prop = getattr(cv2, attr, None)
        if prop is None:
            continue
        try:
            capture.set(prop, milliseconds)
        except Exception:
            continue


def safe_send(gateway: GatewayClient, count: int, confidence: float) -> None:
    try:
        gateway.send(count, confidence)
    except requests.RequestException as exc:
        print(json.dumps({"event": "send_failed", "error": str(exc)}), file=sys.stderr, flush=True)


def cli(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "analyze":
        return analyze_cli(args[1:])
    return main()


def analyze_cli(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m app.main analyze",
        description="Detect and count people in a photo or video file.",
    )
    parser.add_argument("source", help="Path to image or video file")
    parser.add_argument("--media-type", choices=["auto", "image", "video"], default="auto")
    parser.add_argument("--detector", choices=["hog", "yolo", "simulation"], default=env("DETECTOR", "hog").lower())
    parser.add_argument("--yolo-model", default=env("YOLO_MODEL", "yolov8n.pt"))
    parser.add_argument("--output", help="Optional annotated image/video output path")
    parser.add_argument("--json-output", help="Optional path for the JSON report")
    parser.add_argument("--preview-width", type=int, default=int(env("PREVIEW_WIDTH", "1280")))
    parser.add_argument("--confidence-threshold", type=float, default=float(env("CONFIDENCE_THRESHOLD", "0.45")))
    parser.add_argument("--stabilization-window", type=int, default=int(env("STABILIZATION_WINDOW", "5")))
    parser.add_argument("--frame-step", type=int, default=5, help="Analyze each Nth frame for video")
    parser.add_argument("--max-frames", type=int, default=300, help="Maximum sampled frames for video")
    parser.add_argument("--line", help="Optional video flow line as x1,y1,x2,y2")

    args = parser.parse_args(argv)
    source = Path(args.source).expanduser()
    if not source.exists() or not source.is_file():
        raise FileNotFoundError(f"Media file not found: {source}")
    if args.preview_width < 320:
        raise ValueError("--preview-width must be at least 320")
    if args.frame_step < 1:
        raise ValueError("--frame-step must be positive")
    if args.max_frames < 1:
        raise ValueError("--max-frames must be positive")
    if not 0 <= args.confidence_threshold <= 1:
        raise ValueError("--confidence-threshold must be between 0 and 1")

    detector = create_detector_by_name(args.detector, args.yolo_model)
    media_type = resolve_media_type(source, args.media_type)
    output = Path(args.output).expanduser() if args.output else None
    json_output = Path(args.json_output).expanduser() if args.json_output else None
    line_config = parse_line_config(args.line) if args.line else None

    if media_type == "image":
        report = analyze_image_file(
            source,
            detector,
            args.detector,
            output,
            args.preview_width,
            args.confidence_threshold,
            line_config,
        )
    else:
        report = analyze_video_file(
            source,
            detector,
            args.detector,
            output,
            args.preview_width,
            args.confidence_threshold,
            args.stabilization_window,
            args.frame_step,
            args.max_frames,
            line_config,
        )

    body = json.dumps(report, ensure_ascii=False, indent=2)
    if json_output:
        json_output.parent.mkdir(parents=True, exist_ok=True)
        json_output.write_text(body + "\n", encoding="utf-8")
    print(body, flush=True)
    return 0


def analyze_image_file(
    source: Path,
    detector: Detector,
    detector_name: str,
    output: Path | None,
    preview_width: int,
    confidence_threshold: float,
    line_config: tuple[int, int, int, int] | None,
) -> dict[str, Any]:
    import cv2

    frame = cv2.imread(str(source))
    if frame is None:
        raise ValueError(f"Cannot read image file: {source}")

    stabilizer = CountStabilizer(1, confidence_threshold)
    line_counter = LineCrossingCounter(line_config)
    analysis = analyze_frame(cv2, frame, detector, stabilizer, line_counter, preview_width, fps=0.0)
    output_path = write_annotated_image(cv2, output, analysis["overlay"]) if output else None

    return {
        "media_type": "image",
        "source": str(source),
        "detector": detector_name,
        "count": analysis["count"],
        "raw_count": analysis["raw_count"],
        "confidence": analysis["confidence"],
        "detections": [detection.to_json() for detection in analysis["detections"]],
        "line": analysis["line_state"].get("line"),
        "output": str(output_path) if output_path else None,
        "analyzed_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def analyze_video_file(
    source: Path,
    detector: Detector,
    detector_name: str,
    output: Path | None,
    preview_width: int,
    confidence_threshold: float,
    stabilization_window: int,
    frame_step: int,
    max_frames: int,
    line_config: tuple[int, int, int, int] | None,
) -> dict[str, Any]:
    import cv2

    capture = cv2.VideoCapture(str(source))
    if not capture.isOpened():
        raise ValueError(f"Cannot open video file: {source}")

    fps = capture.get(cv2.CAP_PROP_FPS) or 25.0
    total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    stabilizer = CountStabilizer(stabilization_window, confidence_threshold)
    line_counter = LineCrossingCounter(line_config)
    writer: Any | None = None
    frame_index = -1
    sampled = 0
    counts: list[int] = []
    confidences: list[float] = []
    frames: list[dict[str, Any]] = []
    output_path: Path | None = None

    try:
        while sampled < max_frames:
            ok, frame = capture.read()
            if not ok:
                break
            frame_index += 1
            if frame_index % frame_step != 0:
                continue

            analysis = analyze_frame(cv2, frame, detector, stabilizer, line_counter, preview_width, fps=fps / frame_step)
            counts.append(int(analysis["count"]))
            confidences.append(float(analysis["confidence"]))
            sampled += 1
            frames.append({
                "frame": frame_index,
                "time_seconds": round(frame_index / max(fps, 0.001), 3),
                "count": analysis["count"],
                "raw_count": analysis["raw_count"],
                "confidence": analysis["confidence"],
                "detections": len(analysis["detections"]),
            })

            if output:
                if writer is None:
                    output_path, writer = create_video_writer(cv2, output, analysis["overlay"], fps / frame_step)
                writer.write(analysis["overlay"])
    finally:
        capture.release()
        if writer is not None:
            writer.release()

    if not counts:
        raise ValueError(f"No frames were analyzed in video: {source}")

    return {
        "media_type": "video",
        "source": str(source),
        "detector": detector_name,
        "count": int(round(statistics.median(counts))),
        "average_count": round(statistics.fmean(counts), 2),
        "peak_count": max(counts),
        "last_count": counts[-1],
        "average_confidence": round(statistics.fmean(confidences), 3) if confidences else 0.0,
        "frames_analyzed": sampled,
        "frame_step": frame_step,
        "total_frames": total_frames,
        "fps": round(fps, 3),
        "line": line_counter.snapshot(),
        "frames": frames,
        "output": str(output_path) if output_path else None,
        "analyzed_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def analyze_frame(
    cv2: Any,
    frame: Any,
    detector: Detector,
    stabilizer: CountStabilizer,
    line_counter: LineCrossingCounter,
    preview_width: int,
    fps: float,
) -> dict[str, Any]:
    result = detector.detect(frame)
    tracked_detections, line_state = line_counter.update(result.detections)
    stable_count, stable_confidence = stabilizer.push(result.count, result.confidence)
    overlay = draw_overlay(
        cv2,
        frame,
        tracked_detections,
        stable_count,
        stable_confidence,
        OverlayOptions(preview_width),
        fps,
        line_state,
    )
    return {
        "count": stable_count,
        "raw_count": result.count,
        "confidence": stable_confidence,
        "detections": tracked_detections,
        "line_state": line_state,
        "overlay": overlay,
    }


def write_annotated_image(cv2: Any, output: Path, overlay: Any) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(output), overlay):
        raise ValueError(f"Cannot write annotated image: {output}")
    return output


def create_video_writer(cv2: Any, output: Path, first_frame: Any, fps: float) -> tuple[Path, Any]:
    output.parent.mkdir(parents=True, exist_ok=True)
    height, width = first_frame.shape[:2]
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(output), fourcc, max(1.0, fps), (width, height))
    if not writer.isOpened():
        raise ValueError(f"Cannot write annotated video: {output}")
    return output, writer


def resolve_media_type(source: Path, requested: str) -> str:
    if requested != "auto":
        return requested
    suffix = source.suffix.lower()
    if suffix in {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}:
        return "image"
    if suffix in {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"}:
        return "video"
    raise ValueError("Cannot infer media type. Use --media-type image or --media-type video.")


def create_detector(cfg: Config) -> Detector:
    return create_detector_by_name(cfg.detector, cfg.yolo_model)


def create_detector_by_name(detector_name: str, yolo_model: str) -> Detector:
    detector = detector_name.lower()
    if detector == "simulation":
        return SimulationDetector()
    if detector == "hog":
        return HogDetector()
    if detector == "yolo":
        return YoloDetector(yolo_model)
    raise ValueError("detector must be one of: simulation, hog, yolo")


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
    line_state: dict[str, Any],
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

    line = line_state.get("line")
    if line:
        x1 = int(line["x1"] * ratio)
        y1 = int(line["y1"] * ratio)
        x2 = int(line["x2"] * ratio)
        y2 = int(line["y2"] * ratio)
        cv2.line(output, (x1, y1), (x2, y2), (15, 118, 110), 3)
        cv2.putText(output, "flow line", (x1 + 8, max(24, y1 - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (15, 118, 110), 2)

    cv2.rectangle(output, (16, 16), (460, 112), (28, 25, 23), -1)
    cv2.putText(output, f"AudienceFlow  people: {stable_count}", (32, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 255, 255), 2)
    cv2.putText(output, f"conf {stable_confidence:.2f}   fps {fps:.1f}", (32, 72), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (254, 243, 199), 1)
    cv2.putText(
        output,
        f"in {line_state.get('entered', 0)}   out {line_state.get('exited', 0)}   tracks {line_state.get('active_tracks', 0)}",
        (32, 99),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.58,
        (209, 250, 229),
        1,
    )
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
    line_state: dict[str, Any],
) -> dict[str, Any]:
    state = {
        "worker_id": cfg.worker_id,
        "room_id": cfg.room_id,
        "source": source_label(cfg),
        "detector": cfg.detector,
        "count": stable_count,
        "raw_count": result.count,
        "confidence": stable_confidence,
        "fps": round(fps, 1),
        "detections": [detection.to_json() for detection in result.detections],
        "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    state.update(line_state)
    return state


def resolve_video_source(cfg: Config) -> VideoSource:
    raw = cfg.camera_source.strip()
    alias, value = split_source_alias(raw)
    if alias in {"sample", "sample-video", "demo-video"} or raw in {"sample", "sample-video", "demo-video"}:
        source = value if alias and value else cfg.sample_video_url.strip()
        return VideoSource(raw=raw, capture_source=source, kind="sample", label=safe_source_label(source), loop=cfg.video_loop)
    if alias == "device" or value.isdigit():
        index = int(value)
        return VideoSource(raw=raw, capture_source=index, kind="device", label=f"device:{index}", loop=False)
    if alias in {"phone", "mjpeg", "ip"}:
        return VideoSource(raw=raw, capture_source=value, kind="phone" if alias == "phone" else "mjpeg", label=safe_source_label(value), loop=False)

    parsed = urlparse(value)
    scheme = parsed.scheme.lower()
    if scheme == "file":
        path = Path(parsed.path).expanduser()
        return VideoSource(raw=raw, capture_source=str(path), kind="file", label=str(path), loop=cfg.video_loop)
    if scheme == "rtsp":
        return VideoSource(raw=raw, capture_source=value, kind="rtsp", label=safe_source_label(value), loop=False)
    if scheme in {"http", "https"}:
        kind = "mjpeg" if looks_like_mjpeg(value) else "http"
        return VideoSource(raw=raw, capture_source=value, kind=kind, label=safe_source_label(value), loop=cfg.video_loop and kind == "http")

    path = Path(value).expanduser()
    if path.exists():
        return VideoSource(raw=raw, capture_source=str(path), kind="file", label=str(path), loop=cfg.video_loop)

    return VideoSource(raw=raw, capture_source=value, kind="stream", label=safe_source_label(value), loop=False)


def split_source_alias(raw: str) -> tuple[str, str]:
    if ":" not in raw:
        return "", raw
    prefix, rest = raw.split(":", 1)
    prefix = prefix.strip().lower()
    if prefix in {"device", "phone", "mjpeg", "ip", "sample", "sample-video", "demo-video"}:
        return prefix, rest.strip()
    return "", raw


def source_label(cfg: Config) -> str:
    if cfg.camera_source == "simulation" or cfg.detector == "simulation":
        return "simulation"
    try:
        return resolve_video_source(cfg).label
    except Exception:
        return safe_source_label(cfg.camera_source)


def looks_like_mjpeg(value: str) -> bool:
    lowered = value.lower()
    return any(marker in lowered for marker in ("mjpg", "mjpeg", "video.cgi", "videostream.cgi", "ipvideo"))


def safe_source_label(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme and parsed.netloc:
        host = parsed.hostname or parsed.netloc
        port = f":{parsed.port}" if parsed.port else ""
        path = parsed.path or ""
        if len(path) > 60:
            path = "..." + path[-57:]
        return f"{parsed.scheme}://{host}{port}{path}"
    if len(value) > 96:
        return value[:42] + "..." + value[-42:]
    return value


def parse_preview_addr(value: str) -> tuple[str, int]:
    value = value.strip()
    if value.isdigit():
        return "127.0.0.1", int(value)
    if value.startswith(":"):
        return "0.0.0.0", _parse_preview_port(value[1:])
    if value.startswith("["):
        host, sep, rest = value[1:].partition("]")
        if not sep or not rest.startswith(":"):
            raise ValueError("PREVIEW_ADDR must be host:port")
        return host, _parse_preview_port(rest[1:])
    if ":" not in value:
        raise ValueError("PREVIEW_ADDR must be host:port")
    host, port = value.rsplit(":", 1)
    return host, _parse_preview_port(port)


def _parse_preview_port(port: str) -> int:
    port = port.strip()
    if not port.isdigit():
        raise ValueError("PREVIEW_ADDR must be host:port")
    return int(port)


def parse_line_config(value: str) -> tuple[int, int, int, int] | None:
    if not value.strip():
        return None
    parts = [part.strip() for part in value.split(",")]
    if len(parts) != 4:
        raise ValueError("LINE_CONFIG must contain x1,y1,x2,y2")
    return tuple(int(part) for part in parts)  # type: ignore[return-value]


def parse_line_payload(payload: dict[str, Any]) -> tuple[int, int, int, int] | None:
    if payload.get("enabled") is False:
        return None
    try:
        line = (
            int(payload["x1"]),
            int(payload["y1"]),
            int(payload["x2"]),
            int(payload["y2"]),
        )
    except KeyError as exc:
        raise ValueError("Line payload must contain x1, y1, x2, y2") from exc
    except (TypeError, ValueError) as exc:
        raise ValueError("Line coordinates must be integers") from exc

    if line[0] == line[2] and line[1] == line[3]:
        raise ValueError("Line endpoints must be different")
    if any(value < 0 or value > 10_000 for value in line):
        raise ValueError("Line coordinates must be between 0 and 10000")
    return line


def line_to_json(line_config: tuple[int, int, int, int] | None) -> dict[str, int] | None:
    if line_config is None:
        return None
    x1, y1, x2, y2 = line_config
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2}


def env_bool(key: str, default: str) -> bool:
    value = env(key, default).strip().lower()
    return value in {"1", "true", "yes", "on"}


def env(key: str, default: str) -> str:
    value = os.getenv(key)
    return value if value is not None and value != "" else default


if __name__ == "__main__":
    try:
        raise SystemExit(cli())
    except KeyboardInterrupt:
        print(json.dumps({"event": "worker_stopped"}), flush=True)
        raise SystemExit(0)
