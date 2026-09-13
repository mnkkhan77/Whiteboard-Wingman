# AI-Powered Mock Interview Simulator — Implementation Plan

## Context

A "job application tracker" idea was ruled out for being achievable in a spreadsheet — this project needed real backend logic and state, not just CRUD. Instead: an **AI-powered mock interview simulator** that runs live, adaptive technical interviews — asking a question, evaluating the candidate's answer with an LLM, adjusting difficulty based on performance, and producing a final report.

It reuses and extends existing project patterns:
- Stack: React/TS + Spring Boot + MySQL, matching the `job-portal` project (JWT auth pattern reused, extended with a simple USER/ADMIN role distinction so an admin can see who's using the tool and their usage stats — lighter than job-portal's three-role User/Recruiter/Admin model).
- The "bring your own LLM API key, never stored server-side" privacy pattern from `fitforge`'s optional AI coach, adapted for a web app.
- Question content sourced from `java-backend-interview-handbook`, `DSA-notes-java`, and `code-tracker`'s tracked DSA problems (titles/topics/difficulty/tags), via RAG instead of a hardcoded question bank.

Spring AI is the chosen AI integration layer. This is a from-scratch project (empty directory, no existing code).

**Spring AI version note:** Spring AI's APIs (class names for `ChatMemory`, `Advisor`, `VectorStore` builders, etc.) have shifted across milestone/RC releases. Anywhere below marked **[VERIFY]** means: confirm the exact method/class name against the Spring AI version pinned in `pom.xml` at implementation time — the design intent is solid, only the exact signature may need adjusting.

---

## 1. Architecture

```
React+TS frontend  →  Spring Boot API  →  MySQL (sessions/questions/answers/evaluations/reports)
   (JWT + user's                      →  VectorStore (Pinecone, RAG over ingested content)
    own LLM key,                      →  LLM provider (Groq/OpenAI/Anthropic) using the caller's own key
    sessionStorage)
```

**Core loop ("submit answer → get next question"):**
1. Frontend `POST /sessions/{id}/answers` with `{answerText, code?}` + header `X-LLM-Api-Key`.
2. Service loads session state (current question/topic/difficulty; `ChatMemory` conversationId = sessionId).
3. Builds an evaluation prompt (rubric + question + answer), calls `ChatClient.entity(EvaluationResult.class)` using a `ChatClient` built per-request from the caller's own key (never a shared server key).
4. Persists `Evaluation`, computes next difficulty (`AdaptiveDifficultyService`).
5. `QuestionSelectionService` runs a RAG query against `VectorStore` (filtered by topic + difficulty) to pick the next question, optionally rephrasing it via `.entity(GeneratedQuestion.class)`.
6. Returns `{evaluation, nextQuestion, progress}`.
7. After N questions, `POST /sessions/{id}/complete` → `ReportService` aggregates all evaluations into a `Report`.

---

## 2. Data Model (MySQL, Spring Data JPA)

- **User** — id, email, passwordHash, displayName, role (enum: USER, ADMIN — default USER), createdAt, lastActiveAt (updated on login/session start, powers the admin "who's using this" view)
- **InterviewSession** — id, user_id, topic (enum), startingDifficulty, currentDifficulty (mutable), status (IN_PROGRESS/COMPLETED/ABANDONED), targetQuestionCount, timestamps
- **Question** — id, session_id, sequenceNumber, topic, difficulty, promptText, sourceChunkId (traceability to the vector store doc), questionType (CONCEPTUAL/CODING)
- **Answer** — id, question_id (1:1), answerText, codeSubmission, submittedAt
- **Evaluation** — id, answer_id (1:1), score (0-100), correctness enum, feedback, strengths/weaknesses (JSON array), recommendedNextDifficulty, createdAt
- **Report** — id, session_id (1:1), overallScore, strongTopics/weakTopics (JSON), summaryText (LLM-generated), questionCount, averageDifficultyReached

MySQL is the durable system of record for all of the above. `ChatMemory` (keyed by `conversationId = sessionId`) only holds the rolling LLM conversation context for continuity within a session — cap it to a message window (e.g. last 6-10 turns); it's fine if it's lost on restart since MySQL already has everything needed to reconstruct state/reports.

---

## 3. Spring AI Components

- **Dependencies:** `spring-ai-openai-spring-boot-starter` (covers both OpenAI and Groq, since Groq exposes an OpenAI-compatible API — override `base-url` per request), optionally `spring-ai-anthropic-spring-boot-starter`, plus `spring-ai-transformers-spring-boot-starter` for local embeddings, plus `spring-ai-pinecone-store-spring-boot-starter` (**[VERIFY]** exact artifact id for the pinned Spring AI version) for the vector store.
- **BYO-key mechanism:** since each user may bring a different provider/key, don't rely on one global auto-configured `ChatClient`. Build a `PerRequestChatClientFactory` that constructs an `OpenAiChatModel`/`ChatClient` per request from the caller's key + base URL. Keep one default server-side `ChatClient` only for embedding-time/admin use.
- **ChatMemory:** `MessageWindowChatMemory` (**[VERIFY]** exact class), keyed on `sessionId`.
- **VectorStore:** Pinecone (`PineconeVectorStore`, **[VERIFY]** exact config properties/builder for the pinned Spring AI version) — its free serverless tier comfortably covers a small portfolio-scale corpus (a few hundred to few thousand chunks). Needs a Pinecone account/API key + index (name, dimension matching the embedding model's output size, e.g. 384 for MiniLM) — this is an infra-level credential configured once server-side, distinct from the per-user BYO LLM key in §8, since the ingested content is the project's own interview material, not user data. If the free tier's limits ever become restrictive, self-hosted **Qdrant** (via Docker, `QdrantVectorStore` starter) is a fully free fallback with no external account needed and a very similar Spring AI API.
- **EmbeddingModel:** use a local `transformers` model (e.g. ONNX all-MiniLM-L6-v2) for ingestion rather than a hosted API — ingestion is a one-time admin batch job, not a per-user runtime cost, so this sidesteps needing an embedding-capable key (Groq doesn't serve embeddings) and keeps BYO-key scoped to chat only.
- **Structured output records:**
  ```java
  record EvaluationResult(int score, String correctness, String feedback,
      List<String> strengths, List<String> weaknesses, String recommendedNextDifficulty) {}
  record GeneratedQuestion(String questionText, String questionType, String topic, String difficulty) {}
  record InterviewReportSummary(int overallScore, List<String> strongTopics, List<String> weakTopics, String narrativeSummary) {}
  ```
- **Advisors:** `.defaultSystem(...)` for the interviewer persona/rubric; built-in `MessageChatMemoryAdvisor` for conversation memory; a custom `LoggingAdvisor` for prompt/response audit logs that **explicitly redacts the API key header** (this is the single most safety-critical piece of custom code in the project); built-in RAG advisor (**[VERIFY]** name, e.g. `QuestionAnswerAdvisor`) wired to `VectorStore` for question-generation calls only.

---

## 4. RAG Ingestion Pipeline — as actually implemented (Phase 2)

**What changed from the original sketch:** `java-backend-interview-handbook`'s content turned out to be HTML, not markdown — and much better-structured than assumed: each chapter (`java/04-COLLECTIONS.html`, `09-SPRING-CORE.html`, etc.) is a series of `<div class="section">` blocks, each one a complete, human-authored Q&A unit (question + ROI/frequency line + full answer). That meant:
- **Spring AI's built-in `JsoupDocumentReader`** (artifact `spring-ai-jsoup-document-reader`) does the extraction — no hand-rolled HTML parsing needed. Config: `.selector(".section").groupByElement(true).charset("UTF-8")` produces one clean `Document` per Q&A block directly from the real files, verified empirically against the actual content (not guessed) via a throwaway exploration test.
- **No `TokenTextSplitter` needed** — each `.section` *is* already the right chunk size (one concept per chunk), so splitting would only fragment a good boundary.
- **ROI stars turned out to be constant within a file** (always ★★★★★), so they're not a usable difficulty signal. Difficulty is instead **bucketed by position in the file**: first third → EASY, middle third → MEDIUM, last third → HARD — cheap, and a reasonable proxy since these guides run foundational-to-advanced (confirmed by eyeballing the actual ordering).
- **No LLM rephrasing call for this source.** Each chunk's question title (e.g. "How does a HashMap resolve collisions internally?") is extracted directly via regex (`^Q\d+\.\s*(.+?)\s*ROI:`) and used verbatim as the interview prompt — it's already a clean, well-formed question, so the `.entity(GeneratedQuestion.class)` rephrasing step from the original plan isn't needed here (it would only matter for a source with bare, unphrased titles).

**Content actually ingested (`backend/src/main/resources/interview-content/{topic}/*.html`, copied from the handbook):**
- `JAVA_COLLECTIONS` ← `04-COLLECTIONS.html` (35 chunks)
- `SPRING` ← `09-SPRING-CORE.html` + `10-SPRING-BOOT.html` (121 chunks)
- `DSA` ← `29-DATA-STRUCTURES-AND-ALGORITHMS.html` (50 chunks)
- `SYSTEM_DESIGN` ← `20-SYSTEM-DESIGN-FOR-4-YEAR-BACKEND-ENGINEER.html` (85 chunks)
- **291 chunks total**, confirmed via a live `POST /admin/ingest` run.

**Deferred, not implemented in this pass:** `DSA-notes-java` (plain markdown, many files near-empty — lower value) and `code-tracker`'s DSA problems (Source B — its API has no real data yet since that project has no seeded problems). Both are still viable future additions via the same `VectorStore`; the architecture doesn't change, just add another ingestion method in `ContentIngestionService`.

**Vector store:** `SimpleVectorStore` (in-memory), not Pinecone — no Pinecone account/API key was available to provision. `ContentIngestionService`/`QuestionSelectionService` are written against the `VectorStore` interface, so swapping in `PineconeVectorStore` (artifact `spring-ai-starter-vector-store-pinecone`, confirmed exact name) later is a one-bean change in `SpringAiConfig`, not a rewrite.

**Embedding model:** local transformers (`spring-ai-starter-model-transformers`), auto-configured, zero API key — confirmed working end-to-end (downloads the ONNX all-MiniLM-L6-v2 model to a local cache on first use). The OpenAI starter's own `OpenAiEmbeddingAutoConfiguration` had to be explicitly excluded in `application.yml` to avoid an ambiguous `EmbeddingModel` bean once both starters were on the classpath.

**Ingestion trigger:** `POST /api/admin/ingest` (`AdminController`), gated by the existing `.requestMatchers("/api/admin/**").hasRole("ADMIN")` — no separate admin dashboard needed for this (that's still Phase 5). A dev-only `AdminSeeder` (`CommandLineRunner`, `dev` profile only) promotes a configured `app.seed-admin-email` to ADMIN on startup — an ops/seed mechanism, not a self-service path, matching §5's RBAC design intent.

**Retrieval:** confirmed working exactly as designed — `vectorStore.similaritySearch(SearchRequest.builder().query(...).topK(5).filterExpression("topic == 'X' && difficulty == 'Y'").build())`. Both `AND`/`&&` parse fine in Spring AI's filter DSL (verified directly against `FilterExpressionTextParser`). `QuestionSelectionService.pickNext(topic, difficulty, usedChunkIds)` returns `Optional<StaticQuestionEntry>`, reusing the Phase 1 DTO shape; `InterviewSessionService.createNextQuestion()` tries this first and falls back to `StaticQuestionBankService` when empty (no ingested content yet for that combination). Verified live: `JAVA_COLLECTIONS`/EASY, `SPRING`/HARD, and `SYSTEM_DESIGN`/MEDIUM each served a distinct, correctly-filtered real question from the handbook.

---

## 5. API Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | JWT, reused pattern from job-portal |
| POST | `/sessions` | `{topic, startingDifficulty, questionCount}` + `X-LLM-Api-Key` → first question |
| GET | `/sessions/{id}` | current state + history |
| POST | `/sessions/{id}/answers` | `{answerText, code?}` + key → evaluation + next question |
| POST | `/sessions/{id}/complete` | triggers report generation |
| GET | `/sessions/{id}/report` | full report |
| GET | `/sessions` | list of past sessions |
| POST | `/admin/ingest` | requires ADMIN role |
| GET | `/admin/users` | requires ADMIN role — paginated user list + summary stats |
| GET | `/admin/users/{id}` | requires ADMIN role — one user's profile + session history |
| GET | `/admin/stats` | requires ADMIN role — platform-wide usage analytics |

Plain REST is sufficient for MVP (discrete turn-based request/response). Streaming evaluation feedback via SSE is a Phase 4 nice-to-have, not required.

**Role-based access control:** the JWT includes a `role` claim (USER/ADMIN); Spring Security's method security (`@PreAuthorize("hasRole('ADMIN')")`) guards every `/admin/**` endpoint, including `/admin/ingest` — one consistent mechanism rather than a bespoke check per endpoint. A user's role is set at registration (defaults to USER) and promoted to ADMIN only via direct DB update or a seed script — there's no self-service "become admin" path.

**Admin analytics data:** no new tables needed beyond the `role`/`lastActiveAt` additions to `User` — everything else is aggregate queries over existing entities:
- `/admin/users` — per user: email, displayName, createdAt, lastActiveAt, sessionCount (`count(InterviewSession)`), avgScore (`avg(Evaluation.score)` across their sessions), most-practiced topic. Backed by a JPQL aggregate query or a projection interface (e.g. `UserSummaryProjection`), paginated via Spring Data `Pageable`.
- `/admin/users/{id}` — that user's full `InterviewSession` list with per-session score/topic/difficulty, for drill-down.
- `/admin/stats` — platform-wide rollup: totalUsers, totalSessions, sessionsByTopic, avgScoreByTopic, avgScoreByDifficulty, signupsOverTime (e.g. grouped by week) — a small `AdminAnalyticsService` with a handful of aggregate repository queries.

---

## 6. Adaptive Difficulty Logic

Rule-based, not ML — deterministic, unit-testable, and easy to explain in an interview about the project itself.

`AdaptiveDifficultyService.computeNext(session, latestEvaluation)`:
1. Map difficulty to ordinal (EASY=0, MEDIUM=1, HARD=2).
2. Base delta from model's own suggestion: HARDER=+1, SAME=0, EASIER=-1.
3. Guardrails against an inconsistent model suggestion: score ≥85 → floor delta at +1; score ≤35 → cap delta at -1.
4. Streak rule: two consecutive ≥80 scores at the same difficulty forces +1 (prevents plateauing).
5. Clamp to [0,2] and persist as the new `currentDifficulty`, which becomes the next RAG retrieval's difficulty filter.

---

## 7. Frontend Structure (React + TS)

- `api/` — fetch client attaching JWT + `X-LLM-Api-Key`; `sessions.ts` for start/answer/complete/report calls.
- `context/ApiKeyContext.tsx` — holds the key in state + `sessionStorage`.
- `pages/SessionStartPage.tsx` — topic/difficulty/question-count pickers + API key input (masked, password-style field).
- `pages/InterviewPage.tsx` — state machine `AWAITING_ANSWER → SUBMITTING → SHOWING_FEEDBACK → next|COMPLETED`, with `QuestionPanel`, `AnswerInput`, `CodeEditorPane` (Monaco, shown only for CODING questions), `EvaluationFeedback`, `ProgressBar`.
- `pages/ReportPage.tsx` — overall score, strong/weak topics, per-question breakdown.
- `pages/AdminDashboardPage.tsx` — ADMIN-only route (hidden/redirected for USER role, enforced both by hiding the nav link and by the backend rejecting the call regardless): paginated user table (email, signup date, last active, session count, avg score) plus a stats overview (total users/sessions, topic and difficulty breakdowns). Backend is still the real gate — the frontend check is just UX, never the security boundary.
- No Redux/Zustand needed — session-scoped context is enough for this linear flow.

---

## 8. BYO API Key Handling (web adaptation of the fitforge pattern)

Core property to preserve: **the server never durably stores the user's key**, and the server never becomes financially/legally responsible for the user's LLM usage.

- Captured via a masked input on session start.
- Stored client-side in `sessionStorage` (cleared on tab close) — not `localStorage` (persists indefinitely) and not a cookie (auto-sent everywhere, CSRF exposure).
- Sent per-request as header `X-LLM-Api-Key` over HTTPS, never as a URL param (would leak into access logs).
- Server uses it to build a throwaway `ChatClient` for that single request only — never written to any DB column or log. The audit-logging Advisor must strip this header before persisting anything.
- Unlike fitforge's on-device encrypted storage, a server has no legitimate reason to persist the key at all, even encrypted (it would just relocate the liability). So: **zero server-side persistence**, full stop — the frontend re-supplies it from `sessionStorage` each session.
- Known friction to flag to Nasir: re-entering the key per new session/tab. A clearly-labeled "remember on this device" opt-in via `localStorage` could be offered later if that friction proves annoying — not needed for MVP.

---

## 9. Phased Build Plan

**Phase 1 — MVP, no RAG, no adaptive difficulty**
Static question bank (JSON/seed data per topic, ~15-20 questions), full entity model (including `role`/`lastActiveAt` on `User` from the start, even though nothing uses them yet — cheap now, avoids a migration later), JWT auth with the role claim, `POST /sessions` → `POST /sessions/{id}/answers` (single evaluation call) → `POST /sessions/{id}/complete` → `GET /sessions/{id}/report` (simple average, no narrative yet). BYO-key flow built end-to-end now since it affects every LLM call from day one. Difficulty fixed for the whole session.
*Goal: prove the full loop works with a real LLM call.*

**Phase 2 — RAG ingestion — done**
Built and verified end-to-end: `ContentIngestionService` (HTML→chunks via `JsoupDocumentReader`, position-bucketed difficulty), `QuestionSelectionService` (RAG retrieval, no rephrasing needed for this content), `SpringAiConfig` (`SimpleVectorStore` + local transformers embeddings), `AdminController`/`AdminSeeder` for triggering ingestion. 291 chunks ingested across all 4 topics; live sessions confirmed serving correctly topic/difficulty-filtered real questions. See §4 for the full account of what changed from the original sketch once the real content's shape was known.
*Goal met: questions come from Nasir's own content, verified via real ingestion + real session starts (not just unit tests).*

**Phase 3 — Adaptive difficulty — done**
`AdaptiveDifficultyService.computeNext(currentDifficulty, score, modelSuggestion, precedingQuestionAtSameDifficultyAlsoScoredHigh)` implemented exactly per §6 (guardrails at score ≥85/≤35, the plateau-busting streak rule, clamped to [EASY, HARD]) as a pure function — no repository access, so the full truth table is unit-tested directly (16 parameterized cases + 2 dedicated regression cases for the guardrail-overrides-model-suggestion scenarios). Wired into `InterviewSessionService.submitAnswer`: computed right after the evaluation is scored, persisted onto `session.currentDifficulty`, and confirmed (via a dedicated test) that the *next* question is actually picked at the new difficulty, not the old one. Not yet verified against a real LLM call end-to-end (needs a real API key, same limitation as Phases 1-2), but the adaptation logic itself has full deterministic coverage independent of any LLM.
*Goal met: difficulty visibly shifts across a session based on performance — verified via unit tests covering every branch of the rule.*

**Phase 4 — Polish — partially done**
Built and verified:
- **LLM-generated narrative report summary**: `ReportService.completeSession` now requires the same `X-LLM-Api-Key`/`X-LLM-Provider`/`X-LLM-Model` headers as the other session endpoints, builds a per-request `ChatClient` via `PerRequestChatClientFactory`, and calls `.entity(InterviewReportSummary.class)` (new record: `strongTopics`, `weakTopics`, `narrativeSummary`) with the full per-question breakdown (score/correctness/feedback/strengths/weaknesses) as context. The LLM's subtopic-level strong/weak read overrides the Phase 1 single-topic heuristic when non-empty; `summaryText` is persisted on `Report`. Only called once per session (report generation is already idempotent — a repeat `POST /complete` or `GET /report` never re-invokes the LLM).
- **Redacted audit logging**: `InterviewSessionService`/`ReportService` log an audit line (SLF4J, `INFO`) before each LLM call — `sessionId`/`questionId`/`provider`/`model` only, never the raw API key. Locked in via `AuditLoggingTest`, which attaches a Logback `ListAppender` to the root logger, runs `submitAnswer` with a distinctive fake key, and asserts the key string never appears in any captured log line — the single most safety-critical test in the project per §8's BYO-key privacy claim.
- **Session history**: `DashboardPage` (already existed from Phase 1) now also shows the overall score next to completed sessions. `SessionSummaryResponse` gained an `overallScore` field (null until a report exists), backed by a batched `ReportRepository.findBySessionIn` query in `InterviewSessionService.listSessions`/`getSession` (no N+1).

Not done in this pass (deferred, no user-facing gap forcing it yet):
- Optional SSE streaming feedback — still a nice-to-have per the original plan, not required for the core loop.
- A dedicated visual styling pass beyond the existing functional CSS.

**Phase 5 — Admin dashboard & analytics — done**
Built and verified end-to-end against real dev data (not just mocks):
- **RBAC**: reused the existing URL-matcher gate (`.requestMatchers("/api/admin/**").hasRole("ADMIN")` in `SecurityConfig`, from Phase 1) rather than adding `@PreAuthorize` — one consistent mechanism, per §11's "adopt this same URL-matcher approach" guidance. `AdminControllerTest` locks in that every new endpoint (`/users`, `/users/{id}`, `/stats`) rejects a USER-role JWT with 403 and succeeds for an ADMIN-role one (promoted via the repository in-test, then re-logged-in so the JWT's `role` claim reflects it — mirroring how `AdminSeeder` promotes for real).
- **`AdminAnalyticsService`**: backs all three endpoints. Deliberate scale trade-off, called out explicitly: rather than hand-written JPQL group-by queries per metric, it loads the (small, portfolio-scale) rows a request actually needs and aggregates with Java streams — `listUsers` batches its two lookups (`InterviewSessionRepository.findByUserIn`, `ReportRepository.findBySessionIn`) once across the whole page rather than per-user, so it isn't N+1. Same pragmatic-scale reasoning already used for `SimpleVectorStore` (§4).
- **`GET /admin/users`** — paginated (`Pageable`, Spring's `Page<AdminUserSummary>` JSON shape used directly) — email, displayName, createdAt, lastActiveAt, sessionCount, averageScore (null until a report exists), mostPracticedTopic.
- **`GET /admin/users/{id}`** — full profile + session history (`AdminUserDetail`/`AdminSessionSummary`), overallScore null per-session until that session has a report.
- **`GET /admin/stats`** — totalUsers, totalSessions, sessionsByTopic, averageScoreByTopic, averageScoreByDifficulty (from `Evaluation`, not `Report`, so it reflects every scored answer), signupsOverTime (grouped by ISO week, Monday start).
- **Frontend**: `AdminDashboardPage` (Users tab: paginated table linking to drill-down; Stats tab: overview counts + topic/difficulty/signup tables — plain tables, no charting library added, matching the "no dedicated styling pass" note above), `AdminUserDetailPage` (profile + full session history table), `AdminRoute` (role-gated route wrapper — UX only, the backend is the real gate per §7), an "Admin" nav link on `DashboardPage` shown only for `role === "ADMIN"`.
- **Verified live**: promoted a real registered user to ADMIN via `AdminSeeder` (`app.seed-admin-email`) against the persisted dev H2 DB, then hit all three endpoints with curl — correct pagination shape, correct null-handling for users/sessions with no report yet, correct RBAC 403 for a non-admin token.

Each phase should be independently demoable before moving to the next.

---

## 10. Verification / Testing

- **`AdaptiveDifficultyServiceTest`** (JUnit 5) — truth table of (currentDifficulty, score, modelSuggestion) → expected next difficulty. Fully deterministic, thorough coverage expected here.
- **`InterviewSessionServiceTest`** — mock `ChatClient`/`ChatModel` to return canned `EvaluationResult`s; never call a real LLM in unit tests.
- **`@SpringBootTest` + `@AutoConfigureMockMvc`** controller slice tests with `ChatClient` mocked — verifies JSON shapes, auth enforcement, and that `X-LLM-Api-Key` is required/forwarded.
- **Admin RBAC test** — a USER-role JWT must get 403 on every `/admin/**` endpoint; only an ADMIN-role JWT succeeds. Cheap to write, and the kind of gap that's easy to silently introduce later (e.g. a new admin endpoint added without the annotation).
- **Regression test that the audit/logging Advisor never emits the raw API key** — arguably the most important test in the project given the privacy claims in §8.
- **RAG retrieval quality** isn't unit-testable in the strict sense — do manual "golden query" spot checks per topic during Phase 2 (5-10 queries, eyeball top-K relevance/difficulty tagging), plus one automatable smoke check: assert every ingested (topic, difficulty) combination returns a non-empty result set (catches ingestion bugs, not semantic quality).
- **End-to-end manual pass per phase:** an `.http` file (pairs naturally with IntelliJ) or Postman collection covering register → login → start session → answer several questions → complete → fetch report. Also manually try a deliberately wrong / excellent / middling answer to the same question and confirm score/feedback/difficulty-shift all behave sensibly — the practical substitute for testing LLM output quality.

---

## 11. Reuse from `code-tracker`

`code-tracker` turns out to already be a full working Spring Boot + React/TS project with JWT auth, roles, and an admin analytics dashboard — not just a name-only repo. Several pieces are worth copying and adapting directly rather than rebuilding from scratch.

**Backend — copy and adapt:**
- `security/JwtAuthFilter.java`, `security/JwtUtil.java`, `security/SecurityConfig.java` — a working stateless JWT setup (jjwt, BCrypt, CORS, and critically `.requestMatchers("/api/admin/**").hasRole("ADMIN")` gating at the filter-chain level). Adopt this same URL-matcher approach for §5's RBAC instead of scattering `@PreAuthorize` annotations — it's proven and simpler to audit in one place.
- `entity/User.java` (role enum field, `@PrePersist` for createdDate), `entity/Role.java`, `entity/Token.java` (refresh-token revocation) — near-identical shape to our planned `User` entity; copy the structure and add this project's extra fields on top.
- `controller/admin/AdminAnalyticsController.java` — the pattern to follow for `/admin/stats` (§5): `YearMonth`-iteration for monthly trend charts, DTO composition, repository `countBy*`/aggregate queries. Copy the trend-building loop directly for a "signups/sessions over time" chart.
- `exception/GlobalExceptionHandler.java` and `config/WebConfig.java` (CORS) — reuse as-is.
- `pom.xml` — good baseline dependency list (Spring Security, JJWT, springdoc-openapi, validation, actuator); skip the parts specific to code-tracker's payment/S3/PDF features (Stripe, AWS S3, PDFBox) and add what §3 needs for Spring AI instead.

**Frontend — copy and adapt:**
- `components/auth/ProtectedRoute.tsx` / `AdminRoute.tsx` — route guards, copy near-verbatim (role check is `user?.role === "ADMIN"`, matching our `role` field).
- `hooks/use-auth.tsx` — the `AuthProvider`/`useAuth` pattern (login/register/logout, `isAdmin` derived from `user.role`) — adapt to this project's session-shaped API instead of code-tracker's problem-tracking API.
- `components/layout/` (`DashboardLayout`, `Sidebar`, `MobileSidebar`) and the full `components/ui/` shadcn set (already has `table.tsx`, `chart.tsx`, `dialog.tsx`, `card.tsx`, etc.) — a ready-made component library and app shell; adopt wholesale rather than reinstalling/re-theming shadcn from scratch.
- `pages/admin/AdminUsersPage.tsx`, `AdminUserDetailsPage.tsx`, `AdminAnalyticsPage.tsx` + `hooks/useAdminUsers.tsx`, `hooks/useDashboardStats.tsx`, `hooks/usePaginationState.ts` — directly the shape of Phase 5's admin dashboard (§9); swap the displayed data (sessions/scores/topics instead of problems/purchases) but the pagination/table/chart wiring transfers almost unchanged.
- `services/adminService.ts`'s pattern (a thin service wrapper delegating to an `api/adminAPI.ts` layer) — reuse the service/API-layer split.

**What NOT to reuse — conflicts with this project's design:**
- `service/OpenAiService.java` calls Groq with a **server-owned API key** (from config), funded through a credits/Stripe purchase system (`AtsController`, `Purchase` entity). That's the opposite of this project's BYO-key privacy model (§8) — don't bring over the credits/purchase machinery; there's no billing model here since the user supplies their own key.
- Its **prompt-construction pattern** is still worth keeping in mind, though: a single detailed prompt demanding "ONLY a valid JSON object with this exact structure," plus stripping stray ` ``` ` markdown fences from the response. That's a useful fallback reference if Spring AI's `.entity(EvaluationResult.class)` structured-output conversion ever proves unreliable in practice during Phase 1 — not something to build proactively, just a known escape hatch.

---

## 12. Application Flow (User Journey)

Ties together the pieces from §1 (core loop), §5 (endpoints), §7 (frontend pages), and §8 (key handling) into the actual path a person walks through the app.

**First-time user:**
```
Landing → Register (email/password/displayName) → auto-login (JWT stored)
   → Dashboard (GET /sessions — empty list, "Start New Interview" CTA)
```

**Starting and running an interview:**
```
SessionStartPage
  (pick topic, starting difficulty, question count,
   paste LLM API key → sessionStorage, §8)
        │
        ▼  POST /sessions
InterviewPage  ── state machine, one loop per question ──
        │
        ├─ AWAITING_ANSWER   (QuestionPanel + CodeEditorPane if CODING)
        ├─ SUBMITTING        (POST /sessions/{id}/answers, X-LLM-Api-Key header)
        ├─ SHOWING_FEEDBACK  (EvaluationFeedback: score/feedback/strengths/weaknesses,
        │                     ProgressBar advances, "Next Question" button)
        └─ back to AWAITING_ANSWER with the returned nextQuestion
                     … repeats until questionCount is reached …
        │
        ▼  last question's feedback shows "Finish Interview" instead of "Next Question"
        ▼  POST /sessions/{id}/complete
ReportPage (GET /sessions/{id}/report)
  overall score, strong/weak topics, per-question breakdown accordion,
  "Start Another Interview" / "Back to Dashboard"
```

**Returning user:** Dashboard lists past sessions (topic, score, date) via `GET /sessions`; clicking one reopens its `ReportPage` read-only. There's no resume for an interview left mid-session — closing the tab mid-interview leaves that `InterviewSession` row `IN_PROGRESS` in the DB, un-resumable in the UI (shows in the list as still in-progress / effectively abandoned). Resuming an interrupted session isn't in scope for MVP — worth flagging as a known gap rather than solving now.

**Admin (Phase 5, separate branch of the flow):**
```
ADMIN-role login → extra "Admin" nav item appears (hidden for USER role)
        │
        ▼
AdminDashboardPage
  ├─ Users tab   → GET /admin/users (paginated table) → click a user
  │                 → AdminUserDetailsPage (GET /admin/users/{id}, their session history)
  └─ Stats tab   → GET /admin/stats (topic/difficulty breakdowns, signups-over-time chart)
```
`POST /admin/ingest` (content ingestion, §4) is a separate developer/ops action — triggered manually when content changes, not a button surfaced in the admin's day-to-day UI, to avoid an admin accidentally re-triggering a full re-embed.

---

## Critical Files (once implementation starts)

- `config/SpringAiConfig.java` — the `VectorStore` bean (`SimpleVectorStore`, done). `ChatMemory` bean still to add.
- `service/PerRequestChatClientFactory.java` — the BYO-key mechanism (§8's crux) — done.
- `service/InterviewSessionService.java` — the core submit-answer loop (§1) — done, now RAG-first with static-bank fallback.
- `service/AdaptiveDifficultyService.java` — isolated difficulty state machine (§6) — done.
- `service/QuestionSelectionService.java` — RAG retrieval (§4) — done; no rephrasing call needed for this content, see §4.
- `service/ContentIngestionService.java` — HTML→chunk ingestion via `JsoupDocumentReader` (§4) — done.
- `service/CodeTrackerProblemClient.java` — deferred (§4, Source B) — `code-tracker` has no real problem data yet.
- `dto/EvaluationResult.java` — done. `dto/InterviewReportSummary.java` — done (Phase 4 narrative summary). `GeneratedQuestion.java` — still not needed (no rephrasing call for the ingested content, see §4).
- `service/AdminAnalyticsService.java` — the aggregate queries backing `/admin/users`, `/admin/users/{id}` and `/admin/stats` (§5, Phase 5) — done, verified live. `AdminController`/`AdminSeeder` (ingestion trigger + dev seed) already in place from Phase 2.

---

## 13. Infra & tooling hardening (post-Phase-5)

Requested and done outside the phased build plan, since these are cross-cutting rather than feature work:

- **Flyway migrations** (`backend/src/main/resources/db/migration/V1..V7`) replaced `ddl-auto: update`/`create-drop` everywhere (prod, dev, test all now use `validate`). One shared SQL set for both MySQL (prod) and H2 (dev/test) — the only divergence is the `@Lob` String columns' type (H2 has a native `CLOB`; MySQL has none and uses `LONGTEXT` instead), resolved via a Flyway placeholder (`${clob_type}`, set per-profile in each `application*.yml`) rather than maintaining two parallel migration sets. Verified empirically: the full test suite (Spring context + H2) initially failed schema validation until the placeholder was added — confirms it's exercised for real, not just present in config. The MySQL side is unverified against a live server (none available in this environment), but the `flyway-mysql`/`LONGTEXT` choices are Flyway's/Hibernate's own documented defaults for that dialect.
- **`.env` support**: `backend/.env(.example)` loaded via `me.paulschwarz:springboot3-dotenv` (maps `MY_VAR` → `my.var`/`${MY_VAR}` automatically, no extra config) — same env vars that already had `${VAR:default}` placeholders in `application.yml` (DB creds, JWT secret, CORS origins, seed-admin email). `frontend/.env(.example)` needs no library — Vite reads `.env` natively (`VITE_API_BASE_URL`, already referenced in `api/client.ts`). Both `.env` files are gitignored; `.env.example` is committed as the documented template.
- **Profile switching, found the hard way**: a `SPRING_PROFILES_ACTIVE=dev` line was first tried inside `backend/.env` for one-less-flag local dev — booted, but silently did nothing (fell back to the default/MySQL profile and failed trying to reach a MySQL server that doesn't exist here). Confirmed empirically: `.env` values load too late in Spring Boot's startup sequence to influence *which* profile activates, even though the same file's plain `${VAR}` placeholders resolve fine everywhere else. Fixed correctly with `spring.profiles.default: dev` in `application.yml` — the property Spring Boot documents specifically for "activate this profile only if nothing else already specified one," so it never fights an explicit `-Dspring-boot.run.profiles=...` or a real deploy-time `SPRING_PROFILES_ACTIVE`. Verified live: a bare `mvn spring-boot:run` with zero flags now correctly falls back to `dev` (H2 + Flyway), and reverted the ineffective `.env` line. No frontend equivalent was needed — Vite already auto-selects `.env`/`.env.production` based on which npm script runs (`vite` vs `vite build`), with no flag to forget in the first place.
- **Spring Boot DevTools** added (`optional`, runtime scope) for auto-restart on classpath changes during `mvn spring-boot:run` — confirmed live (`restartedMain` thread visible in the boot log).
- **Frontend build tool**: swapped `@vitejs/plugin-react` (Babel) for `@vitejs/plugin-react-swc` — faster dev-server HMR/builds, drop-in replacement (same `vite.config.ts` shape).
- **Global error handling completion**: added a catch-all `@ExceptionHandler(Exception.class)` to `GlobalExceptionHandler` so a genuine bug returns a clean generic 500 (logged in full server-side) instead of a stack trace — deliberately *not* wrapping application code in try/catch, since a single centralized boundary is both the existing pattern here and easier to audit for the "never leak the LLM key" claim (§8) than the same logic scattered per-method. Adding the catch-all surfaced a real regression, caught by the existing test suite: it shadowed Spring MVC's own default handling of framework-level exceptions (`MissingRequestHeaderException`, malformed-JSON `HttpMessageNotReadableException`, bad-path-variable `MethodArgumentTypeMismatchException`, wrong-verb `HttpRequestMethodNotSupportedException`), turning what should be clean 4xx responses into 500s. Fixed by naming each explicitly ahead of the catch-all, in the same `{"message": ...}` shape as the rest of the API. Locked in with `GlobalExceptionHandlerTest` (catch-all never leaks exception detail) and a new `SessionControllerTest` case (malformed JSON body → 400, not 500).
