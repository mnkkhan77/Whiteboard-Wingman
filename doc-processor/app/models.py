"""Event and chunk models. JSON shapes follow docs/study-packs-contract.md exactly (camelCase)."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_serializer
from pydantic.alias_generators import to_camel

ParserName = Literal["docling", "unstructured"]
ElementType = Literal["text", "table", "list", "title", "caption"]

# Output event ids are derived from the input event id, so re-processing the same upload
# (at-least-once delivery) produces the same eventId and the backend can de-duplicate.
_EVENT_NAMESPACE = uuid.UUID("5b0f4f8e-2c1d-4a3e-9d8b-6f1e0c7a9b21")


class ErrorCode(StrEnum):
    UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    PAGE_LIMIT_EXCEEDED = "PAGE_LIMIT_EXCEEDED"
    OCR_REQUIRED = "OCR_REQUIRED"
    FILE_NOT_FOUND = "FILE_NOT_FOUND"
    EMPTY_DOCUMENT = "EMPTY_DOCUMENT"
    PARSE_ERROR = "PARSE_ERROR"


def utc_now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


def derived_event_id(source_event_id: str, kind: str) -> str:
    return str(uuid.uuid5(_EVENT_NAMESPACE, f"{source_event_id}:{kind}"))


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",  # tolerate new producer fields (forward compatible)
    )

    def to_json_bytes(self) -> bytes:
        return self.model_dump_json(by_alias=True).encode("utf-8")


class _TimestampedEvent(CamelModel):
    """Each subclass declares `occurred_at` last so JSON key order matches the contract."""

    @field_serializer("occurred_at", check_fields=False)
    def _ser_occurred_at(self, value: datetime) -> str:
        """Always emit `2026-09-27T10:15:30Z` (UTC, seconds precision) as the contract says."""
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class DocumentUploadedEvent(_TimestampedEvent):
    """`wingman.document.uploaded` (backend -> doc-processor)."""

    event_id: str = Field(min_length=1)
    pack_id: int
    owner_id: int
    tier: str
    file_name: str
    content_type: str | None = None
    storage_path: str = Field(min_length=1)
    size_bytes: int | None = None
    ocr_enabled: bool = False
    max_pages: int = Field(gt=0)
    occurred_at: datetime = Field(default_factory=utc_now)


class DocumentParsedEvent(_TimestampedEvent):
    """`wingman.document.parsed` (doc-processor -> backend). Chunks travel via chunks.json."""

    event_id: str
    pack_id: int
    page_count: int | None
    parser: ParserName
    ocr_used: bool
    chunk_count: int
    chunks_path: str
    occurred_at: datetime = Field(default_factory=utc_now)


class DocumentFailedEvent(_TimestampedEvent):
    """`wingman.document.failed` (doc-processor -> backend)."""

    event_id: str
    pack_id: int
    error_code: ErrorCode
    message: str
    occurred_at: datetime = Field(default_factory=utc_now)


class Chunk(CamelModel):
    """One element of the chunks.json array."""

    index: int = 0
    text: str
    page: int | None = None
    page_end: int | None = None
    section: str | None = None
    element_type: ElementType = "text"
