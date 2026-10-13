# Expense Tracker

A statement-based expense tracker for households with accounts across several
banks, built as a long-term AI engineering project. Upload statements, review
extracted transactions, and understand where shared spending changed. The
target product uses household memberships and roles, starts on the web, and
adds a mobile app using the same API later. The backend target is now **Java
Spring Boot**. Its first slice implements identity, household roles, and account
services; the existing Django models remain an individual-user-owned reference.

Initial issuer targets: **Capital One, Chase, and Amex**. Their actual export
formats still need to be confirmed; no bank-specific parser is implemented.

## What exists

- A Java 21 / Spring Boot 4.1.1 backend with Maven Wrapper and PostgreSQL/Flyway.
- Identity registration and authorized household/account application services,
  household locks, last-owner protection, and PostgreSQL integration tests.
- A public `GET /api/v1/health/` in Spring Boot; other requests require authentication.
- A legacy Django REST scaffold, ledger models, and initial database migration.
- A deterministic Python spending-change service and synthetic command-line demo.
- A documented import pipeline, API plan, feature options, and implementation
  milestones.
- Reserved web, mobile, worker, shared-contract, and shared-utility directories.

Login/signup, uploads, bank parsers, PDF extraction, review screens, dashboards,
background jobs, AI calls, authenticated data endpoints, and deployment are planned
work. Identity registration is currently an internal service, not a signup route.
The reporting demo runs on synthetic data; it is not connected to uploaded
statements or the API.

Adopted on 2026-10-13: shared household ledger; PDF extraction with a CSV
baseline; OpenAI first and Claude comparison later. Evaluation and tracing start
with the first AI feature. Private accounts, receipts, and settle-up come later.

## Structure

```text
expense-tracker/
  apps/
    backend/              Java Spring Boot product backend foundation
    api/                  Legacy Django scaffold and Python reporting reference
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
    backend-transition.md  Django reference to Spring Boot implementation plan
    engineering-conventions.md  Patterns, shared code, date/money contracts
    AI-Tech.md            Harness, RAG, MCP, and planned AI workflows
    imports.md            Issuer adapters, validation, review, deduplication
    api-plan.md           Intended authenticated API
    roadmap.md            Milestones with acceptance criteria
    next-move.md          Adopted decisions and immediate coding task
  tools/
    demo_spending_changes.py
  AGENTS.md               Shared coding-agent instructions
  CLAUDE.md               Points Claude Code to the same instructions
```

## Try the feature prototype

No dependencies or API key are required:

```powershell
python tools/demo_spending_changes.py
python -m unittest discover -s apps/api/tests -t apps/api -p test_spending_changes.py
```

The demo compares August and September 2026. It calculates a $300 increase,
then shows category and merchant contributions with their source transaction
IDs. It accounts for refunds and excludes a card payment. Amounts are computed
in integer cents.

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

## Run the legacy Django reference locally

From the workspace root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r apps/api/requirements.txt
.\.venv\Scripts\python.exe apps/api/manage.py migrate
.\.venv\Scripts\python.exe apps/api/manage.py runserver 127.0.0.1:8000
```

Open `http://127.0.0.1:8000/api/v1/health/`. Local development defaults to
SQLite, so a PostgreSQL installation is not needed for this first check.

`.env.example` documents separate Spring Boot and legacy Django variables; it
is not automatically loaded. Set process environment variables before starting
either application. Details are in [the legacy API README](apps/api/README.md).

Legacy scaffold verification:

```powershell
.\.venv\Scripts\python.exe apps/api/manage.py check
.\.venv\Scripts\python.exe apps/api/manage.py makemigrations --check --dry-run
.\.venv\Scripts\python.exe apps/api/manage.py test tests
```

## Next milestone

Implement Spring Security session login/logout/current-user and authorized
household/account HTTP endpoints on the tested services. Extend the ledger graph,
reporting parity, and safe deletion as separate workflows. Keep
the Python reporting demo as an offline behavior reference. In parallel, a
synthetic CLI can establish
the extraction contract, CSV baseline, and evaluation scoring without live AI.
Then build PDF upload → OpenAI extraction → review/correction → versioned
labels → evaluation score → confirmed monthly reporting.

See [the product brief](docs/product.md), [architecture](docs/architecture.md),
[features and pages](docs/features-and-pages.md),
[engineering conventions](docs/engineering-conventions.md),
[AI technology guide](docs/AI-Tech.md), and [roadmap](docs/roadmap.md) for the
decisions and remaining work. [Next move](docs/next-move.md) is the concrete
brief for the next implementation task.
