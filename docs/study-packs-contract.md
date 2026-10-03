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

## Quiz from a pack (Phase 4)

A pack gets an LLM-generated **question bank** (MCQ + conceptual, grounded in its chunks). A quiz
is a normal interview session whose questions come from that bank, so grading, adaptive
difficulty, timed mode, reports, sharing and progress all reuse the existing session flow.

### Modelling

- Pack sessions use a hidden `Topic.STUDY_PACK` (never listed in the topic catalog / pickers)
  plus a nullable `packId` on the session. This keeps every existing `topic` NOT NULL column,
  group-by and DTO working; UIs show the pack title wherever a topic label would appear.
- All LLM work for pack sessions (bank generation, grading CONCEPTUAL answers, report narrative)
  uses the **server Groq key** and is charged to the same monthly `chatTokensPerMonth` quota as
  chat. The `X-LLM-*` headers are ignored for pack sessions.
- Quota is checked when generating a bank and when **starting** a pack session. Once a session has
  started it always finishes: tokens used during it are recorded even if that crosses the limit.

### Question bank

- Generated on demand (not on upload), asynchronously, from chunks spread across the whole pack.
- Target size 24 questions: ~half MCQ (exactly 4 options, one correct, with explanation) and
  ~half CONCEPTUAL (with a reference answer used for grading), mixed EASY / MEDIUM / HARD.
- Each question records its source (`page`, `section`) so feedback can point back to the document.
- Regenerating replaces the bank. Deleting the pack deletes the bank.

`PackDto` gains:

```json
{ "quizStatus": "NONE" | "GENERATING" | "READY" | "FAILED", "quizQuestionCount": 24,
  "quizErrorMessage": null }
```

A bank left `GENERATING` by a backend restart is marked `FAILED` at startup ("interrupted").

### REST

| Method | Path | Body / notes | Response |
|---|---|---|---|
| POST | `/api/packs/{id}/quiz/generate` | owner only; pack must be READY | 202 `PackDto` (quizStatus GENERATING) |
| POST | `/api/sessions` | existing endpoint; new variant `{ "packId": 42, "startingDifficulty": "MEDIUM", "questionCount": 8 }` — `topic`/`topics` omitted | existing `SessionStartResponse` |

Generate errors: `PACK_NOT_READY` (409), `QUIZ_ALREADY_GENERATING` (409), `CHAT_QUOTA_EXCEEDED` (429),
`CHAT_UNAVAILABLE` (503). Pack-session start errors: exactly one of `topic` / `packId` (400),
`PACK_NOT_READY` (409), `QUIZ_NOT_READY` (409), `CHAT_QUOTA_EXCEEDED` (429), `CHAT_UNAVAILABLE` (503),
not found / not owner (404).

Answering / completing a pack session (`POST /api/sessions/{id}/answers`, `/complete`): if the
server-key LLM call fails (after one short retry of a rate limit), the request is refused with
`LLM_RATE_LIMITED` (503) or `LLM_ERROR` (502) and nothing is saved — the client can simply submit
again. (BYO-key topic sessions keep their existing error shapes.)

`questionCount` (default 8, 2–20) is split into CONCEPTUAL = ceil(n/2) then MCQ = floor(n/2),
capped by what the bank has; questions are picked by closest difficulty to the session's current
difficulty and never repeat within a session.

### DTO additions (nullable, only set for pack sessions)

- `QuestionResponse`, `SessionResumeResponse`, `ReportResponse`, `SessionSummaryResponse`,
  `ScorePoint`: `packId`, `packTitle`.
- `topic` is `"STUDY_PACK"` on those; clients display `packTitle` instead of the topic label.
- Deleting a pack keeps its quiz sessions and reports (`topic` stays `"STUDY_PACK"`), but their
  `packId` / `packTitle` become `null` — clients need a fallback label (e.g. "Study pack").
- The public shared report (`GET /api/public/reports/{token}`) of a pack session carries
  `packTitle` only; `packId` is always `null` there.
- Report "practice again" for a pack session links to the pack's quiz page, not a topic.

### Frontend

- READY pack: **Quiz** button → `/packs/:packId/quiz`: bank status; "Generate questions" (notes it
  uses the monthly token budget) → poll until READY/FAILED; then difficulty, question count and
  timed-mode options → start → the existing interview page.
- Everywhere a topic label is shown, prefer `packTitle` when present; `topicLabel` must never
  crash on an unknown/null topic.

## Flashcards from a pack (Phase 5)

A pack gets an LLM-generated **flashcard deck** (front/back pairs, grounded in its chunks), studied
with the **SM-2** spaced-repetition algorithm. Unlike a quiz, studying never calls the LLM — only
deck generation does, so only generation checks the monthly token quota.

### Modelling

- `PackFlashcard`: `front`, `back`, `sourcePage`/`sourceSection`/`sourceChunkIndex`, plus its own
  SM-2 schedule (`easeFactor`, `intervalDays`, `repetitions`, `dueAt`, `lastReviewedAt`). A pack is
  only ever studied by its own owner, so the schedule lives directly on the row — no separate
  per-user review-state table, unlike a quiz's per-session grading.
- Deck generation uses the **server Groq key** and is charged to the same monthly
  `chatTokensPerMonth` quota as chat and quizzes. Reviewing a card is a pure local computation
  (Sm2Scheduler) and never touches the quota.
- Regenerating replaces the whole deck (including every card's SM-2 progress). Deleting the pack
  deletes the deck.

### SM-2 scheduling

Each review answers one of four buttons, mapped to SM-2's 0-5 quality scale: **Again** (0, a lapse:
repetitions and interval reset, due again tomorrow), **Hard** (3), **Good** (4), **Easy** (5) — Hard
and above advance the card: 1 day after the first good review, 6 days after the second, then
`interval * easeFactor` after that. `easeFactor` moves by the standard SM-2 formula, floored at 1.3.
A new card is due immediately (never reviewed).

### Deck generation

- Generated on demand (not on upload), asynchronously, from chunks spread across the whole pack.
- Target size 30 cards, each tied to its source page/section.
- Regenerating replaces the bank. Deleting the pack deletes the bank.

`PackDto` gains:

```json
{ "flashcardStatus": "NONE" | "GENERATING" | "READY" | "FAILED", "flashcardCount": 30,
  "flashcardErrorMessage": null }
```

A deck left `GENERATING` by a backend restart is marked `FAILED` at startup ("interrupted").

### REST

| Method | Path | Body / notes | Response |
|---|---|---|---|
| POST | `/api/packs/{id}/flashcards/generate` | owner only; pack must be READY | 202 `PackDto` (flashcardStatus GENERATING) |
| GET | `/api/packs/{id}/flashcards` | owner only; deck must be READY | `FlashcardDeckDto` |
| POST | `/api/packs/{id}/flashcards/{cardId}/review` | `{ "quality": "AGAIN" \| "HARD" \| "GOOD" \| "EASY" }` | `PackFlashcardDto` (updated schedule) |

Generate errors: `PACK_NOT_READY` (409), `FLASHCARDS_ALREADY_GENERATING` (409),
`CHAT_QUOTA_EXCEEDED` (429), `CHAT_UNAVAILABLE` (503). Deck/review errors: `PACK_NOT_READY` (409),
`FLASHCARDS_NOT_READY` (409), not found / not owner (404).

### DTOs

```json
// PackFlashcardDto
{ "id": 1, "front": "What is MVCC?", "back": "Multi-version concurrency control: ...",
  "sourcePage": 12, "sourceSection": "Chapter 3 > Concurrency",
  "easeFactor": 2.5, "intervalDays": 6, "repetitions": 2,
  "dueAt": "2026-10-09T10:15:30", "lastReviewedAt": "2026-10-03T10:15:30" }

// FlashcardDeckDto
{ "cards": [PackFlashcardDto...], "dueCount": 12 }
```

### Frontend

- READY pack: **Flashcards** button → `/packs/:packId/flashcards`: deck status; "Generate
  flashcards" (notes it uses the monthly token budget) → poll until READY/FAILED; then a study
  session over the due cards (flip, then Again/Hard/Good/Easy) with a "browse all cards" view
  alongside it.
