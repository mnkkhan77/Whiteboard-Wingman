"""DOCX / PPTX / image (OCR) parsing with Unstructured, plus the PDF fallback when Docling fails."""

from __future__ import annotations

import logging
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from app.chunking import classify_element_type, html_table_to_markdown, page_range, section_path
from app.config import Settings
from app.models import Chunk, ElementType, ParserName
from app.parsers.base import DocKind, ParseOutput

log = logging.getLogger(__name__)

# Unstructured element categories -> contract elementType. Anything unmapped is plain "text".
UNSTRUCTURED_CATEGORIES: dict[str, ElementType] = {
    "Table": "table",
    "TableChunk": "table",
    "ListItem": "list",
    "Title": "title",
    "FigureCaption": "caption",
}
# Running headers/footers repeat on every page and only add noise to retrieval.
_DROPPED_CATEGORIES = {"Header", "Footer", "PageBreak", "PageNumber"}


class UnstructuredParser:
    """DOCX, PPTX and images; also registered for PDFs as the fallback behind Docling."""

    name: ParserName = "unstructured"
    kinds: frozenset[DocKind] = frozenset(DocKind)

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def warmup(self) -> None:
        """Nothing to preload: the partitioners used here have no ML models."""

    def parse(self, path: Path, kind: DocKind, *, ocr: bool) -> ParseOutput:
        elements = [e for e in self._partition(path, kind, ocr) if e.category not in _DROPPED_CATEGORIES]
        sections = _section_paths(elements)
        chunks = [self._to_chunk(c, sections) for c in self._chunk(elements)]
        pages = [e.metadata.page_number for e in elements]
        page_count = max((p for p in pages if isinstance(p, int)), default=None)
        if kind is DocKind.IMAGE:
            page_count = 1
        return ParseOutput(chunks=chunks, page_count=page_count, ocr_used=ocr)

    def _partition(self, path: Path, kind: DocKind, ocr: bool) -> list[Any]:
        languages = self._settings.ocr_language_list
        filename = str(path)
        if kind is DocKind.DOCX:
            from unstructured.partition.docx import partition_docx

            return partition_docx(filename=filename, infer_table_structure=True)
        if kind is DocKind.PPTX:
            from unstructured.partition.pptx import partition_pptx

            return partition_pptx(filename=filename, infer_table_structure=True)
        if kind is DocKind.IMAGE:
            from unstructured.partition.image import partition_image

            # "ocr_only" = plain Tesseract; skips the hi_res layout model (and its download).
            return partition_image(filename=filename, strategy="ocr_only", languages=languages)
        if kind is DocKind.PDF:
            if ocr:
                # Unstructured's PDF OCR renders pages through poppler, which we deliberately
                # don't ship; OCR for PDFs is Docling's job.
                raise RuntimeError("Unstructured PDF fallback only supports PDFs with a text layer")
            from unstructured.partition.pdf import partition_pdf

            return partition_pdf(filename=filename, strategy="fast", languages=languages)
        raise ValueError(f"Unsupported kind for Unstructured: {kind}")

    def _chunk(self, elements: Sequence[Any]) -> list[Any]:
        from unstructured.chunking.title import chunk_by_title

        s = self._settings
        return chunk_by_title(
            list(elements),
            max_characters=s.chunk_max_chars,
            new_after_n_chars=s.chunk_soft_max_chars,
            combine_text_under_n_chars=s.chunk_combine_under_chars,
            multipage_sections=True,
            include_orig_elements=True,
        )

    @staticmethod
    def _to_chunk(chunk: Any, sections: dict[str, str]) -> Chunk:
        originals = list(chunk.metadata.orig_elements or [chunk])
        element_type = classify_element_type(
            UNSTRUCTURED_CATEGORIES.get(o.category, "text") for o in originals
        )
        if UNSTRUCTURED_CATEGORIES.get(chunk.category) == "table":
            element_type = "table"
        text = chunk.text
        html = getattr(chunk.metadata, "text_as_html", None)
        if element_type == "table" and html:
            text = html_table_to_markdown(html) or text
        page, page_end = page_range(o.metadata.page_number for o in originals)
        section = next((sections[o.id] for o in originals if o.id in sections), None)
        return Chunk(text=text, page=page, page_end=page_end, section=section, element_type=element_type)


def _section_paths(elements: Sequence[Any]) -> dict[str, str]:
    """Map element id -> "H1 > H2" heading path, using Title depth when the format provides it
    (DOCX heading levels); formats without depth get a flat, most-recent-title section."""
    path: list[str] = []
    sections: dict[str, str] = {}
    for element in elements:
        if element.category == "Title" and element.text.strip():
            depth = getattr(element.metadata, "category_depth", None) or 0
            path = [*path[:depth], element.text.strip()]
        if section := section_path(path):
            sections[element.id] = section
    return sections
