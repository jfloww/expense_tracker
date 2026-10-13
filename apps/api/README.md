# API foundation

Python 3.12, Django 5.2 LTS, and Django REST framework 3.16. Dependency ranges
stay within these compatible release series. [Django Python support](https://docs.djangoproject.com/en/5.2/releases/5.2/),
[DRF 3.16 compatibility](https://www.django-rest-framework.org/community/3.16-announcement/).

## Implemented

- Django configuration, management entry point, and initial ledger migration.
- `GET /api/v1/health/`: public process liveness; it does not check database readiness.
- Owner-linked accounts, categories, statements, staged import rows, normalized
  transactions, category-rule definitions, and processing-job records.
- Model validation for common relationship ownership errors, source-account
  consistency, review prerequisites, and currency/flow consistency.
- Database constraints for positive amounts, valid expense/refund/income flow,
  unique staged row numbers per statement, and identical source-file hashes
  within the same owner and account.
- A deterministic spending-change service in `ledger/services/spending_changes.py`.
  It consumes explicit reporting data; no ORM adapter or HTTP report endpoint exists.

No signup/login routes, upload handler, live bank connection, issuer parser, PDF
extraction, background worker, import/confirmation service, report endpoint, or
AI provider is implemented. Issuer choices identify intended account sources;
they do not mean Capital One, Chase, or Amex export formats are supported.

## Local setup

Run from the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r apps/api/requirements.txt
.\.venv\Scripts\python.exe apps/api/manage.py migrate
.\.venv\Scripts\python.exe apps/api/manage.py runserver 127.0.0.1:8000
```

The default development database is SQLite at `apps/api/db.sqlite3`. PostgreSQL
is the intended public-product database; PostgreSQL migrations and deployment
configuration still require verification before release.

Settings read process environment variables. `.env` files are not loaded automatically.

| Variable | Meaning |
| --- | --- |
| `DJANGO_DEBUG` | `true` by default for local bootstrap; set `false` for deployment |
| `DJANGO_SECRET_KEY` | Required when debug is false; local development uses a fixed fallback |
| `DJANGO_ALLOWED_HOSTS` | Comma-separated hosts; local default `localhost,127.0.0.1,testserver` |
| `DATABASE_URL` | Optional `postgresql://user:password@host:5432/database?sslmode=require` |
| `POSTGRES_DB` | Alternative PostgreSQL configuration; selecting it enables PostgreSQL |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | Credentials for the alternative configuration |
| `POSTGRES_HOST`, `POSTGRES_PORT` | Defaults `localhost`, `5432` |
| `POSTGRES_SSLMODE` | Optional PostgreSQL SSL mode for the alternative configuration |

`DATABASE_URL` takes precedence over the separate PostgreSQL variables. It only
accepts `postgres`/`postgresql` schemes and the `sslmode` query option. With debug
false, settings require both an explicit secret and PostgreSQL configuration.
HTTPS, secure cookies, session/auth routes, upload limits, and production hosting
remain deployment work.

## Staging and money contract

Statement statuses record the intended lifecycle: `uploaded`, `parsing`,
`needs_review`, `reviewed`, `imported`, `failed`. The scaffold does not run that
workflow or enforce every state transition. Reviewed/imported states require
their corresponding timestamps during model validation.

`ImportedTransaction` preserves a statement relationship, row number, optional
PDF page, original description, raw source data, and normalization errors. Pending
rows may be incomplete. Accepting a row requires a posted date, positive amount,
currency, flow, kind, and no unresolved normalization errors. A fingerprint is
a duplicate candidate; it is intentionally not unique across overlapping statements.

`Transaction` references its source row with an optional one-to-one relationship
and defaults `confirmed` to false. Manual entries may have no source row. A
confirmed sourced entry requires an accepted row and a reviewed/imported
statement during validation. Atomic promotion from staged rows is future work.

Store positive integer `amount_minor` values with an explicit uppercase currency
and `debit`/`credit` flow. Initial USD amounts mean cents; currency validation checks
the three-letter syntax, not an exchange rate or minor-unit exponent table. An
account has one currency, and normalized rows must match it. Expense is debit;
refund and income are credit. Transfers, card payments, and adjustments may use
either flow. Fees use kind `expense` and an appropriate category. Only confirmed
expenses minus confirmed refunds contribute to spending reports.

## Ownership and checks

Every ledger model has an owner foreign key. Related owners and source accounts
are checked by `full_clean()`. Django does **not** call `full_clean()` automatically
on `save()`, and cross-owner relationships are **not** enforced by database row
security. The health endpoint returns no ledger data. Every future data endpoint
must scope its queryset to the authenticated user, reject client-supplied owner
IDs, validate nested relationships, and test cross-user access denial.

```powershell
.\.venv\Scripts\python.exe apps/api/manage.py check
.\.venv\Scripts\python.exe apps/api/manage.py makemigrations --check --dry-run
.\.venv\Scripts\python.exe apps/api/manage.py test tests
```

Tests use synthetic records. They verify owner/account mismatches, staged review
prerequisites, duplicate-file scope, amount/flow database constraints, and the
public read-only health endpoint. They are foundation checks, not an end-to-end
authenticated import product test.
