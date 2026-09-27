from __future__ import annotations

from app.chunking import (
    classify_element_type,
    finalize_chunks,
    html_table_to_markdown,
    page_range,
    split_to_token_limit,
)
from app.models import Chunk
from tests.conftest import word_count


def test_classify_element_type() -> None:
    assert classify_element_type([]) == "text"
    assert classify_element_type(["text", "table"]) == "table"
    assert classify_element_type(["title"]) == "title"
    assert classify_element_type(["title", "list", "list"]) == "list"  # heading introduces a list
    assert classify_element_type(["caption"]) == "caption"
    assert classify_element_type(["text", "list"]) == "text"


def test_html_table_to_markdown() -> None:
    html = (
        "<table><tr><th>Level</th><th>Dirty | reads</th></tr>"
        "<tr><td>Serializable</td><td>No</td></tr><tr><td>x</td></tr></table>"
    )
    assert html_table_to_markdown(html) == (
        "| Level | Dirty \\| reads |\n|---|---|\n| Serializable | No |\n| x |  |"
    )
    assert html_table_to_markdown("<table></table>") is None


def test_small_chunk_untouched() -> None:
    chunk = Chunk(text="short text", page=1)
    assert split_to_token_limit(chunk, word_count, 10) == [chunk]


def test_text_split_prefers_sentence_boundaries_and_keeps_metadata() -> None:
    text = "One two three four. Five six seven eight. Nine ten."
    pieces = split_to_token_limit(Chunk(text=text, page=2, page_end=3, section="S"), word_count, 8)
    assert [p.text for p in pieces] == ["One two three four. Five six seven eight.", "Nine ten."]
    assert all(p.page == 2 and p.page_end == 3 and p.section == "S" for p in pieces)


def test_giant_word_is_hard_split() -> None:
    pieces = split_to_token_limit(Chunk(text="x" * 64), lambda t: len(t) // 8 + 1, 3)
    assert "".join(p.text for p in pieces) == "x" * 64
    assert all(len(p.text) // 8 + 1 <= 3 for p in pieces)


def test_table_split_repeats_header() -> None:
    header = "| col a | col b |\n|---|---|"
    rows = "\n".join(f"| r{i} a | r{i} b |" for i in range(10))
    pieces = split_to_token_limit(Chunk(text=f"{header}\n{rows}", element_type="table"), word_count, 26)
    assert len(pieces) > 1
    for piece in pieces:
        assert piece.text.startswith(header)
        assert word_count(piece.text) <= 26
    body = [line for p in pieces for line in p.text.splitlines()[2:]]
    assert body == rows.splitlines()


def test_finalize_drops_contentless_and_reindexes() -> None:
    chunks = [Chunk(index=9, text="  a  "), Chunk(text=" "), Chunk(text="• —"), Chunk(index=4, text="b")]
    out = finalize_chunks(chunks, word_count, 10)
    assert [(c.index, c.text) for c in out] == [(0, "a"), (1, "b")]


def test_page_range() -> None:
    assert page_range([3, None, 1, 2]) == (1, 3)
    assert page_range([None]) == (None, None)
