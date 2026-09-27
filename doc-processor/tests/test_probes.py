"""Cheap pre-flight probes that need no ML stack (the PDF probe is covered by the real-parser tests)."""

from __future__ import annotations

import zipfile
from pathlib import Path

import pytest

from app.errors import PipelineError
from app.models import ErrorCode
from app.probes import count_slides

_ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="{target}"
    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>
</Relationships>"""

_PRESENTATION = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst>{slides}</p:sldIdLst>
</p:presentation>"""


def _write_pptx(path: Path, slides: int, main_part: str = "ppt/presentation.xml") -> Path:
    slide_ids = "".join(f'<p:sldId id="{256 + i}" r:id="rId{i + 2}"/>' for i in range(slides))
    with zipfile.ZipFile(path, "w") as package:
        package.writestr("_rels/.rels", _ROOT_RELS.format(target=main_part))
        package.writestr(main_part.lstrip("/"), _PRESENTATION.format(slides=slide_ids))
    return path


def test_count_slides_reads_slide_id_list(tmp_path: Path) -> None:
    assert count_slides(_write_pptx(tmp_path / "deck.pptx", 3)) == 3
    assert count_slides(_write_pptx(tmp_path / "empty.pptx", 0)) == 0


def test_count_slides_follows_office_document_relationship(tmp_path: Path) -> None:
    assert count_slides(_write_pptx(tmp_path / "deck.pptx", 2, main_part="/ppt/main.xml")) == 2


@pytest.mark.parametrize("content", [b"PK not really a zip", b""])
def test_count_slides_rejects_corrupt_package(tmp_path: Path, content: bytes) -> None:
    path = tmp_path / "broken.pptx"
    path.write_bytes(content)
    with pytest.raises(PipelineError) as err:
        count_slides(path)
    assert err.value.code is ErrorCode.PARSE_ERROR
