"""Parser-independent chunk helpers and post-processing.

Both parsers produce chunks sized by their own logic; this module enforces the one hard
constraint that matters downstream: every chunk must fit the backend's embedding model
(all-MiniLM-L6-v2 silently truncates after 256 word pieces), measured with that model's
own tokenizer. It also holds the small mapping helpers both parser adapters share
(element-type collapsing, page ranges, section paths, HTML tables to markdown).
"""

from __future__ import annotations

import re
from collections.abc import Callable, Iterable
from functools import lru_cache
from html.parser import HTMLParser
from typing import TYPE_CHECKING, Any

from app.models import Chunk, ElementType

if TYPE_CHECKING:
    from transformers import PreTrainedTokenizerBase

TokenCounter = Callable[[str], int]

SECTION_SEPARATOR = " > "

_PARAGRAPH_RE = re.compile(r"\n\s*\n")
_LINE_RE = re.compile(r"\n")
_SENTENCE_RE = re.compile(r"(?<=[.!?;:])\s+")
_WHITESPACE_RE = re.compile(r"\s+")
_MD_TABLE_SEPARATOR_RE = re.compile(r"^\s*\|?\s*:?-{3,}")


@lru_cache(maxsize=4)
def load_hf_tokenizer(model_name: str) -> PreTrainedTokenizerBase:
    """Load (from the local HF cache when offline) the embedding model's tokenizer."""
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_name)
    # We only count tokens; silence "sequence longer than model max" warnings on big inputs.
    tokenizer.model_max_length = 1_000_000
    return tokenizer


def hf_token_counter(model_name: str) -> TokenCounter:
    """Token counter backed by the (cached) HF tokenizer of the embedding model."""
    tokenizer = load_hf_tokenizer(model_name)
    return lambda text: len(tokenizer.tokenize(text))


def section_path(headings: Iterable[str]) -> str | None:
    """Join a heading hierarchy into the contract's `section` ("Chapter 2 > Transactions")."""
    return SECTION_SEPARATOR.join(h.strip() for h in headings if h and h.strip()) or None


def classify_element_type(types: Iterable[ElementType]) -> ElementType:
    """Collapse the element types inside one chunk to a single contract elementType.

    A heading that merely introduces content does not make the chunk a "title"; any table
    makes it a "table" (so the UI can render markdown); otherwise a uniform type wins.
    """
    kinds = set(types)
    if not kinds:
        return "text"
    if "table" in kinds:
        return "table"
    if len(kinds) > 1:
        kinds.discard("title")
    return kinds.pop() if len(kinds) == 1 else "text"


def html_table_to_markdown(html: str) -> str | None:
    """Convert Unstructured's `text_as_html` table into a GitHub-style markdown table."""
    parser = _TableHTMLParser()
    parser.feed(html)
    rows = [row for row in parser.rows if any(cell for cell in row)]
    if not rows:
        return None
    width = max(len(row) for row in rows)
    rows = [row + [""] * (width - len(row)) for row in rows]
    lines = ["| " + " | ".join(rows[0]) + " |", "|" + "|".join(["---"] * width) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in rows[1:]]
    return "\n".join(lines)


class _TableHTMLParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.rows: list[list[str]] = []
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            self.rows.append([])
        elif tag in ("td", "th"):
            if not self.rows:
                self.rows.append([])
            self._cell = []

    def handle_endtag(self, tag: str) -> None:
        if tag in ("td", "th") and self._cell is not None:
            text = _WHITESPACE_RE.sub(" ", "".join(self._cell)).strip().replace("|", "\\|")
            self.rows[-1].append(text)
            self._cell = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)


def finalize_chunks(
    chunks: Iterable[Chunk], count_tokens: TokenCounter, max_tokens: int
) -> list[Chunk]:
    """Strip, drop content-free chunks, split anything over the token limit, re-index 0..n-1."""
    result: list[Chunk] = []
    for chunk in chunks:
        text = chunk.text.strip()
        if not any(ch.isalnum() for ch in text):
            continue
        for piece in split_to_token_limit(chunk.model_copy(update={"text": text}), count_tokens, max_tokens):
            result.append(piece.model_copy(update={"index": len(result)}))
    return result


def split_to_token_limit(chunk: Chunk, count_tokens: TokenCounter, max_tokens: int) -> list[Chunk]:
    """Split one chunk into pieces of at most `max_tokens`, keeping its metadata on each."""
    if count_tokens(chunk.text) <= max_tokens:
        return [chunk]
    if chunk.element_type == "table":
        texts = _split_markdown_table(chunk.text, count_tokens, max_tokens)
    else:
        texts = _pack(_atoms(chunk.text, count_tokens, max_tokens), " ", count_tokens, max_tokens)
    return [chunk.model_copy(update={"text": text}) for text in texts if text.strip()]


def _atoms(text: str, count_tokens: TokenCounter, limit: int) -> list[str]:
    """Break text into pieces that each fit, preferring the coarsest natural boundary."""
    text = text.strip()
    if not text:
        return []
    if count_tokens(text) <= limit:
        return [text]
    for splitter in (_PARAGRAPH_RE, _LINE_RE, _SENTENCE_RE, _WHITESPACE_RE):
        parts = [part for part in splitter.split(text) if part.strip()]
        if len(parts) > 1:
            return [atom for part in parts for atom in _atoms(part, count_tokens, limit)]
    mid = len(text) // 2  # one giant "word" (e.g. base64): cut it in half until it fits
    return _atoms(text[:mid], count_tokens, limit) + _atoms(text[mid:], count_tokens, limit)


def _pack(
    pieces: list[str],
    joiner: str,
    count_tokens: TokenCounter,
    limit: int,
    prefix: list[str] | None = None,
) -> list[str]:
    """Greedily join pieces into groups that stay under the limit (optionally with a prefix
    repeated on every group, e.g. a table header)."""
    prefix = prefix or []
    prefix_tokens = sum(count_tokens(p) for p in prefix)
    groups: list[str] = []
    current: list[str] = []
    current_tokens = prefix_tokens
    for piece in pieces:
        tokens = count_tokens(piece)
        if current and current_tokens + tokens > limit:
            groups.append(joiner.join(prefix + current))
            current, current_tokens = [], prefix_tokens
        current.append(piece)
        current_tokens += tokens
    if current:
        groups.append(joiner.join(prefix + current))
    return groups


def _split_markdown_table(text: str, count_tokens: TokenCounter, limit: int) -> list[str]:
    """Split a markdown table by rows, repeating the header so every piece stays readable."""
    lines = [line for line in text.splitlines() if line.strip()]
    header: list[str] = []
    if len(lines) >= 2 and _MD_TABLE_SEPARATOR_RE.match(lines[1]):
        header, lines = lines[:2], lines[2:]
    header_tokens = sum(count_tokens(h) for h in header)
    if header_tokens > limit // 2:
        header, header_tokens = [], 0  # absurdly wide header: repeating it leaves no room
    budget = limit - header_tokens
    rows: list[str] = []
    for line in lines:
        rows.extend([line] if count_tokens(line) <= budget else _atoms(line, count_tokens, budget))
    return _pack(rows, "\n", count_tokens, limit, prefix=header)


def page_range(pages: Iterable[Any]) -> tuple[int | None, int | None]:
    """(first, last) 1-based page among `pages`, ignoring missing/non-integer entries."""
    numbers = [p for p in pages if isinstance(p, int) and p > 0]
    return (min(numbers), max(numbers)) if numbers else (None, None)
