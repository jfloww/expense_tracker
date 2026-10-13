# Expense Tracker

A statement-based expense tracker for people with accounts across several
banks. Upload statements, review extracted transactions, and understand where
spending changed. The intended product supports separate user accounts, starts
on the web, and adds a mobile app using the same API later.

Initial issuer targets: **Capital One, Chase, and Amex**. Their actual export
formats still need to be confirmed; no bank-specific parser is implemented.

## What exists

- A Django REST backend foundation, ledger models, and initial database migration.
- A health endpoint at `GET /api/v1/health/`.
- A deterministic spending-change service and a synthetic command-line demo.
- A documented import pipeline, API plan, feature options, and implementation
  milestones.
- Reserved web, mobile, worker, and shared-contract directories.

Login/signup, uploads, bank parsers, PDF extraction, review screens, dashboards,
background jobs, AI calls, and deployment are planned work. The reporting demo
runs on synthetic data; it is not connected to uploaded statements or the API.

## Structure

```text
expense-tracker/
  apps/
    api/                  Django REST, ledger models, reporting service
    web/                  Next.js + TypeScript web client (planned)
    mobile/               Expo mobile client (later)
    worker/               Import worker entry point (planned)
  packages/
    contracts/            API conventions; generated client types later
  docs/
    product.md            Product scope and distinctive feature options
    architecture.md       Boundaries, data model, and AI/RAG/MCP placement
    AI-Tech.md            Harness, RAG, MCP, and planned AI workflows
    imports.md            Issuer adapters, validation, review, deduplication
    api-plan.md           Intended authenticated API
    roadmap.md            Milestones with acceptance criteria
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

## Run the backend locally

From the workspace root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r apps/api/requirements.txt
.\.venv\Scripts\python.exe apps/api/manage.py migrate
.\.venv\Scripts\python.exe apps/api/manage.py runserver 127.0.0.1:8000
```

Open `http://127.0.0.1:8000/api/v1/health/`. Local development defaults to
SQLite, so a PostgreSQL installation is not needed for this first check.

`.env.example` documents settings; it is not automatically loaded. Set process
environment variables before starting Django. PostgreSQL is the intended
deployment database; details are in [the API README](apps/api/README.md).

Backend verification:

```powershell
.\.venv\Scripts\python.exe apps/api/manage.py check
.\.venv\Scripts\python.exe apps/api/manage.py makemigrations --check --dry-run
.\.venv\Scripts\python.exe apps/api/manage.py test tests
```

## Recommended first release

Make the complete path work for one verified CSV export format: authenticate,
register an account, import, review, confirm, and view a correct monthly total.
Then expand to the other issuer formats and add the spending-change explanation.

See [the product brief](docs/product.md), [architecture](docs/architecture.md),
[AI technology guide](docs/AI-Tech.md), and [roadmap](docs/roadmap.md) for the
decisions and remaining work.
