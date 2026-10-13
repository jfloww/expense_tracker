# API plan

Only `GET /api/v1/health/` is implemented. Everything below is a proposed
contract for subsequent work, not an available endpoint.

Use `/api/v1/` as the versioned prefix. The server derives ownership from the
authenticated user. Responses use stable IDs, ISO dates, integer minor-unit
amounts, and explicit currencies. Never accept an owner ID as authorization.

| Method | Proposed path | Purpose |
| --- | --- | --- |
| POST | /auth/signup/ | Create a user |
| POST | /auth/login/ | Establish browser session |
| POST | /auth/logout/ | Revoke session |
| GET | /auth/me/ | Current user |
| GET, POST | /accounts/ | List and register own accounts |
| GET, PATCH, DELETE | /accounts/{id}/ | Manage own account |
| GET, POST | /statements/ | List own files; validate upload and create job |
| GET, DELETE | /statements/{id}/ | Status, provenance, and deletion |
| GET | /statements/{id}/rows/ | Paginated staged review rows |
| PATCH | /statement-rows/{id}/ | Correct or resolve own staged row |
| POST | /statements/{id}/confirm/ | Idempotent atomic confirmation |
| GET, PATCH, DELETE | /transactions/{id}/ | Inspect and manage own entry |
| GET | /transactions/ | Filtered, paginated own ledger |
| GET, POST | /categories/ | Own categories |
| GET, POST | /category-rules/ | Own explicit matching rules |
| POST | /category-rules/preview/ | Preview effects without committing |
| GET | /reports/monthly/ | Calculated totals with coverage |
| GET | /reports/spending-changes/ | Contributions and source IDs for two months |
| GET | /exports/transactions.csv | Export own confirmed ledger |
| DELETE | /auth/me/ | Delete account and associated records/files |

Report parameters include months, currency, and selected account IDs. Validate
every account's owner before constructing reporting DTOs. Read only the scoped
ledger queryset; the standalone reporting service is not an authorization layer.

Spending-change responses should include previous/current totals, delta,
category and merchant contributions, source transaction IDs for both periods,
coverage status, and warnings. A source ID becomes a user-facing link through
the authorized transaction endpoint. An optional AI summary must use these
facts rather than supply new totals.

Use field-level error codes for actionable validation failures. Return a generic
not-found response for inaccessible object IDs. Limit file size and row count,
paginate lists, and bound expensive operations. Confirmation needs a unique
source constraint plus atomic locking/state checks; an HTTP request retry must
not alter totals a second time.

Generate an OpenAPI description from real serializers/views when these routes
exist. Generate TypeScript client types from that source. Keep this plan separate
from generated contracts so proposed routes never appear implemented.
