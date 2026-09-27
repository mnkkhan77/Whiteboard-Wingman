"""Cheap pre-flight checks run before any heavy parsing (page limits, text layer detection)."""

from __future__ import annotations

import posixpath
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

from app.errors import PipelineError
from app.models import ErrorCode

# A page with fewer visible characters than this is treated as "no text layer" (scans often
# carry a stray page number or watermark in their text layer).
MIN_TEXT_CHARS_PER_PAGE = 10

_REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
_PML_NS = "{http://schemas.openxmlformats.org/presentationml/2006/main}"
_OFFICE_DOCUMENT_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"


@dataclass(frozen=True)
class PdfInfo:
    page_count: int
    pages_with_text: int

    @property
    def has_text_layer(self) -> bool:
        return self.pages_with_text > 0

    @property
    def fully_text_based(self) -> bool:
        return self.pages_with_text == self.page_count


def probe_pdf(path: Path, max_pages: int) -> PdfInfo:
    """Count pages with pdfium (no rendering). The text layer is only inspected when the page
    count is within the limit, so an oversized upload is rejected in milliseconds."""
    import pypdfium2 as pdfium

    try:
        pdf = pdfium.PdfDocument(str(path))
    except pdfium.PdfiumError as exc:
        raise PipelineError(
            ErrorCode.PARSE_ERROR, f"PDF cannot be opened (corrupt or password-protected): {exc}"
        ) from exc
    try:
        page_count = len(pdf)
        if page_count > max_pages:
            return PdfInfo(page_count=page_count, pages_with_text=0)
        with_text = 0
        for i in range(page_count):
            page = pdf[i]
            textpage = page.get_textpage()
            try:
                text = textpage.get_text_bounded()
            finally:
                textpage.close()
                page.close()
            if sum(1 for ch in text if not ch.isspace()) >= MIN_TEXT_CHARS_PER_PAGE:
                with_text += 1
        return PdfInfo(page_count=page_count, pages_with_text=with_text)
    finally:
        pdf.close()


def count_slides(path: Path) -> int:
    """Slide count from the presentation part's slide-id list. Reads two small XML parts from
    the zip instead of loading the whole package (media included) the way python-pptx does."""
    try:
        with zipfile.ZipFile(path) as package:
            rels = ElementTree.fromstring(package.read("_rels/.rels"))
            targets = [
                rel.get("Target", "")
                for rel in rels.iter(f"{_REL_NS}Relationship")
                if rel.get("Type") == _OFFICE_DOCUMENT_REL
            ]
            main_part = posixpath.normpath(targets[0].lstrip("/")) if targets else "ppt/presentation.xml"
            presentation = ElementTree.fromstring(package.read(main_part))
    except (zipfile.BadZipFile, KeyError, ElementTree.ParseError, OSError) as exc:
        raise PipelineError(ErrorCode.PARSE_ERROR, f"PPTX cannot be opened: {exc}") from exc
    return len(presentation.findall(f"{_PML_NS}sldIdLst/{_PML_NS}sldId"))
