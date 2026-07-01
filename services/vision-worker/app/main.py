from __future__ import annotations

import json
import math
import os
import random
import socket
import statistics
import sys
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Protocol

import requests


class Detector(Protocol):
    def detect(self, frame: Any | None) -> tuple[int, float]:
        """Return detected person count and average confidence."""


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

    def detect(self, frame: Any | None) -> tuple[int, float]:
        elapsed = time.monotonic() - self.started
        baseline = 22 + 11 * math.sin(elapsed / 32)
        rush = 8 * math.sin(elapsed / 9)
        noise = random.randint(-3, 3)
        count = max(0, int(round(baseline + rush + noise)))
        confidence = round(random.uniform(0.78, 0.96), 3)
        return count, confidence


class HogDetector:
    def __init__(self) -> None:
        import cv2

        self.cv2 = cv2
        self.hog = cv2.HOGDescriptor()
        self.hog.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())

    def detect(self, frame: Any | None) -> tuple[int, float]:
        if frame is None:
            return 0, 0.0

        frame = resize_for_detection(self.cv2, frame)
        boxes, weights = self.hog.detectMultiScale(
            frame,
            winStride=(8, 8),
            padding=(8, 8),
            scale=1.05,
        )
        count = len(boxes)
        if len(weights) == 0:
            return count, 0.0
        confidence = min(1.0, max(0.0, float(sum(weights) / len(weights))))
        return count, round(confidence, 3)


class YoloDetector:
    def __init__(self, model_name: str) -> None:
        try:
            from ultralytics import YOLO
        except ImportError as exc:
            raise RuntimeError(
                "YOLO detector requires `pip install -r requirements-yolo.txt`"
            ) from exc

        self.model = YOLO(model_name)

    def detect(self, frame: Any | None) -> tuple[int, float]:
        if frame is None:
            return 0, 0.0

        result = self.model(frame, classes=[0], verbose=False)[0]
        boxes = result.boxes
        if boxes is None or boxes.conf is None:
            return 0, 0.0

        confidences = boxes.conf.detach().cpu().numpy().tolist()
        count = len(confidences)
        confidence = float(sum(confidences) / count) if count else 0.0
        return count, round(confidence, 3)


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

    print(json.dumps({
        "event": "worker_started",
        "worker_id": cfg.worker_id,
        "room_id": cfg.room_id,
        "source": cfg.camera_source,
        "detector": cfg.detector,
    }, ensure_ascii=False), flush=True)

    if cfg.camera_source == "simulation" or cfg.detector == "simulation":
        return run_simulation_loop(cfg, detector, stabilizer, gateway)

    return run_camera_loop(cfg, detector, stabilizer, gateway)


def run_simulation_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    gateway: GatewayClient,
) -> int:
    while True:
        raw_count, raw_confidence = detector.detect(None)
        stable_count, stable_confidence = stabilizer.push(raw_count, raw_confidence)
        safe_send(gateway, stable_count, stable_confidence)
        time.sleep(cfg.send_interval_seconds)


def run_camera_loop(
    cfg: Config,
    detector: Detector,
    stabilizer: CountStabilizer,
    gateway: GatewayClient,
) -> int:
    import cv2

    source: int | str = int(cfg.camera_source) if cfg.camera_source.isdigit() else cfg.camera_source
    capture = cv2.VideoCapture(source)
    if not capture.isOpened():
        raise RuntimeError(f"Cannot open camera source: {cfg.camera_source}")

    last_sent = 0.0
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                print(json.dumps({"event": "frame_read_failed"}), flush=True)
                time.sleep(1)
                continue

            raw_count, raw_confidence = detector.detect(frame)
            stable_count, stable_confidence = stabilizer.push(raw_count, raw_confidence)

            now = time.monotonic()
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


def resize_for_detection(cv2: Any, frame: Any, max_width: int = 960) -> Any:
    height, width = frame.shape[:2]
    if width <= max_width:
        return frame
    ratio = max_width / width
    return cv2.resize(frame, (max_width, int(height * ratio)))


def env(key: str, default: str) -> str:
    value = os.getenv(key)
    return value if value is not None and value != "" else default


if __name__ == "__main__":
    raise SystemExit(main())
