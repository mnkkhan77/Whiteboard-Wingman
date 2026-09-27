"""Parser interface shared by the Docling and Unstructured adapters (and test fakes)."""

from __future__ import annotations

from collections.abc import Collection
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

from app.models import Chunk, ParserName


class DocKind(StrEnum):
    PDF = "pdf"
    DOCX = "docx"
    PPTX = "pptx"
    IMAGE = "image"


@dataclass
class ParseOutput:
    chunks: list[Chunk]
    page_count: int | None = None
    ocr_used: bool = False


class DocumentParser(Protocol):
    """A format adapter. The pipeline routes each DocKind to the parsers that declare it in
    `kinds`, trying them in registration order (later ones are fallbacks)."""

    name: ParserName
    kinds: Collection[DocKind]

    def parse(self, path: Path, kind: DocKind, *, ocr: bool) -> ParseOutput:
        """Parse and chunk a document. Raise on failure; return chunks (possibly empty)."""
        ...

    def warmup(self) -> None:
        """Load models ahead of the first document (optional, may be a no-op)."""
        ...
