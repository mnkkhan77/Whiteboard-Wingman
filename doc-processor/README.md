# doc-processor

Python microservice that turns uploaded study-pack files into embedding-sized chunks.
Shapes of every event and file are defined in [`docs/study-packs-contract.md`](../docs/study-packs-contract.md).

```
wingman.document.uploaded ──► probe (pages, text layer) ──► parse ──► chunk ──► packs/{id}/chunks.json
                                                                        └──► wingman.document.parsed
                             any contract error ───────────────────────────► wingman.document.failed
                             undecodable / invalid message ────────────────► wingman.document.uploaded.DLT
```

## Routing

| Input | Parser | Notes |
|---|---|---|
| PDF | Docling (layout + TableFormer) + `HybridChunker` | page count checked first with pypdfium2; OCR (Tesseract) only if `ocrEnabled` **and** some page lacks a text layer. Docling error / empty output → Unstructured fallback (`fast` strategy, text-layer PDFs only: its PDF OCR path needs poppler, which is not shipped). |
| DOCX, PPTX | Unstructured `partition_docx` / `partition_pptx` + `chunk_by_title` | PPTX slide count is checked against `maxPages`. |
| PNG, JPG, JPEG | Unstructured `partition_image(strategy="ocr_only")` | needs `ocrEnabled`, otherwise `OCR_REQUIRED`. |
| anything else | – | `UNSUPPORTED_FORMAT` |

The format is taken from the stored file's extension (`packs/{id}/source.{ext}`, chosen by the
backend); the client-supplied file name / content type are only used when that has none.

Routing is table-driven: each parser adapter (`app/parsers/`, implementing
`parsers.base.DocumentParser`) declares the `kinds` it handles, and `build_pipeline` registers
them in priority order (Docling, then Unstructured). For a kind with several parsers, a later
one is the fallback when an earlier one raises or extracts no text. Adding a format means a
`DocKind`, its extension/content type in `pipeline.EXTENSION_KINDS` / `CONTENT_TYPE_KINDS`, and
a parser declaring it; tier limits for it (if any) go in `Pipeline._preflight`.

Error codes: `FILE_NOT_FOUND` (missing file or a path escaping `STORAGE_ROOT`), `EMPTY_DOCUMENT`
(0-byte file or no text extracted), `PAGE_LIMIT_EXCEEDED`, `OCR_REQUIRED`, `UNSUPPORTED_FORMAT`,
`PARSE_ERROR` (corrupt/encrypted file, or a parser still failing after `PARSE_MAX_ATTEMPTS`).

## Chunking

The backend embeds with `all-MiniLM-L6-v2`, which truncates after 256 word pieces. Chunks are
therefore sized with **that model's tokenizer** (`CHUNK_MAX_TOKENS=254`, leaving room for
`[CLS]`/`[SEP]`), which is roughly 800–1200 characters of prose.

* Docling: `HybridChunker` with the MiniLM tokenizer; tables serialized as markdown; headings
  → `section` (`"Chapter 2 > Transactions"`); provenance → `page` / `pageEnd`.
* Unstructured: `chunk_by_title` (`CHUNK_MAX_CHARS` / `CHUNK_SOFT_MAX_CHARS` /
  `CHUNK_COMBINE_UNDER_CHARS`); tables converted from `text_as_html` to markdown; heading path
  from `Title` depth. Running headers/footers are dropped.
* Both: a final pass drops content-free chunks and splits anything still over the token limit
  (paragraph → line → sentence → word; tables by row with the header repeated).

`text` is the raw chunk (the section is **not** prepended); `section` is separate so the backend
can decide whether to prefix it before embedding.

## Delivery semantics

At-least-once. Auto-commit is off and an offset is committed only after its result event (or DLT
copy) is acknowledged. If publishing fails 3 times the consumer seeks back and the upload is
re-processed. Re-processing is safe: `chunks.json` is atomically replaced, and output `eventId`s
are UUIDv5 of the input `eventId`, so a duplicate `parsed`/`failed` event carries the same id and
the backend can de-duplicate. Parsing a large PDF can take minutes without polling, hence the high
`KAFKA_MAX_POLL_INTERVAL_MS` default (30 min).

`confluent-kafka` in a background thread was chosen over `aiokafka`: the work is blocking and
CPU-bound (PyTorch, Tesseract), so an event loop adds nothing, while librdkafka gives idempotent
producing and explicit synchronous per-message commits.

## Dependency choices

* `docling-slim` with only `format-pdf,models-local,feat-chunking,convert-core` instead of
  `docling` (whose "standard" set adds RapidOCR, email/HTML/LaTeX backends, CLI…).
* CPU-only PyTorch wheels (the default PyPI wheel bundles CUDA, ~2.5 GB more).
* **One OCR engine: Tesseract** (Debian package, ~30 MB with English data). Docling uses it via
  `TesseractCliOcrOptions` (no Python binding), Unstructured via `unstructured.pytesseract`.
  EasyOCR would add large PyTorch models and RapidOCR another ONNX model set, for a
  study-notes use case where Tesseract's accuracy is sufficient.
* Unstructured with only the `docx,pptx` extras. Its PDF/image partitioners import
  `unstructured-inference` at module import time, so that package is installed `--no-deps`
  (`requirements-nodeps.txt`) with its import-time deps pinned in `requirements.txt`, swapping
  `opencv-python` for `opencv-python-headless` (no libGL in a slim image). `google-cloud-vision`,
  `effdet` and the hi_res layout model are never installed or downloaded.
* The spaCy model Unstructured needs is installed at build time (it would otherwise
  pip-install itself into site-packages at runtime).

### Requirements files

| File | Contents | Used by |
|---|---|---|
| `requirements-core.txt` | FastAPI, uvicorn, pydantic(-settings), confluent-kafka | everything; enough for the unit tests |
| `requirements.txt` | core + CPU PyTorch, Docling, Unstructured and their pinned deps | Docker image |
| `requirements-nodeps.txt` | `unstructured-inference`, installed with `pip install --no-deps` | Docker image |
| `requirements-dev.txt` | core + pytest, httpx, fpdf2, ruff | local venv, Docker `test` stage |

The split exists because the full stack is Linux/3.12-only and multi-GB, while the unit tests
(fake parsers) need only the core. The `--no-deps` file cannot be merged into
`requirements.txt`: pip has no per-package `--no-deps`. The torch/torchvision pins in
`requirements.txt` are also what the Dockerfile installs from the CPU wheel index. All pins are
exact (`==`).

## Configuration (env)

| Variable | Default |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` (compose: `kafka:29092`) |
| `KAFKA_GROUP_ID` | `doc-processor` |
| `KAFKA_TOPIC_UPLOADED` / `_PARSED` / `_FAILED` / `_DLT` | contract topic names |
| `KAFKA_MAX_POLL_INTERVAL_MS` | `1800000` (must exceed the slowest parse, see above) |
| `KAFKA_SESSION_TIMEOUT_MS` | `45000` |
| `PUBLISH_TIMEOUT_SECONDS` | `30` (wait for the broker ack of a result event) |
| `STORAGE_ROOT` | `/data/uploads` |
| `LOG_LEVEL` | `INFO` |
| `CHUNK_MAX_TOKENS` | `254` |
| `CHUNK_MAX_CHARS` / `CHUNK_SOFT_MAX_CHARS` / `CHUNK_COMBINE_UNDER_CHARS` | `1200` / `1000` / `300` |
| `TOKENIZER_MODEL` | `sentence-transformers/all-MiniLM-L6-v2` |
| `OCR_LANGUAGES` | `eng` (comma-separated Tesseract codes; extra `tesseract-ocr-*` packages needed) |
| `PARSE_MAX_ATTEMPTS` / `PARSE_RETRY_BACKOFF_SECONDS` | `3` / `2.0` (exponential) |
| `DOCLING_TIMEOUT_SECONDS` | `900` |
| `DOCLING_ARTIFACTS_PATH` | Docling's cache dir (image: `/opt/docling-models`, baked in) |
| `WARMUP_ON_START` | `true` (load models before consuming) |
| `CONSUMER_ENABLED` | `true` |

## Endpoints

* `GET /health` — liveness (process up). Used by the Docker `HEALTHCHECK`.
* `GET /ready` — 200 when the consumer thread is running, subscribed and a broker is reachable
  (or a document is being parsed); 503 otherwise, with details.

## Running

```bash
docker build -t doc-processor .                       # models are baked in; runs offline
docker run --rm -p 8000:8000 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -v "$PWD/../backend/data/uploads:/data/uploads" doc-processor
```

The container runs as UID 1000 so files it writes into the bind-mounted uploads directory are
owned by the usual host user on Linux; `chunks.json` is written world-readable (0644).

## Tests

```bash
# fast unit tests (fake parsers, no ML deps) — any Python ≥ 3.12
python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt   # or .venv/bin/pip
.venv/Scripts/python -m pytest
.venv/Scripts/python -m ruff check .                                           # lint (ruff.toml)

# everything, including real Docling/Unstructured/Tesseract on generated files
docker build --target test -t doc-processor:test . && docker run --rm doc-processor:test
```
