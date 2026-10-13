# Project review — 2026-10-13

Reviewed: commit `04e3f4b` ("first commit with structure"), plus the
uncommitted `dev.txt`.

## Checks run

Run from the repository root with Python 3.12.4 in `.venv`.

| Check | Result |
| --- | --- |
| `python -m unittest ... -p test_spending_changes.py` | 11 tests OK |
| `manage.py check` | No issues |
| `manage.py makemigrations --check --dry-run` | No changes |
| `manage.py test tests` | 23 tests OK (12 model/endpoint, 11 reporting) |
| `python tools/demo_spending_changes.py` | Aug $580.00 → Sep $880.00, +$300.00 |
| Deleting a user who has a statement (inside a rolled-back transaction) | `ProtectedError` on `Statement` (see issue 3) |

## What exists

- Django 5.2 LTS and DRF 3.16, with seven ledger models: Account, Category,
  Statement, ImportedTransaction, Transaction, CategoryRule and ProcessingJob.
- One endpoint: `GET /api/v1/health/`.
- [spending_changes.py](../apps/api/ledger/services/spending_changes.py): a
  deterministic "why did my spending change?" calculation, demonstrated only on
  synthetic data.
- Not built yet: authentication, uploads, statement parsers, the worker, the
  web UI, and any AI calls.

## Strengths to keep

1. **Money handling.** Amounts are positive integer minor units with an
   explicit currency. Database check constraints reject an expense recorded as
   a credit, or a refund or income recorded as a debit
   ([models.py:234-244](../apps/api/ledger/models.py#L234-L244)).
2. **Provenance and staging.** `ImportedTransaction` keeps the raw row data and
   the row and page numbers. `Transaction.source_row` is one-to-one with it.
   This already fits an AI extraction pipeline: model output goes into staging,
   and the user's accept or correct action becomes the label.
3. **Reporting.** Results are deterministic, category contributions add up
   exactly to the total change, and coverage is reported explicitly as
   complete, partial or unknown
   ([spending_changes.py:155](../apps/api/ledger/services/spending_changes.py#L155)).
4. **AGENTS.md** gives coding agents clear rules and the commands to verify
   their work.

## Issues, in priority order

### 1. Records are scoped per user, but the goal is a household (High)

Every model inherits `OwnedModel.owner → User`
([models.py:61-71](../apps/api/ledger/models.py#L61-L71)), and
[product.md](../docs/product.md) defers household sharing. Every queryset
filter, permission check and cross-user test written from now on would be
built on `owner`. Switching to a household after endpoints exist means
rewriting all of them, so decide this before building the endpoints (see
[open-decisions.md](open-decisions.md) #1).

If the household option is chosen, a sketch:

- Add `Household` and `Membership(user, household, role)`.
- Scope ledger records by household. `Transaction` gets an optional `paid_by`
  member, and staged and confirmed records get `created_by`.
- Ownership checks become "`request.user` is a member of the record's
  household". `ownership_errors()` compares households instead of users.

### 2. AI comes last in the roadmap (High)

In [roadmap.md](../docs/roadmap.md), milestones 2–3 hand-write one parser per
bank. Models are used only for rows the parsers can't resolve (milestone 3),
and agents only at milestone 5. Current multimodal models can read CSV files,
PDFs and receipt photos into a fixed schema, so AI extraction can be the main
import path. The existing safeguards then act as the validation layer:

- Schema-constrained model output goes into `ImportedTransaction` as staged
  rows.
- Code checks the results: amounts are integer cents, flow matches kind, dates
  fall inside the statement period, and rows add up to the statement totals
  where the statement has them.
- A person reviews the rows before anything is confirmed.

Keep one deterministic CSV parser anyway. It is the baseline that AI
extraction is compared against.

### 3. Deleting a user fails (Medium, verified)

Most foreign keys between ledger records use `on_delete=PROTECT` (for example
[models.py:124](../apps/api/ledger/models.py#L124)). Deleting a user who has a
statement raises `ProtectedError`. The architecture doc acknowledges that an
"ordered deletion service" is needed.

There are two options:

- **`models.RESTRICT`:** Django allows the delete when the referenced row is
  removed in the same cascade.
- **An explicit deletion service:** delete records in a defined order.

`RESTRICT` has not been tested here yet. Either way, add a test that deletes
the tenant with a full statement → staged row → transaction chain.

### 4. Ownership validation is opt-in (Medium, documented)

`clean()` checks that related records share an owner, but `save()` and
`bulk_create()` do not call it
([models.py:1-6](../apps/api/ledger/models.py#L1-L6)). The risk grows once an
AI pipeline writes staged rows in bulk. Route writes through one service per
model that always calls `full_clean()`, and write cross-tenant denial tests
for every endpoint (AGENTS.md already requires these tests).

### 5. More documentation than code (Low)

There are about 870 lines of docs, against about 660 lines of code and 300
lines of tests. That's reasonable for a foundation, but it risks planning
instead of building. Several docs describe late phases (MCP, the agent) in
detail and will need rewriting after the goal change anyway.

### Resolved since the first review

- **Version control:** the repo is now on git. `db.sqlite3` and `.venv` are
  correctly left out.

## Tasks for Codex once the decisions are made

1. Implement the tenancy model chosen in [open-decisions.md](open-decisions.md)
   #1, with its migration, the `ownership_errors()` update, and denial tests.
2. Fix tenant deletion (switch to `RESTRICT` or add a deletion service) and
   add the full-chain deletion test.
3. Add write services that always call `full_clean()`, and use them in all new
   code.
4. Rewrite `docs/roadmap.md`, `docs/product.md` and `docs/AI-Tech.md` to match
   the adopted plan.
