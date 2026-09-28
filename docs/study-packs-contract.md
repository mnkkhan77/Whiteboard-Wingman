# Study Packs — shared contract

Source of truth for the backend (Spring Boot), the `doc-processor` (Python) service and the
frontend. Change this file first if any of these shapes need to change.

## Flow

```
React --multipart--> backend  POST /api/packs
backend: tier check -> save file -> StudyPack(status=QUEUED) -> publish wingman.document.uploaded
doc-processor: read file -> Docling (PDF) / Unstructured (other formats, fallback)
             -> write chunks JSON to shared storage -> publish wingman.document.parsed
             (or wingman.document.failed)
backend: consume parsed -> status=EMBEDDING -> read chunks file -> enforce chunk cap
       -> embed (local MiniLM) -> pgvector (metadata packId, ownerId, page, ...) -> status=READY
```

## Shared storage

- Backend writes uploads under `app.storage.root` (default `./data/uploads`, i.e.
  `backend/data/uploads` when run from `backend/`).
- doc-processor reads/writes under env `STORAGE_ROOT` (default `/data/uploads`); docker-compose
  mounts `./backend/data/uploads` there.
- All paths in events are **relative** to the storage root, forward slashes:
  - upload: `packs/{packId}/source.{ext}`
  - chunks: `packs/{packId}/chunks.json`

## Kafka

- Bootstrap: host `localhost:9092`; inside compose network `kafka:29092`.
- JSON values (UTF-8), message key = `packId` as a string.
- Timestamps: ISO-8601 UTC strings, e.g. `2026-09-27T10:15:30Z`.

### `wingman.document.uploaded` (backend -> doc-processor)

```json
{
  "eventId": "uuid",
  "packId": 42,
  "ownerId": 7,
  "tier": "FREE",
  "fileName": "notes.pdf",
  "contentType": "application/pdf",
  "storagePath": "packs/42/source.pdf",
  "sizeBytes": 123456,
  "ocrEnabled": false,
  "maxPages": 50,
  "occurredAt": "2026-09-27T10:15:30Z"
}
```

### `wingman.document.parsed` (doc-processor -> backend)

Claim-check pattern: chunks go to a file, not into the Kafka message (default 1MB limit).

```json
{
  "eventId": "uuid",
  "packId": 42,
  "pageCount": 37,
  "parser": "docling",
  "ocrUsed": false,
  "chunkCount": 180,
  "chunksPath": "packs/42/chunks.json",
  "occurredAt": "2026-09-27T10:16:02Z"
}
```

`parser` is `"docling"` or `"unstructured"`.

Chunks file (`chunks.json`) is a JSON array:

```json
[
  {
    "index": 0,
    "text": "chunk text (markdown allowed, tables as markdown)",
    "page": 3,
    "pageEnd": 4,
    "section": "Chapter 2 > Transactions",
    "elementType": "text"
  }
]
```

- `page` / `pageEnd`: 1-based, nullable (formats without pages).
- `section`: heading path, nullable.
- `elementType`: `text` | `table` | `list` | `title` | `caption`.
- Target chunk size: ~800–1200 characters of text, never split mid-table if avoidable.

### `wingman.document.failed` (doc-processor -> backend)

```json
{
  "eventId": "uuid",
  "packId": 42,
  "errorCode": "PAGE_LIMIT_EXCEEDED",
  "message": "Document has 120 pages; your tier allows 50.",
  "occurredAt": "2026-09-27T10:16:02Z"
}
```

`errorCode`: `UNSUPPORTED_FORMAT` | `PAGE_LIMIT_EXCEEDED` | `OCR_REQUIRED` (no text layer and
tier has OCR disabled) | `FILE_NOT_FOUND` | `EMPTY_DOCUMENT` | `PARSE_ERROR`.

Poison messages that can't even be decoded go to `wingman.document.uploaded.DLT`.

## Tiers

`User.tier`: `FREE` (default) | `PRO` | `MAX`. Set by an admin. Guests cannot upload.

| | FREE | PRO | MAX |
|---|---|---|---|
| maxFileBytes | 10 MB | 50 MB | 200 MB |
| maxPages | 50 | 300 | 1000 |
| maxPacks | 3 | 20 | -1 (unlimited) |
| maxChunksPerPack | 300 | 2000 | 6000 |
| ocrEnabled | false | true | true |
| allowedExtensions | pdf | pdf, docx | pdf, docx, pptx, png, jpg, jpeg |

Limits live in backend config (`app.tiers.*`), not hard-coded in the frontend.

## StudyPack status

`QUEUED` -> `EMBEDDING` -> `READY`, or `FAILED` from any step.

## REST API (all under JWT auth, same `{ "message", "code"? }` error JSON as the rest of the app)

| Method | Path | Body / notes | Response |
|---|---|---|---|
| GET | `/api/packs/limits` | | `PackLimitsDto` |
| GET | `/api/packs` | current user's packs, newest first | `PackDto[]` |
| POST | `/api/packs` | multipart: `file` (required), `title` (optional, defaults to file name without extension) | 201 `PackDto` |
| GET | `/api/packs/{id}` | owner only (404 otherwise) | `PackDto` |
| DELETE | `/api/packs/{id}` | owner only; deletes file, chunks and vectors | 204 |
| PUT | `/api/admin/users/{userId}/tier` | `{ "tier": "PRO" }`, ADMIN only | 200 admin user detail (now includes `tier`) |

`PackDto`:

```json
{
  "id": 42, "title": "notes", "fileName": "notes.pdf", "status": "READY",
  "sizeBytes": 123456, "pageCount": 37, "chunkCount": 180, "parser": "docling", "ocrUsed": false,
  "errorCode": null, "errorMessage": null,
  "createdAt": "2026-09-27T10:15:30", "updatedAt": "2026-09-27T10:16:09"
}
```

`PackLimitsDto`:

```json
{
  "tier": "FREE", "maxFileBytes": 10485760, "maxPages": 50, "maxPacks": 3,
  "maxChunksPerPack": 300, "ocrEnabled": false, "allowedExtensions": ["pdf"], "packsUsed": 1
}
```

Upload error codes (4xx body `code`): `GUEST_UPLOAD_NOT_ALLOWED` (403), `PACK_LIMIT_REACHED` (403),
`FILE_TOO_LARGE` (413), `UNSUPPORTED_FORMAT` (400), `EMPTY_FILE` (400).
Pack `errorCode` on `FAILED`: the doc-processor codes above plus `CHUNK_LIMIT_EXCEEDED` and
`EMBEDDING_FAILED` (set by the backend).

## Chat with a pack (Phase 3)

Grounded Q&A over one READY pack: retrieve the pack's closest chunks from pgvector, answer with
the **server-side Groq key** (`GROQ_API_KEY`, never the user's key), cite sources by number.

### Tier quota

Monthly LLM token budget per user (prompt + completion tokens, calendar month, UTC), config
`app.tiers.*.chat-tokens-per-month`:

| | FREE | PRO | MAX |
|---|---|---|---|
| chatTokensPerMonth | 20000 | 500000 | 2000000 |

A request is refused when `used >= limit` before calling the LLM; the actual usage of an answer
is added afterwards (so one answer can overshoot the limit slightly — accepted).

`PackLimitsDto` gains `chatTokensPerMonth` and `chatTokensUsed`.

### REST

| Method | Path | Body / notes | Response |
|---|---|---|---|
| GET | `/api/packs/{id}/chat` | owner only; last 50 messages, oldest first | `ChatHistoryDto` |
| POST | `/api/packs/{id}/chat` | `{ "message": "..." }` (1–2000 chars, trimmed) | `text/event-stream` (below) |
| DELETE | `/api/packs/{id}/chat` | clears this pack's history | 204 |

Refusals **before** streaming starts are normal JSON errors `{ message, code }`:
`PACK_NOT_READY` (409), `CHAT_QUOTA_EXCEEDED` (429), `CHAT_UNAVAILABLE` (503, no server key
configured), validation (400), not found / not owner (404).

### SSE stream (POST response)

Events, in order (`data` is JSON):

1. `sources` — `{ "sources": [ChatSourceDto...] }` — the retrieved chunks, numbered from 1.
   May be empty; then the answer says the document doesn't cover it and no LLM call is made.
2. `delta` — `{ "text": "..." }` — repeated; append in order.
3. `done` — `{ "messageId": 123, "citedSources": [1, 3], "usage": { "promptTokens": 900,
   "completionTokens": 150, "totalTokens": 1050 }, "quota": { "used": 5050, "limit": 20000 } }`
   — `citedSources` = the `[n]` markers actually present in the answer.
4. `error` — `{ "code": "LLM_RATE_LIMITED" | "LLM_ERROR", "message": "..." }` — instead of `done`
   if the LLM fails mid-stream. Partial text already streamed is not persisted.

### DTOs

```json
// ChatSourceDto
{ "n": 1, "page": 3, "pageEnd": 4, "section": "Chapter 2 > Transactions", "snippet": "first ~300 chars" }

// ChatMessageDto
{ "id": 123, "role": "USER" | "ASSISTANT", "content": "...", "sources": [ChatSourceDto...],
  "citedSources": [1, 3], "createdAt": "2026-09-27T10:15:30" }
// sources/citedSources are empty arrays on USER messages.

// ChatHistoryDto
{ "messages": [ChatMessageDto...], "quota": { "used": 5050, "limit": 20000 } }
```

### Answer format

Plain text with light markdown (paragraphs, `-` bullets, `code`). Citations are inline `[n]`
markers referring to `sources[n-1]`. The model is instructed to answer only from the sources and
to say so when they don't contain the answer.
