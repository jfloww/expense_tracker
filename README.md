# Expense Tracker

A statement-based expense tracker for households with accounts across several
banks, built as a long-term AI engineering project. Upload statements, review
extracted transactions, and understand where shared spending changed. The
target product uses household memberships and roles, starts on the web, and
adds a mobile app using the same API later. The backend uses **Java
Spring Boot**. Its first slice implements identity, household roles, and account
services. The Django scaffold and Python reporting prototype were removed on
2026-10-13; their source remains in Git history.

Initial issuer targets: **Capital One, Chase, and Amex**. Their actual export
formats still need to be confirmed; no bank-specific parser is implemented.

## What exists

- A Java 21 / Spring Boot 4.1.1 backend with Maven Wrapper and PostgreSQL/Flyway.
- Identity registration and authorized household/account application services,
  household locks, last-owner protection, and PostgreSQL integration tests.
- A public `GET /api/v1/health/` in Spring Boot; other requests require authentication.
- A language-neutral reporting contract with synthetic spending-change examples
  for the future Java reporting implementation.
- A documented import pipeline, API plan, feature options, and implementation
  milestones.
- Reserved web, mobile, worker, shared-contract, and shared-utility directories.

Login/signup, uploads, bank parsers, PDF extraction, review screens, dashboards,
background jobs, AI calls, authenticated data endpoints, and deployment are planned
work. Identity registration is currently an internal service, not a signup route.
Deterministic reporting is specified but has not been implemented in Java.

Adopted on 2026-10-13: shared household ledger; PDF extraction with a CSV
baseline; OpenAI first and Claude comparison later. Evaluation and tracing start
with the first AI feature. Private accounts, receipts, and settle-up come later.

## Structure

```text
expense-tracker/
  apps/
    backend/              Java Spring Boot product backend foundation
    web/                  Next.js + TypeScript web client (planned)
    mobile/               Expo mobile client (later)
    worker/               Import worker entry point (planned)
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
    roadmap.md            Milestones with acceptance criteria
    next-move.md          Adopted decisions and immediate coding task
    reporting-contract.md Required reporting behavior and synthetic examples
  AGENTS.md               Shared coding-agent instructions
  CLAUDE.md               Points Claude Code to the same instructions
```

## Run the product backend

Use Java 21 and PostgreSQL. Set `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in your shell,
then run from `apps/backend`:

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

See [the backend README](apps/backend/README.md) and
[transition plan](docs/backend-transition.md) for implementation and data-transfer
boundaries. Next.js and the API contract remain the client direction.

`.env.example` documents Spring Boot variables and is not automatically loaded.
Set process environment variables before starting the backend. Any existing
local legacy database is retained; copying its data is a separate workflow.

## Next milestone

Implement Spring Security session login/logout/current-user and authorized
household/account HTTP endpoints on the tested services. Extend the ledger graph,
reporting, and safe deletion as separate workflows. Use
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
