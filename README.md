# AI-Powered Mock Interview Simulator

A live, adaptive technical mock interview platform. It asks a question, evaluates your answer
with an LLM, adjusts the next question's difficulty based on how you did, and produces a report
at the end — with an admin view into platform-wide usage.

Full design rationale, the phased build history, and every deliberate deviation from the original
plan live in [`PLAN.md`](PLAN.md). This README is the practical "how do I run this" reference.

## What it does

1. Pick a topic (Java Collections, Spring, DSA, System Design), a starting difficulty, and how
   many questions you want.
2. Answer each question (plain text, or code in an in-browser editor for coding questions).
3. An LLM scores your answer, gives feedback, and recommends the next question's difficulty —
   which a rule-based adaptive algorithm then applies (with guardrails, so one lucky/unlucky
   answer can't wildly swing things).
4. After the last question, get a full report: overall score, strong/weak topics, and an
   LLM-written narrative summary.
5. Admins get a dashboard: per-user stats (sessions, average score, most-practiced topic) and
   platform-wide analytics (sessions by topic, scores by difficulty, signups over time).

## Architecture

```
React + TypeScript (Vite)  --->  Spring Boot API  --->  MySQL (prod) / H2 (dev, file-based)
   JWT in localStorage            Flyway migrations       schema is validate-only; Flyway owns it
   LLM key in sessionStorage      Spring Security (JWT)
                                  Spring AI --------->  In-memory vector store (RAG over
                                                          ingested interview-handbook content)
                                            --------->  Groq / OpenAI (the caller's own API key,
                                                          never stored server-side)
```

**Bring-your-own LLM key**: every evaluation call is built from a key the user pastes in at
session start, held only in `sessionStorage` and a request header — never written to any database
column or log line. See `PLAN.md` §8 for the full design and the regression test that locks it in.

## Tech stack

- **Backend**: Java 17, Spring Boot 3.5.9, Spring Security (JWT), Spring Data JPA, Spring AI 1.0.1
  (OpenAI-compatible client for Groq/OpenAI, local embedding model, in-memory vector store),
  Flyway, MySQL (prod) / H2 (dev & test), Maven.
- **Frontend**: React 19, TypeScript, Vite, react-router-dom, Monaco Editor (the code editor
  behind VS Code) for coding-question answers.

## Project layout

```
backend/    Spring Boot API (Maven)
frontend/   React + TypeScript app (Vite)
PLAN.md     Full design doc: architecture decisions, phased build log, what changed and why
```

## Prerequisites

- Java 17+
- Node 18+
- Maven (or use the bundled `./mvnw` if present)
- A MySQL server, **only** if running the default/prod profile — local dev uses a file-based H2
  database instead, no install required (see below).
- A Groq or OpenAI API key to actually run an interview (get one free at
  [console.groq.com](https://console.groq.com)) — the app itself needs no API key to start up.

## Running locally

### Backend

```bash
cd backend
cp .env.example .env      # fill in real values if you have any non-default ones
mvn spring-boot:run
```

`dev` is the default profile (`spring.profiles.default` in `application.yml`), so **no `-D` flag
is needed** — it activates automatically whenever nothing else says otherwise. It uses a
file-based H2 database (`backend/data/`, gitignored), so you don't need a MySQL install for local
development. `.env` is loaded automatically (via `springboot3-dotenv`) for everything *except*
which profile is active — see `.env.example`. Flyway creates the schema on first boot.

To run against the default/prod profile instead (e.g. to test against a real local MySQL),
override it explicitly: `mvn spring-boot:run "-Dspring-boot.run.profiles=default"` (quote it in
PowerShell so a stray space from copy-paste can't split the argument in two).

To promote a registered user to ADMIN locally, set `APP_SEED_ADMIN_EMAIL` in `.env` to their email
and restart — a dev-only seeder promotes them on startup.

Runs on `http://localhost:8080`. `spring-boot-devtools` is on the classpath, so the backend
auto-restarts on classpath changes.

### Frontend

```bash
cd frontend
cp .env.example .env      # defaults already point at http://localhost:8080/api
npm install
npm run dev
```

Runs on `http://localhost:5173`.

### First run walkthrough

1. Open `http://localhost:5173`, register an account.
2. Click **Start New Interview**, pick a topic/difficulty, paste in a real Groq/OpenAI API key.
3. Answer questions until the session completes, then view the report.
4. (Optional) Promote yourself to ADMIN as described above, restart the backend, log in again,
   and open the **Admin** link on the dashboard.

## Running the tests

```bash
cd backend
mvn test        # 62 tests: unit + full-stack (real JWT auth, real H2 DB, mocked LLM calls)
```

```bash
cd frontend
npx tsc --noEmit   # type-check
npm run build      # production build
```

The backend test suite never calls a real LLM — the `ChatClient` is mocked — so it runs offline
and needs no API key.

## Content ingestion (RAG)

Real interview-handbook content is bundled under
`backend/src/main/resources/interview-content/` and gets chunked + embedded into the in-memory
vector store. Trigger ingestion (as an ADMIN) with:

```bash
curl -X POST http://localhost:8080/api/admin/ingest -H "Authorization: Bearer <admin JWT>"
```

Without running this, question selection falls back to a small static question bank per topic —
the app works fine either way, ingestion just makes the questions draw from richer real content.

## API overview

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | — |
| POST | `/api/sessions` | user + LLM key headers |
| POST | `/api/sessions/{id}/answers` | user + LLM key headers |
| POST | `/api/sessions/{id}/complete` | user + LLM key headers |
| GET | `/api/sessions/{id}/report`, `/api/sessions/{id}`, `/api/sessions` | user |
| POST | `/api/admin/ingest` | ADMIN |
| GET | `/api/admin/users`, `/api/admin/users/{id}`, `/api/admin/stats` | ADMIN |

The LLM key headers are `X-LLM-Api-Key` (required), `X-LLM-Provider` (`GROQ`\|`OPENAI`, default
`GROQ`), `X-LLM-Model` (optional).

## What's not done

- No live end-to-end verification against a real LLM provider beyond manual spot checks (all
  automated tests mock the LLM call).
- MySQL/prod Flyway migrations are verified against the H2 dialect only — no MySQL server was
  available in development. See `PLAN.md` §13 for the exact divergence (one `${clob_type}`
  placeholder).
- Pinecone was the originally planned vector store; an in-memory `SimpleVectorStore` is used
  instead (no Pinecone account provisioned) — swapping it in later is a one-bean change.
- SSE streaming feedback and a dedicated visual styling pass were deliberately deferred as
  low-value nice-to-haves.

See `PLAN.md` for the full, honest account of every decision and trade-off.
