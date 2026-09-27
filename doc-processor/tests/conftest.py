"""Shared fixtures: settings on a temp storage root and fake parsers (no heavy deps needed)."""

from __future__ import annotations

from collections.abc import Callable, Collection
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest

from app.config import Settings
from app.models import Chunk, DocumentUploadedEvent, ParserName
from app.parsers.base import DocKind, ParseOutput
from app.pipeline import Pipeline
from app.probes import PdfInfo
from app.storage import Storage


def word_count(text: str) -> int:
    return len(text.split())


@dataclass
class FakeParser:
    """Records calls; returns canned chunks or raises the queued exceptions first."""

    name: ParserName
    kinds: Collection[DocKind] = frozenset(DocKind)
    chunks: list[Chunk] = field(default_factory=lambda: [Chunk(text="Some parsed text.", page=1, page_end=1)])
    errors: list[Exception] = field(default_factory=list)
    page_count: int | None = None
    calls: list[tuple[Path, DocKind, bool]] = field(default_factory=list)

    def parse(self, path: Path, kind: DocKind, *, ocr: bool) -> ParseOutput:
        self.calls.append((path, kind, ocr))
        if self.errors:
            raise self.errors.pop(0)
        return ParseOutput(chunks=list(self.chunks), page_count=self.page_count, ocr_used=ocr)

    def warmup(self) -> None:
        pass


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(storage_root=tmp_path, parse_max_attempts=3, parse_retry_backoff_seconds=0.01, _env_file=None)


@pytest.fixture
def storage(settings: Settings) -> Storage:
    return Storage(settings.storage_root)


def upload_event(pack_id: int = 42, ext: str = "pdf", **overrides: Any) -> DocumentUploadedEvent:
    """A contract-shaped uploaded event for packs/{pack_id}/source.{ext} (camelCase overrides)."""
    fields: dict[str, Any] = {
        "eventId": "0b7f6a6e-3f0e-4d7e-9c43-1f7f5d1f3a10",
        "packId": pack_id,
        "ownerId": 7,
        "tier": "FREE",
        "fileName": f"notes.{ext}",
        "contentType": "application/octet-stream",
        "storagePath": f"packs/{pack_id}/source.{ext}",
        "ocrEnabled": False,
        "maxPages": 50,
        "occurredAt": "2026-09-27T10:15:30Z",
    }
    return DocumentUploadedEvent.model_validate(fields | overrides)


@pytest.fixture
def docling() -> FakeParser:
    return FakeParser(name="docling", kinds={DocKind.PDF})


@pytest.fixture
def unstructured() -> FakeParser:
    return FakeParser(name="unstructured")


@pytest.fixture
def pdf_info() -> dict[str, PdfInfo]:
    """Mutable holder so a test can change what the fake PDF probe reports."""
    return {"info": PdfInfo(page_count=3, pages_with_text=3)}


@pytest.fixture
def slides() -> dict[str, int]:
    return {"count": 5}


@pytest.fixture
def sleeps() -> list[float]:
    return []


@pytest.fixture
def pipeline(
    settings: Settings,
    storage: Storage,
    docling: FakeParser,
    unstructured: FakeParser,
    pdf_info: dict[str, PdfInfo],
    slides: dict[str, int],
    sleeps: list[float],
) -> Pipeline:
    return Pipeline(
        settings,
        storage,
        [docling, unstructured],
        count_tokens=word_count,
        probe_pdf=lambda _path, _max: pdf_info["info"],
        count_slides=lambda _path: slides["count"],
        sleep=sleeps.append,
    )


@pytest.fixture
def make_upload(settings: Settings) -> Callable[..., DocumentUploadedEvent]:
    """Create the source file under packs/{packId}/ and return a matching uploaded event."""

    def factory(
        ext: str = "pdf",
        content: bytes | None = b"%PDF-1.7 fake",
        pack_id: int = 42,
        **overrides: Any,
    ) -> DocumentUploadedEvent:
        event = upload_event(pack_id, ext, sizeBytes=len(content or b""), **overrides)
        if content is not None:
            target = settings.storage_root / event.storage_path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        return event

    return factory
