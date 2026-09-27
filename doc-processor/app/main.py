"""FastAPI entrypoint: health endpoints plus the Kafka worker started in the lifespan."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.consumer import DocumentWorker
from app.pipeline import build_pipeline


def _configure_logging(level: str) -> None:
    logging.basicConfig(
        level=level.upper(),
        format="%(asctime)s %(levelname)s [%(threadName)s] %(name)s: %(message)s",
    )
    # Third-party libraries are chatty at INFO (per-page progress, HTTP calls).
    for noisy in ("docling", "docling_core", "httpx", "urllib3", "unstructured", "transformers"):
        logging.getLogger(noisy).setLevel(max(logging.WARNING, logging.getLogger().level))


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    _configure_logging(settings.log_level)
    worker = DocumentWorker(settings, pipeline_factory=lambda: build_pipeline(settings))
    app.state.worker = worker
    if settings.consumer_enabled:
        worker.start()
    yield
    await asyncio.to_thread(worker.stop, 30.0)


app = FastAPI(title="Whiteboard Wingman doc-processor", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness: the process is up and serving requests."""
    return {"status": "UP"}


@app.get("/ready")
def ready(request: Request) -> JSONResponse:
    """Readiness: the Kafka consumer thread is running, subscribed and can reach a broker."""
    worker: DocumentWorker = request.app.state.worker
    status = worker.status()
    return JSONResponse(status, status_code=200 if status["ready"] else 503)
