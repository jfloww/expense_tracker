# Expense Tracker

A statement-based expense tracker for households with accounts across several
banks, built as a long-term AI engineering project. Upload statements, review
extracted transactions, and understand where shared spending changed. The
target product uses household memberships and roles, starts on the web, and
adds a mobile app using the same API later. The backend uses **Java 21 and
Spring Boot** with PostgreSQL; the web client uses Next.js and TypeScript.

Initial issuer targets: **Capital One, Chase, and Amex**. Their actual export
formats need private sample verification. See [next move](docs/next-move.md) for
current capabilities, verification, limitations, and the immediate task.

The AI direction is PDF extraction with a CSV baseline, OpenAI first, and a
Claude comparison later. AI proposes; Java validates and calculates money.
Evaluation and tracing begin with the first experiment.

## Structure

```text
expense-tracker/
  backend/                Java Spring Boot product backend
  frontend/               Next.js + TypeScript web client (planned)
  mobile/                 Expo mobile client (later)
  worker/                 Import worker entry point (planned)
  codex/                  Dated implementation/review notes
  claude/                 Dated independent reviews and current decisions
  packages/
    contracts/            API conventions; generated client types later
    shared/               Pure date/money/error helpers (planned)
  docs/
    product.md            Product scope and distinctive feature options
    features-and-pages.md  Feature inventory, page placement, and navigation
    architecture.md       Boundaries, data model, and AI/RAG/MCP placement
    backend-transition.md  Java stack and legacy data-transfer policy
    engineering-conventions.md  Patterns, shared code, date/money contracts
    AI-Tech.md            Harness, RAG, MCP, and planned AI workflows
    imports.md            Issuer adapters, validation, review, deduplication
    statement-schema.md   Proposed statement, source, review, and ledger tables
    api-plan.md           Intended authenticated API
    security.md           Browser login, MFA, sessions, and local setup
    roadmap.md            Milestones with acceptance criteria
    next-move.md          Current status, verification, and immediate coding task
    reporting-contract.md Required reporting behavior and synthetic examples
  AGENTS.md               Shared coding-agent instructions
  CLAUDE.md               Points Claude Code to the same instructions
```

## Run the offline extraction experiment

With Java 21, run from the repository root on Windows:

```powershell
.\tools\run-extraction-experiment.cmd
```

Pass an optional output directory as the first argument.

On macOS/Linux:

```sh
sh tools/run-extraction-experiment.sh
```

The standalone CLI generates synthetic PDF/CSV fixtures, runs a deterministic
CSV baseline and fake PDF provider, and scores their output. It writes immutable
predictions, versioned labels, trace metadata, and evaluation JSONL under an
ignored output directory. It requires no database, Spring application, API key,
or network call. The fake provider tests the contract and scorer; its results do
not measure real model extraction quality or native bank-format support.

An opt-in [OpenAI PDF experiment](docs/openai-experiment.md) uses the same
contract and scorer with generated synthetic inputs. It requires a configured
API key, explicit model, and request/cost/time limits. Ordinary tests use only
a loopback mock API; live model quality remains unmeasured until a paid run.

## Run the product backend

Use Java 21 and PostgreSQL. Set `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and a stable
`MFA_ENCRYPTION_KEY` (Base64 encoding of 32 random bytes) in your shell. For
local HTTP only, explicitly set `SESSION_COOKIE_SECURE=false`; production
requires HTTPS and secure cookies. Then run from `backend`:

```powershell
.\mvnw.cmd spring-boot:run
```

Health is at `http://127.0.0.1:8080/api/v1/health/`. Stop with Ctrl+C. To run
unit and real PostgreSQL integration tests:

```powershell
.\mvnw.cmd verify
```

Verification needs Docker for Testcontainers, or the three
`TEST_DATABASE_*` variables for a disposable PostgreSQL test database. The
test database is separate from the application database. See the backend README
for Docker Compose, environment setup, and the current endpoint/service boundary.

See [the backend README](backend/README.md) and
[transition plan](docs/backend-transition.md) for implementation and data-transfer
boundaries. Next.js and the API contract remain the client direction.
See [security and client flow](docs/security.md) for CSRF bootstrap, MFA,
session management, and encryption-key requirements.

`.env.example` documents Spring Boot variables and is not automatically loaded.
Set process environment variables before starting the backend. Any existing
local legacy database is retained; copying its data is a separate workflow.
The retained SQLite file is ignored at `data/private/legacy-db.sqlite3`.
Dated reviews use the former `apps/backend`/`apps/web` paths; those snapshots
refer to the layout before the root-directory move.

See [the product brief](docs/product.md), [architecture](docs/architecture.md),
[features and pages](docs/features-and-pages.md),
[engineering conventions](docs/engineering-conventions.md),
[AI technology guide](docs/AI-Tech.md), and [roadmap](docs/roadmap.md) for the
design and phase sequence. [Next move](docs/next-move.md) is the concrete brief
and the source for current implementation status.
