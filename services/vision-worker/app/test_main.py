"""Fast, deterministic tests for the vision-worker hardening fixes.

These cover the pure-Python paths touched by the production-hardening changes:
preview-addr parsing/validation, constant-time token auth, live-source FFmpeg
timeout configuration, and the gateway retry wiring. They avoid importing cv2 /
numpy (those imports are lazy inside the frame loops), so they run offline.
"""
from __future__ import annotations

import os

import pytest

from app import main


# --- parse_preview_addr (fix #4) ------------------------------------------


def test_parse_preview_addr_bare_port():
    assert main.parse_preview_addr("8090") == ("127.0.0.1", 8090)


def test_parse_preview_addr_colon_port_binds_all():
    assert main.parse_preview_addr(":8090") == ("0.0.0.0", 8090)


def test_parse_preview_addr_host_port():
    assert main.parse_preview_addr("127.0.0.1:8090") == ("127.0.0.1", 8090)


def test_parse_preview_addr_ipv6_bracketed():
    assert main.parse_preview_addr("[::1]:8090") == ("::1", 8090)


@pytest.mark.parametrize(
    "value",
    ["localhost", "example.com", "::1", "[::1]", "host:abc", "[::1]8090", ":", ""],
)
def test_parse_preview_addr_rejects_missing_numeric_port(value):
    with pytest.raises(ValueError, match="PREVIEW_ADDR must be host:port"):
        main.parse_preview_addr(value)


def test_config_validate_rejects_bad_preview_addr(monkeypatch):
    cfg = _base_config(preview_enabled=True, preview_addr="localhost")
    with pytest.raises(ValueError, match="PREVIEW_ADDR must be host:port"):
        cfg.validate()


def test_config_validate_skips_addr_when_preview_disabled():
    # A bogus addr is tolerated when the preview server is not started.
    cfg = _base_config(preview_enabled=False, preview_addr="localhost")
    cfg.validate()  # must not raise


# --- constant-time preview token check (fix #3) ----------------------------


class _StubPreview:
    """Minimal object exposing PreviewServer._authorized bound to a config."""

    def __init__(self, token: str) -> None:
        self.cfg = _base_config(preview_token=token)

    _authorized = main.PreviewServer._authorized


def test_authorized_allows_when_no_token_configured():
    preview = _StubPreview("")
    assert preview._authorized("/v1/state", {}) is True


def test_authorized_accepts_matching_header_token():
    preview = _StubPreview("s3cret-token")
    assert preview._authorized("/v1/state", {"X-Preview-Token": "s3cret-token"}) is True


def test_authorized_accepts_matching_query_token():
    preview = _StubPreview("s3cret-token")
    assert preview._authorized("/v1/state?token=s3cret-token", {}) is True


def test_authorized_rejects_wrong_token():
    preview = _StubPreview("s3cret-token")
    assert preview._authorized("/v1/state", {"X-Preview-Token": "nope"}) is False


def test_authorized_rejects_missing_token():
    preview = _StubPreview("s3cret-token")
    assert preview._authorized("/v1/state", {}) is False


# --- live FFmpeg timeout options (fix #1) ----------------------------------


def _clear_ffmpeg_env(monkeypatch):
    monkeypatch.delenv("OPENCV_FFMPEG_CAPTURE_OPTIONS", raising=False)


def test_configure_live_ffmpeg_options_rtsp(monkeypatch):
    _clear_ffmpeg_env(monkeypatch)
    source = main.VideoSource(
        raw="rtsp://cam/stream",
        capture_source="rtsp://cam/stream",
        kind="rtsp",
        label="rtsp://cam/stream",
        loop=False,
    )
    main.configure_live_ffmpeg_options(source)
    opts = os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"]
    assert "rtsp_transport;tcp" in opts
    assert "stimeout;5000000" in opts


def test_configure_live_ffmpeg_options_non_rtsp(monkeypatch):
    _clear_ffmpeg_env(monkeypatch)
    source = main.VideoSource(
        raw="http://cam/mjpg",
        capture_source="http://cam/mjpg",
        kind="mjpeg",
        label="http://cam/mjpg",
        loop=False,
    )
    main.configure_live_ffmpeg_options(source)
    assert os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] == "timeout;5000000"


def test_configure_live_ffmpeg_options_respects_operator_override(monkeypatch):
    monkeypatch.setenv("OPENCV_FFMPEG_CAPTURE_OPTIONS", "custom;1")
    source = main.VideoSource(
        raw="rtsp://cam/stream",
        capture_source="rtsp://cam/stream",
        kind="rtsp",
        label="rtsp://cam/stream",
        loop=False,
    )
    main.configure_live_ffmpeg_options(source)
    assert os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] == "custom;1"


def test_apply_live_capture_timeouts_guards_missing_constants():
    class FakeCap:
        def __init__(self) -> None:
            self.calls: list[tuple[int, int]] = []

        def set(self, prop, value):
            self.calls.append((prop, value))
            return True

    class FakeCv2:
        CAP_PROP_OPEN_TIMEOUT_MSEC = 53
        # READ constant intentionally absent to simulate an older OpenCV build.

    cap = FakeCap()
    main.apply_live_capture_timeouts(FakeCv2(), cap)
    assert cap.calls == [(53, 5000)]


def test_apply_live_capture_timeouts_swallows_set_errors():
    class FakeCap:
        def set(self, prop, value):
            raise RuntimeError("unsupported")

    class FakeCv2:
        CAP_PROP_OPEN_TIMEOUT_MSEC = 1
        CAP_PROP_READ_TIMEOUT_MSEC = 2

    # Must not raise even when capture.set() fails.
    main.apply_live_capture_timeouts(FakeCv2(), FakeCap())


# --- gateway retry wiring (fix #5) -----------------------------------------


def test_gateway_client_mounts_bounded_retry():
    cfg = _base_config()
    client = main.GatewayClient(cfg)
    adapter = client.session.get_adapter("http://localhost/x")
    retry = adapter.max_retries
    assert retry.total == 2
    assert retry.backoff_factor > 0
    assert 500 in retry.status_forcelist
    assert "POST" in retry.allowed_methods


# --- helpers ----------------------------------------------------------------


def _base_config(**overrides):
    defaults = dict(
        gateway_url="http://localhost:8081/v1/events",
        ingest_api_key="x" * 24,
        room_id=1,
        worker_id="test-worker",
        camera_source="simulation",
        detector="simulation",
        send_interval_seconds=5.0,
        stabilization_window=5,
        confidence_threshold=0.45,
        request_timeout_seconds=5.0,
        yolo_model="yolov8n.pt",
        preview_enabled=True,
        preview_addr="127.0.0.1:8090",
        preview_token="",
        preview_width=960,
        preview_jpeg_quality=82,
        line_config=None,
        sample_video_url=main.DEFAULT_SAMPLE_VIDEO_URL,
        video_loop=True,
        source_reconnect_seconds=3.0,
        source_read_failure_limit=12,
    )
    defaults.update(overrides)
    return main.Config(**defaults)
