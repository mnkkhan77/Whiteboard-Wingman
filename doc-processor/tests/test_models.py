"""Event/chunk (de)serialization must match docs/study-packs-contract.md byte-for-byte in shape."""

from __future__ import annotations

import json
from datetime import UTC, datetime

from app.models import (
    Chunk,
    DocumentFailedEvent,
    DocumentParsedEvent,
    DocumentUploadedEvent,
    ErrorCode,
    derived_event_id,
)

CONTRACT_UPLOADED = """
{
  "eventId": "uuid",
  "packId": 42,
  "ownerId": 7,
  "tier": "FREE",
  "fileName": "notes.pdf",
  "contentType": "application/pdf",
  "storagePath": "packs/42/source.pdf",
  "sizeBytes": 123456,
  "ocrEnabled": false,
  "maxPages": 50,
  "occurredAt": "2026-09-27T10:15:30Z"
}
"""

OCCURRED = datetime(2026, 9, 27, 10, 16, 2, tzinfo=UTC)


def test_uploaded_event_parses_contract_example() -> None:
    event = DocumentUploadedEvent.model_validate_json(CONTRACT_UPLOADED)
    assert event.event_id == "uuid"
    assert event.pack_id == 42
    assert event.owner_id == 7
    assert event.storage_path == "packs/42/source.pdf"
    assert event.ocr_enabled is False
    assert event.max_pages == 50
    assert event.occurred_at == datetime(2026, 9, 27, 10, 15, 30, tzinfo=UTC)


def test_uploaded_event_tolerates_unknown_fields_and_fractional_seconds() -> None:
    raw = json.loads(CONTRACT_UPLOADED) | {"newField": 1, "occurredAt": "2026-09-27T10:15:30.123456Z"}
    event = DocumentUploadedEvent.model_validate(raw)
    assert event.occurred_at.microsecond == 123456


def test_parsed_event_serializes_exactly_like_contract() -> None:
    event = DocumentParsedEvent(
        event_id="uuid",
        pack_id=42,
        page_count=37,
        parser="docling",
        ocr_used=False,
        chunk_count=180,
        chunks_path="packs/42/chunks.json",
        occurred_at=OCCURRED,
    )
    body = json.loads(event.to_json_bytes())
    assert list(body) == [
        "eventId", "packId", "pageCount", "parser", "ocrUsed", "chunkCount", "chunksPath", "occurredAt"
    ]
    assert body == {
        "eventId": "uuid",
        "packId": 42,
        "pageCount": 37,
        "parser": "docling",
        "ocrUsed": False,
        "chunkCount": 180,
        "chunksPath": "packs/42/chunks.json",
        "occurredAt": "2026-09-27T10:16:02Z",
    }


def test_failed_event_serializes_exactly_like_contract() -> None:
    event = DocumentFailedEvent(
        event_id="uuid",
        pack_id=42,
        error_code=ErrorCode.PAGE_LIMIT_EXCEEDED,
        message="Document has 120 pages; your tier allows 50.",
        occurred_at=OCCURRED,
    )
    body = json.loads(event.to_json_bytes())
    assert list(body) == ["eventId", "packId", "errorCode", "message", "occurredAt"]
    assert body == {
        "eventId": "uuid",
        "packId": 42,
        "errorCode": "PAGE_LIMIT_EXCEEDED",
        "message": "Document has 120 pages; your tier allows 50.",
        "occurredAt": "2026-09-27T10:16:02Z",
    }


def test_default_timestamp_is_utc_z_without_fraction() -> None:
    payload = json.loads(
        DocumentFailedEvent(event_id="x", pack_id=1, error_code=ErrorCode.PARSE_ERROR, message="m").to_json_bytes()
    )
    stamp = payload["occurredAt"]
    assert stamp.endswith("Z") and len(stamp) == len("2026-09-27T10:16:02Z")


def test_chunk_serializes_with_contract_keys() -> None:
    chunk = Chunk(index=0, text="t", page=3, page_end=4, section="Chapter 2 > Transactions", element_type="text")
    assert chunk.model_dump(by_alias=True) == {
        "index": 0,
        "text": "t",
        "page": 3,
        "pageEnd": 4,
        "section": "Chapter 2 > Transactions",
        "elementType": "text",
    }


def test_derived_event_ids_are_stable_and_distinct() -> None:
    assert derived_event_id("abc", "parsed") == derived_event_id("abc", "parsed")
    assert derived_event_id("abc", "parsed") != derived_event_id("abc", "failed")
    assert derived_event_id("abc", "parsed") != derived_event_id("abd", "parsed")
