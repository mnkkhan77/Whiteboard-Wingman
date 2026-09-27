"""route -> probe -> parse -> chunk -> write chunks.json -> result event.

The pipeline never raises: every outcome becomes either a parsed or a failed event, so the
consumer's only job is to publish the result and commit.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Sequence
from pathlib import Path, PurePosixPath
from typing import TypeVar

from app import probes
from app.chunking import TokenCounter, finalize_chunks, hf_token_counter
from app.config import Settings
from app.errors import PipelineError
from app.models import (
    DocumentFailedEvent,
    DocumentParsedEvent,
    DocumentUploadedEvent,
    ErrorCode,
    ParserName,
    derived_event_id,
)
from app.parsers.base import DocKind, DocumentParser, ParseOutput
from app.storage import Storage, to_posix

log = logging.getLogger(__name__)
T = TypeVar("T")

EXTENSION_KINDS: dict[str, DocKind] = {
    ".pdf": DocKind.PDF,
    ".docx": DocKind.DOCX,
    ".pptx": DocKind.PPTX,
    ".png": DocKind.IMAGE,
    ".jpg": DocKind.IMAGE,
    ".jpeg": DocKind.IMAGE,
}
CONTENT_TYPE_KINDS: dict[str, DocKind] = {
    "application/pdf": DocKind.PDF,
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": DocKind.DOCX,
    "application/vnd.openxmlformats-officedocument.presentationml.presentation": DocKind.PPTX,
    "image/png": DocKind.IMAGE,
    "image/jpeg": DocKind.IMAGE,
}
SUPPORTED_EXTENSIONS = ", ".join(ext.lstrip(".") for ext in EXTENSION_KINDS)

ResultEvent = DocumentParsedEvent | DocumentFailedEvent


def detect_kind(event: DocumentUploadedEvent) -> tuple[DocKind | None, str]:
    """Route by the extension the backend chose for the stored file; the client-supplied
    file name / content type are only consulted when that path has no extension."""
    for name in (event.storage_path, event.file_name):
        suffix = PurePosixPath(to_posix(name)).suffix.lower()
        if suffix:
            return EXTENSION_KINDS.get(suffix), suffix
    content_type = (event.content_type or "").split(";")[0].strip().lower()
    return CONTENT_TYPE_KINDS.get(content_type), content_type or "unknown"


class Pipeline:
    def __init__(
        self,
        settings: Settings,
        storage: Storage,
        parsers: Sequence[DocumentParser],
        *,
        count_tokens: TokenCounter,
        probe_pdf: Callable[[Path, int], probes.PdfInfo] = probes.probe_pdf,
        count_slides: Callable[[Path], int] = probes.count_slides,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        """`parsers` in priority order: each DocKind is routed to the parsers declaring it,
        and a later parser is the fallback when an earlier one fails or extracts no text."""
        self.settings = settings
        self.storage = storage
        self.parsers = list(parsers)
        self.routes: dict[DocKind, list[DocumentParser]] = {}
        for parser in self.parsers:
            for kind in parser.kinds:
                self.routes.setdefault(kind, []).append(parser)
        self._count_tokens = count_tokens
        self._probe_pdf = probe_pdf
        self._count_slides = count_slides
        self._sleep = sleep

    def warmup(self) -> None:
        for parser in self.parsers:
            parser.warmup()

    def process(self, event: DocumentUploadedEvent) -> ResultEvent:
        """Run one upload end to end. Never raises: failures become a failed event."""
        started = time.monotonic()
        result: ResultEvent
        try:
            result = self._process(event)
        except PipelineError as exc:
            result = self._failed(event, exc.code, exc.message)
        except Exception as exc:  # retries exhausted, or a bug: still report, never crash
            log.exception("pack %s: parsing failed", event.pack_id)
            result = self._failed(
                event, ErrorCode.PARSE_ERROR, f"Document could not be parsed ({type(exc).__name__})."
            )
        elapsed = time.monotonic() - started
        if isinstance(result, DocumentFailedEvent):
            log.info(
                "pack %s: failed with %s in %.1fs: %s", event.pack_id, result.error_code, elapsed, result.message
            )
        else:
            log.info(
                "pack %s: %d chunks via %s in %.1fs", event.pack_id, result.chunk_count, result.parser, elapsed
            )
        return result

    # --- steps -----------------------------------------------------------------------------

    def _process(self, event: DocumentUploadedEvent) -> DocumentParsedEvent:
        path = self.storage.require_file(event.storage_path)
        kind, label = detect_kind(event)
        if kind is None or kind not in self.routes:  # no parser registered for it
            raise PipelineError(
                ErrorCode.UNSUPPORTED_FORMAT,
                f"Unsupported file type '{label}'. Supported: {SUPPORTED_EXTENSIONS}.",
            )
        if path.stat().st_size == 0:
            raise PipelineError(ErrorCode.EMPTY_DOCUMENT, "The uploaded file is empty.")

        ocr, probed_pages = self._preflight(path, kind, event)
        parser_name, output = self._with_retries(lambda: self._parse(path, kind, ocr), event.pack_id)

        chunks = finalize_chunks(output.chunks, self._count_tokens, self.settings.chunk_max_tokens)
        if not chunks:
            raise PipelineError(ErrorCode.EMPTY_DOCUMENT, "No text could be extracted from the document.")
        chunks_path = self._with_retries(
            lambda: self.storage.write_chunks(event.pack_id, chunks), event.pack_id
        )
        return DocumentParsedEvent(
            event_id=derived_event_id(event.event_id, "parsed"),
            pack_id=event.pack_id,
            page_count=probed_pages if probed_pages is not None else output.page_count,
            parser=parser_name,
            ocr_used=output.ocr_used,
            chunk_count=len(chunks),
            chunks_path=chunks_path,
        )

    def _preflight(self, path: Path, kind: DocKind, event: DocumentUploadedEvent) -> tuple[bool, int | None]:
        """Enforce tier limits cheaply and decide whether OCR runs. Returns (ocr, page count)."""
        if kind is DocKind.PDF:
            info = self._probe_pdf(path, event.max_pages)
            _check_page_limit(info.page_count, event.max_pages, "Document", "pages")
            if not info.has_text_layer and not event.ocr_enabled:
                raise PipelineError(
                    ErrorCode.OCR_REQUIRED,
                    "Document has no text layer (it looks scanned) and OCR is not available on your tier.",
                )
            # OCR is slow: only run it when allowed AND some page actually lacks a text layer.
            return event.ocr_enabled and not info.fully_text_based, info.page_count
        if kind is DocKind.PPTX:
            slides = self._count_slides(path)
            _check_page_limit(slides, event.max_pages, "Presentation", "slides")
            return False, slides
        if kind is DocKind.IMAGE:
            if not event.ocr_enabled:
                raise PipelineError(
                    ErrorCode.OCR_REQUIRED, "Images need OCR, which is not available on your tier."
                )
            return True, 1
        return False, None  # DOCX: no cheap, reliable page count (pagination is render-time)

    def _parse(self, path: Path, kind: DocKind, ocr: bool) -> tuple[ParserName, ParseOutput]:
        """Try the parsers routed to `kind` in order, falling through on an error or empty
        output. The last parser's result (or error) is final."""
        *primaries, last = self.routes[kind]
        previous_error: Exception | None = None
        for parser in primaries:
            try:
                output = parser.parse(path, kind, ocr=ocr)
            except PipelineError:
                raise
            except Exception as exc:
                previous_error = exc
                log.warning("%s failed on %s; falling back", parser.name, path.name, exc_info=True)
                continue
            if any(chunk.text.strip() for chunk in output.chunks):
                return parser.name, output
            log.warning("%s returned no text for %s; falling back", parser.name, path.name)
        try:
            return last.name, last.parse(path, kind, ocr=ocr)
        except Exception as exc:
            if previous_error is None:
                raise
            raise exc from previous_error  # keep the primary parser's error for diagnosis

    def _with_retries(self, fn: Callable[[], T], pack_id: int) -> T:
        """Retry unexpected (possibly transient: I/O, memory pressure) errors with backoff.
        PipelineErrors are deterministic and propagate immediately."""
        attempts = self.settings.parse_max_attempts
        for attempt in range(1, attempts + 1):
            try:
                return fn()
            except PipelineError:
                raise
            except Exception as exc:
                if attempt == attempts:
                    raise
                delay = self.settings.parse_retry_backoff_seconds * 2 ** (attempt - 1)
                log.warning(
                    "pack %s: attempt %d/%d failed (%s: %s); retrying in %.1fs",
                    pack_id, attempt, attempts, type(exc).__name__, exc, delay,
                )
                self._sleep(delay)
        raise AssertionError("unreachable")

    @staticmethod
    def _failed(event: DocumentUploadedEvent, code: ErrorCode, message: str) -> DocumentFailedEvent:
        return DocumentFailedEvent(
            event_id=derived_event_id(event.event_id, "failed"),
            pack_id=event.pack_id,
            error_code=code,
            message=message,
        )


def _check_page_limit(count: int, max_pages: int, what: str, unit: str) -> None:
    if count > max_pages:
        raise PipelineError(
            ErrorCode.PAGE_LIMIT_EXCEEDED, f"{what} has {count} {unit}; your tier allows {max_pages}."
        )


def build_pipeline(settings: Settings) -> Pipeline:
    """Wire the real parsers. Imports are local so tests never pull in Docling/Unstructured.

    Order matters: Docling handles PDFs first; Unstructured is the PDF fallback and the only
    parser for everything else. Adding a format = a parser declaring it in `kinds`, plus its
    extension / content type in EXTENSION_KINDS / CONTENT_TYPE_KINDS."""
    from app.parsers.docling_parser import DoclingParser
    from app.parsers.unstructured_parser import UnstructuredParser

    return Pipeline(
        settings,
        Storage(settings.storage_root),
        [DoclingParser(settings), UnstructuredParser(settings)],
        count_tokens=hf_token_counter(settings.tokenizer_model),
    )
