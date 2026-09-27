# Whiteboard Wingman

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
React + TypeScript (Vite)  --->  Spring Boot API  --->  PostgreSQL 16 + pgvector (Docker)
   JWT in localStorage            Flyway migrations       schema is validate-only; Flyway owns it
   LLM key in sessionStorage      Spring Security (JWT)
                                  Spring AI --------->  pgvector vector_store table (RAG over
                                                          ingested interview-handbook content,
                                                          local all-MiniLM-L6-v2 embeddings)
                                            --------->  Groq / OpenAI (the caller's own API key,
                                                          never stored server-side)
```

**Bring-your-own LLM key**: every evaluation call is built from a key the user pastes in at
session start, held only in `sessionStorage` and a request header — never written to any database
column or log line. See `PLAN.md` §8 for the full design and the regression test that locks it in.

## Tech stack

- **Backend**: Java 17, Spring Boot 3.5.9, Spring Security (JWT), Spring Data JPA, Spring AI 1.0.1
  (OpenAI-compatible client for Groq/OpenAI, local embedding model, pgvector vector store),
  Flyway, PostgreSQL 16 + pgvector (dev, test and prod alike), Testcontainers, Maven.
- **Infrastructure** (repo-root `docker-compose.yml`): PostgreSQL + pgvector, Kafka (KRaft) with
  kafka-ui, and — behind a compose profile — the Python `doc-processor`.
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
- **Docker** (Docker Desktop on Windows/macOS) — required: the database runs in a container
  (`docker compose up -d`), and `mvn test` starts its own throwaway Postgres via Testcontainers.
- A Groq or OpenAI API key to actually run an interview (get one free at
  [console.groq.com](https://console.groq.com)) — the app itself needs no API key to start up.

## Running locally

### Infrastructure (Docker)

From the repo root:

```bash
docker compose up -d        # postgres (+pgvector), kafka, kafka-init (creates topics), kafka-ui
docker compose ps           # postgres/kafka should be "healthy"; kafka-init exits 0 once done
```

| Service | Host address | Notes |
|---|---|---|
| `postgres` (`pgvector/pgvector:pg16`) | `localhost:5433` | db/user/password `wingman`; data in the `postgres-data` volume |
| `kafka` (single-node KRaft) | `localhost:9092` | containers use `kafka:29092` |
| `kafka-ui` | `http://localhost:8085` | browse topics/messages |

Postgres is published on **5433**, not 5432, so it can't collide with a natively installed
PostgreSQL (set `POSTGRES_PORT` to change it, and `DB_PORT` in `backend/.env` to match). The
Python document processor is opt-in: `docker compose --profile processor up -d --build`.
`docker compose down` stops everything but keeps the database volume; add `-v` to wipe it.

### Backend

```bash
cd backend
cp .env.example .env      # fill in real values if you have any non-default ones
mvn spring-boot:run
```

`dev` is the default profile (`spring.profiles.default` in `application.yml`), so **no `-D` flag
is needed** — it activates automatically whenever nothing else says otherwise. Every profile uses
the same PostgreSQL (defaults: `localhost:5433`, db/user/password `wingman`, overridable via
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`); `dev` only adds the admin seeder below.
`.env` is loaded automatically (via `springboot3-dotenv`) for everything *except* which profile is
active — see `.env.example`. Flyway creates the schema (V1–V14, including the pgvector
`vector_store` table) on first boot. **Upgrading an older checkout?** An existing `backend/.env`
from the MySQL/H2 days still says `DB_USERNAME=root` — change it (and `DB_PASSWORD`) to `wingman`
or delete those lines.

To run as the default/prod profile instead, override it explicitly:
`mvn spring-boot:run "-Dspring-boot.run.profiles=default"` (quote it in PowerShell so a stray
space from copy-paste can't split the argument in two).

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
mvn test        # 106 tests: unit + full-stack (real JWT auth, real Postgres+pgvector, mocked LLM calls)
```

Docker must be running: the full-stack tests start a throwaway `pgvector/pgvector:pg16`
container through Testcontainers (`jdbc:tc:` URL in `application-test.yml`), independent of the
compose stack, and apply the same Flyway migrations as dev/prod.

```bash
cd frontend
npx tsc --noEmit   # type-check
npm run build      # production build
```

The backend test suite never calls a real LLM — the `ChatClient` is mocked — so it runs offline
and needs no API key.

## Content ingestion (RAG)

Real interview-handbook content is bundled under
`backend/src/main/resources/interview-content/` (plus ~160 more chapters fetched from the
handbook's GitHub repo) and gets chunked, embedded locally, and stored in Postgres (pgvector
`vector_store` table), so it survives restarts. Re-running it is idempotent — chunks get
deterministic ids and upsert in place. A full run takes several minutes. Trigger ingestion (as an
ADMIN) with:

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
- Pinecone was the originally planned vector store; pgvector in the same PostgreSQL is used
  instead (no extra service or account to provision).
- SSE streaming feedback and a dedicated visual styling pass were deliberately deferred as
  low-value nice-to-haves.

See `PLAN.md` for the full, honest account of every decision and trade-off.
