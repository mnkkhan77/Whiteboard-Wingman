from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import get_settings


def test_health_and_ready_without_consumer(monkeypatch) -> None:
    monkeypatch.setenv("CONSUMER_ENABLED", "false")
    get_settings.cache_clear()
    from app.main import app

    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}
        ready = client.get("/ready")
        assert ready.status_code == 503
        assert ready.json()["threadAlive"] is False
    get_settings.cache_clear()
