"""PDF parsing with Docling (layout model + TableFormer) and token-aware HybridChunker."""

from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import TYPE_CHECKING, Any

from app.chunking import classify_element_type, load_hf_tokenizer, page_range, section_path
from app.config import Settings
from app.models import Chunk, ElementType, ParserName
from app.parsers.base import DocKind, ParseOutput

if TYPE_CHECKING:
    from docling.document_converter import DocumentConverter
    from docling_core.transforms.chunker.hybrid_chunker import HybridChunker

log = logging.getLogger(__name__)

# docling-core DocItemLabel values -> contract elementType. Anything unmapped is plain "text".
DOCLING_LABELS: dict[str, ElementType] = {
    "table": "table",
    "list_item": "list",
    "title": "title",
    "section_header": "title",
    "caption": "caption",
}


class DoclingParser:
    """PDFs only. Converters (one per OCR mode) and the chunker are built once and reused."""

    name: ParserName = "docling"
    kinds: frozenset[DocKind] = frozenset({DocKind.PDF})

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._converters: dict[bool, DocumentConverter] = {}
        self._chunker: HybridChunker | None = None
        self._lock = threading.Lock()

    def warmup(self) -> None:
        """Build the non-OCR converter and load its models so the first upload isn't slow."""
        converter = self._converter(ocr=False)
        converter.initialize_pipeline(self._input_format())
        self._get_chunker()

    def parse(self, path: Path, kind: DocKind, *, ocr: bool) -> ParseOutput:
        if kind not in self.kinds:
            raise ValueError(f"DoclingParser only handles PDFs, got {kind}")
        from docling.datamodel.base_models import ConversionStatus

        result = self._converter(ocr).convert(path, raises_on_error=True)
        warnings = [str(err.error_message) for err in result.errors]
        if result.status == ConversionStatus.PARTIAL_SUCCESS:
            log.warning("Docling partial success for %s: %s", path.name, warnings)
        elif result.status != ConversionStatus.SUCCESS:
            raise RuntimeError(f"Docling conversion status {result.status}: {warnings}")

        doc = result.document
        chunker = self._get_chunker()
        chunks = [self._to_chunk(raw) for raw in chunker.chunk(dl_doc=doc)]
        return ParseOutput(chunks=chunks, page_count=doc.num_pages() or None, ocr_used=ocr)

    @staticmethod
    def _to_chunk(raw: Any) -> Chunk:
        """Map a docling-core DocChunk to the contract chunk (headings -> section path)."""
        items = raw.meta.doc_items or []
        page, page_end = page_range(prov.page_no for item in items for prov in (item.prov or []))
        element_type = classify_element_type(
            DOCLING_LABELS.get(str(getattr(item.label, "value", item.label)), "text")
            for item in items
        )
        return Chunk(
            text=raw.text,
            page=page,
            page_end=page_end,
            section=section_path(raw.meta.headings or []),
            element_type=element_type,
        )

    @staticmethod
    def _input_format() -> Any:
        from docling.datamodel.base_models import InputFormat

        return InputFormat.PDF

    def _converter(self, ocr: bool) -> DocumentConverter:
        """One converter per OCR mode; building one loads models, so they are cached."""
        with self._lock:
            if ocr not in self._converters:
                self._converters[ocr] = self._build_converter(ocr)
            return self._converters[ocr]

    def _build_converter(self, ocr: bool) -> DocumentConverter:
        from docling.datamodel.pipeline_options import (
            HeadingHierarchyOptions,
            PdfPipelineOptions,
            TableFormerMode,
            TableStructureOptions,
            TesseractCliOcrOptions,
        )
        from docling.document_converter import DocumentConverter, PdfFormatOption

        s = self._settings
        options = PdfPipelineOptions(
            artifacts_path=s.docling_artifacts_path,
            do_table_structure=True,
            # Only the ACCURATE TableFormer weights are baked into the image.
            table_structure_options=TableStructureOptions(mode=TableFormerMode.ACCURATE),
            do_ocr=ocr,
            # Tesseract CLI: the same engine Unstructured uses for images, no extra Python deps.
            ocr_options=TesseractCliOcrOptions(lang=s.ocr_language_list),
            document_timeout=s.docling_timeout_seconds,
            heading_hierarchy_options=HeadingHierarchyOptions(enabled=True),
        )
        return DocumentConverter(
            format_options={self._input_format(): PdfFormatOption(pipeline_options=options)}
        )

    def _get_chunker(self) -> HybridChunker:
        with self._lock:
            if self._chunker is None:
                self._chunker = self._build_chunker()
            return self._chunker

    def _build_chunker(self) -> HybridChunker:
        from docling_core.transforms.chunker.hierarchical_chunker import (
            ChunkingDocSerializer,
            ChunkingSerializerProvider,
        )
        from docling_core.transforms.chunker.hybrid_chunker import HybridChunker
        from docling_core.transforms.chunker.tokenizer.huggingface import HuggingFaceTokenizer
        from docling_core.transforms.serializer.markdown import MarkdownTableSerializer

        class MarkdownTables(ChunkingSerializerProvider):
            """Serialize tables as markdown instead of the default "row, col = value" triplets."""

            def get_serializer(self, doc: Any) -> ChunkingDocSerializer:
                return ChunkingDocSerializer(doc=doc, table_serializer=MarkdownTableSerializer())

        tokenizer = HuggingFaceTokenizer(
            tokenizer=load_hf_tokenizer(self._settings.tokenizer_model),
            max_tokens=self._settings.chunk_max_tokens,
        )
        return HybridChunker(tokenizer=tokenizer, serializer_provider=MarkdownTables(), merge_peers=True)
