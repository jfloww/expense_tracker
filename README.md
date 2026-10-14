# Expense Tracker

A statement-based expense tracker for households with accounts across several
banks, built as a long-term AI engineering project. Upload statements, review
extracted transactions, and understand where shared spending changed. The
target product uses household memberships and roles, starts on the web, and
adds a mobile app using the same API later. The backend uses **Java
Spring Boot**. It implements browser authentication, optional Google login/MFA, household
roles, and shared account APIs. The Django scaffold and Python reporting prototype were removed on
2026-10-13; their source remains in Git history.

Initial issuer targets: **Capital One, Chase, and Amex**. Their actual export
formats still need to be confirmed; no bank-specific parser is implemented.

## What exists

- A Java 21 / Spring Boot 4.1.1 backend with Maven Wrapper and PostgreSQL/Flyway.
- Identity registration and authorized household/account application services,
  household locks, last-owner protection, and PostgreSQL integration tests.
- CSRF-protected signup/login/logout, password changes, persistent sessions,
  TOTP/recovery-code MFA, optional Google login/linking, and authorized household/account HTTP endpoints.
- A public `GET /api/v1/health/`; signup, login and CSRF bootstrap are also public.
- A language-neutral reporting contract with synthetic spending-change examples
  for the future Java reporting implementation.
- A documented import pipeline, API plan, feature options, and implementation
  milestones.
- Reserved web, mobile, worker, shared-contract, and shared-utility directories.

The web client, local-signup email verification/invitations/recovery, uploads, bank parsers,
PDF extraction, review screens, dashboards, background jobs, AI calls, and
deployment remain planned. Reporting and full ledger deletion are pending;
authenticated account APIs do not constitute a finished expense tracker.

Adopted on 2026-10-13: shared household ledger; PDF extraction with a CSV
baseline; OpenAI first and Claude comparison later. Evaluation and tracing start
with the first AI feature. Private accounts, receipts, and settle-up come later.

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
    backend-transition.md  Java transition status and legacy data-transfer policy
    engineering-conventions.md  Patterns, shared code, date/money contracts
    AI-Tech.md            Harness, RAG, MCP, and planned AI workflows
    imports.md            Issuer adapters, validation, review, deduplication
    api-plan.md           Intended authenticated API
    security.md           Browser login, MFA, sessions, and local setup
    roadmap.md            Milestones with acceptance criteria
    next-move.md          Adopted decisions and immediate coding task
    reporting-contract.md Required reporting behavior and synthetic examples
  AGENTS.md               Shared coding-agent instructions
  CLAUDE.md               Points Claude Code to the same instructions
```

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

## Next milestone

Extend the ledger graph, reporting, and safe deletion, and build the first
Next.js workflow against the implemented authentication/account APIs. Use
[the reporting contract](docs/reporting-contract.md) for exact totals, refunds,
source references, and coverage requirements. In parallel, a synthetic Java CLI
can establish the extraction contract, CSV baseline, and evaluation scoring
without live AI.
Then build PDF upload → OpenAI extraction → review/correction → versioned
labels → evaluation score → confirmed monthly reporting.

See [the product brief](docs/product.md), [architecture](docs/architecture.md),
[features and pages](docs/features-and-pages.md),
[engineering conventions](docs/engineering-conventions.md),
[AI technology guide](docs/AI-Tech.md), and [roadmap](docs/roadmap.md) for the
decisions and remaining work. [Next move](docs/next-move.md) is the concrete
brief for the next implementation task.
