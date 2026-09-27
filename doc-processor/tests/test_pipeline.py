"""Routing, every contract error code, retries, fallback and idempotency (fake parsers)."""

from __future__ import annotations

import json

import pytest

from app.errors import PipelineError
from app.models import Chunk, DocumentFailedEvent, DocumentParsedEvent, ErrorCode
from app.pipeline import Pipeline
from app.probes import PdfInfo
from tests.conftest import word_count


def _parsed(result: object) -> DocumentParsedEvent:
    assert isinstance(result, DocumentParsedEvent), result
    return result


def _failed(result: object, code: ErrorCode) -> DocumentFailedEvent:
    assert isinstance(result, DocumentFailedEvent), result
    assert result.error_code == code, result.message
    return result


# --- routing -----------------------------------------------------------------------------------


def test_pdf_goes_to_docling(pipeline, make_upload, docling, unstructured, settings) -> None:
    result = _parsed(pipeline.process(make_upload("pdf")))
    assert result.parser == "docling"
    assert result.page_count == 3
    assert result.ocr_used is False
    assert result.chunks_path == "packs/42/chunks.json"
    assert len(docling.calls) == 1 and not unstructured.calls
    assert docling.calls[0][2] is False  # fully text-based PDF: no OCR even if allowed


@pytest.mark.parametrize("ext", ["docx", "pptx"])
def test_office_goes_to_unstructured(pipeline, make_upload, docling, unstructured, ext) -> None:
    result = _parsed(pipeline.process(make_upload(ext, content=b"PK fake zip")))
    assert result.parser == "unstructured"
    assert not docling.calls
    assert unstructured.calls[0][1].value == ext


def test_pptx_page_count_is_slide_count(pipeline, make_upload) -> None:
    assert _parsed(pipeline.process(make_upload("pptx"))).page_count == 5


@pytest.mark.parametrize("ext", ["png", "jpg", "jpeg", "JPG"])
def test_images_use_unstructured_ocr_when_enabled(pipeline, make_upload, unstructured, ext) -> None:
    result = _parsed(pipeline.process(make_upload(ext, content=b"img", ocrEnabled=True)))
    assert result.parser == "unstructured"
    assert result.ocr_used is True
    assert result.page_count == 1
    assert unstructured.calls[0][2] is True


def test_scanned_pdf_with_ocr_enabled_runs_docling_ocr(pipeline, make_upload, docling, pdf_info) -> None:
    pdf_info["info"] = PdfInfo(page_count=2, pages_with_text=0)
    result = _parsed(pipeline.process(make_upload(ocrEnabled=True)))
    assert docling.calls[0][2] is True
    assert result.ocr_used is True


def test_partially_scanned_pdf_uses_ocr_only_if_enabled(pipeline, make_upload, docling, pdf_info) -> None:
    pdf_info["info"] = PdfInfo(page_count=4, pages_with_text=2)
    _parsed(pipeline.process(make_upload(ocrEnabled=False)))
    _parsed(pipeline.process(make_upload(ocrEnabled=True)))
    assert [call[2] for call in docling.calls] == [False, True]


def test_docling_failure_falls_back_to_unstructured(pipeline, make_upload, docling, unstructured, sleeps) -> None:
    docling.errors = [RuntimeError("layout model crashed")]
    result = _parsed(pipeline.process(make_upload()))
    assert result.parser == "unstructured"
    assert len(unstructured.calls) == 1
    assert not sleeps  # fallback, not a retry


def test_docling_empty_output_falls_back_to_unstructured(pipeline, make_upload, docling) -> None:
    docling.chunks = [Chunk(text="   ")]
    assert _parsed(pipeline.process(make_upload())).parser == "unstructured"


def test_stored_extension_wins_over_client_content_type(pipeline, make_upload) -> None:
    event = make_upload("txt", contentType="application/pdf", fileName="notes.pdf")
    _failed(pipeline.process(event), ErrorCode.UNSUPPORTED_FORMAT)


def test_content_type_used_when_no_extension_anywhere(pipeline, make_upload) -> None:
    event = make_upload("pdf")
    src = pipeline.storage.resolve(event.storage_path)
    src.rename(src.with_suffix(""))
    event = event.model_copy(
        update={"storage_path": "packs/42/source", "file_name": "notes", "content_type": "application/pdf"}
    )
    assert _parsed(pipeline.process(event)).parser == "docling"


# --- error codes ---------------------------------------------------------------------------------


@pytest.mark.parametrize("ext", ["txt", "exe", "doc", "xlsx"])
def test_unsupported_format(pipeline, make_upload, docling, unstructured, ext) -> None:
    result = _failed(pipeline.process(make_upload(ext)), ErrorCode.UNSUPPORTED_FORMAT)
    assert f".{ext}" in result.message
    assert not docling.calls and not unstructured.calls


def test_kind_without_registered_parser_is_unsupported(pipeline, make_upload, docling) -> None:
    pdf_only = Pipeline(pipeline.settings, pipeline.storage, [docling], count_tokens=word_count)
    _failed(pdf_only.process(make_upload("docx")), ErrorCode.UNSUPPORTED_FORMAT)
    assert not docling.calls


def test_page_limit_exceeded_for_pdf(pipeline, make_upload, docling, pdf_info) -> None:
    pdf_info["info"] = PdfInfo(page_count=120, pages_with_text=0)
    result = _failed(pipeline.process(make_upload(maxPages=50)), ErrorCode.PAGE_LIMIT_EXCEEDED)
    assert result.message == "Document has 120 pages; your tier allows 50."
    assert not docling.calls


def test_page_limit_exceeded_for_pptx(pipeline, make_upload, slides) -> None:
    slides["count"] = 400
    _failed(pipeline.process(make_upload("pptx", maxPages=300)), ErrorCode.PAGE_LIMIT_EXCEEDED)


def test_ocr_required_for_scanned_pdf_without_ocr(pipeline, make_upload, docling, pdf_info) -> None:
    pdf_info["info"] = PdfInfo(page_count=2, pages_with_text=0)
    _failed(pipeline.process(make_upload(ocrEnabled=False)), ErrorCode.OCR_REQUIRED)
    assert not docling.calls


def test_ocr_required_for_image_without_ocr(pipeline, make_upload, unstructured) -> None:
    _failed(pipeline.process(make_upload("png", content=b"img", ocrEnabled=False)), ErrorCode.OCR_REQUIRED)
    assert not unstructured.calls


def test_file_not_found(pipeline, make_upload) -> None:
    _failed(pipeline.process(make_upload(content=None)), ErrorCode.FILE_NOT_FOUND)


@pytest.mark.parametrize("path", ["../secret.pdf", "packs/../../etc/passwd.pdf", "/etc/passwd.pdf"])
def test_path_traversal_is_rejected(pipeline, make_upload, path) -> None:
    event = make_upload().model_copy(update={"storage_path": path})
    _failed(pipeline.process(event), ErrorCode.FILE_NOT_FOUND)


def test_empty_file(pipeline, make_upload, docling) -> None:
    _failed(pipeline.process(make_upload(content=b"")), ErrorCode.EMPTY_DOCUMENT)
    assert not docling.calls


def test_no_text_extracted(pipeline, make_upload, docling, unstructured) -> None:
    docling.chunks = []
    unstructured.chunks = [Chunk(text=" \n "), Chunk(text="---")]
    _failed(pipeline.process(make_upload()), ErrorCode.EMPTY_DOCUMENT)


def test_transient_errors_are_retried_then_succeed(pipeline, make_upload, unstructured, sleeps) -> None:
    unstructured.errors = [OSError("disk hiccup")]
    _parsed(pipeline.process(make_upload("docx")))
    assert len(unstructured.calls) == 2
    assert sleeps == [0.01]


def test_parse_error_after_retries_exhausted(pipeline, make_upload, unstructured, sleeps, storage) -> None:
    unstructured.errors = [ValueError("boom")] * 3
    result = _failed(pipeline.process(make_upload("docx")), ErrorCode.PARSE_ERROR)
    assert len(unstructured.calls) == 3
    assert sleeps == [0.01, 0.02]  # exponential backoff between attempts
    assert "ValueError" in result.message
    assert not storage.resolve("packs/42/chunks.json").exists()


def test_deterministic_probe_error_is_not_retried(pipeline, make_upload, docling, sleeps) -> None:
    def broken_probe(_path, _max):
        raise PipelineError(ErrorCode.PARSE_ERROR, "PDF cannot be opened")

    pipeline._probe_pdf = broken_probe
    _failed(pipeline.process(make_upload()), ErrorCode.PARSE_ERROR)
    assert not sleeps and not docling.calls


# --- chunks.json + idempotency --------------------------------------------------------------------


def test_chunks_file_matches_contract(pipeline, make_upload, docling, storage) -> None:
    docling.chunks = [
        Chunk(text="  Intro text.  ", page=1, page_end=1, section="Chapter 1"),
        Chunk(text="", page=1),  # dropped
        Chunk(
            text="| a | b |\n|---|---|\n| 1 | 2 |",
            page=2,
            page_end=3,
            section="Chapter 1 > Data",
            element_type="table",
        ),
    ]
    result = _parsed(pipeline.process(make_upload()))
    data = json.loads(storage.resolve(result.chunks_path).read_text(encoding="utf-8"))
    assert result.chunk_count == 2
    assert data == [
        {"index": 0, "text": "Intro text.", "page": 1, "pageEnd": 1, "section": "Chapter 1", "elementType": "text"},
        {
            "index": 1,
            "text": "| a | b |\n|---|---|\n| 1 | 2 |",
            "page": 2,
            "pageEnd": 3,
            "section": "Chapter 1 > Data",
            "elementType": "table",
        },
    ]


def test_oversized_chunks_are_split_to_token_limit(pipeline, make_upload, docling, settings, storage) -> None:
    docling.chunks = [Chunk(text=" ".join(f"word{i}." for i in range(600)), page=1, page_end=1)]
    result = _parsed(pipeline.process(make_upload()))
    data = json.loads(storage.resolve(result.chunks_path).read_text(encoding="utf-8"))
    assert result.chunk_count == len(data) == 3
    assert all(len(c["text"].split()) <= settings.chunk_max_tokens for c in data)
    assert [c["index"] for c in data] == [0, 1, 2]


def test_reprocessing_same_event_is_idempotent(pipeline, make_upload, docling, storage) -> None:
    event = make_upload()
    first = _parsed(pipeline.process(event))
    path = storage.resolve(first.chunks_path)
    first_content = path.read_text(encoding="utf-8")

    path.write_text("stale", encoding="utf-8")  # e.g. left over from an older, different run
    second = _parsed(pipeline.process(event))

    assert second.event_id == first.event_id  # backend can de-duplicate on eventId
    assert second.model_dump(exclude={"occurred_at"}) == first.model_dump(exclude={"occurred_at"})
    assert path.read_text(encoding="utf-8") == first_content
    assert not list(path.parent.glob(".chunks-*"))  # no temp files left behind


def test_failed_event_id_is_derived_from_input(pipeline, make_upload) -> None:
    event = make_upload("txt")
    assert pipeline.process(event).event_id == pipeline.process(event).event_id != event.event_id
