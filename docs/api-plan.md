# API plan

Only `GET /api/v1/health/` is implemented as an HTTP endpoint in the Java backend
at `apps/backend` (also retained in the legacy Django scaffold). The Java schema
and authorized household/account services exist; browser login and data routes
remain pending. Everything below remains a
planned contract for the shared household target. Build household scope into
the new PostgreSQL/Flyway schema rather than migrating Django models in place.
The framework change preserves these paths and wire rules; see
[backend transition](backend-transition.md).

Use `/api/v1/` as the versioned prefix. The server authenticates the user and
verifies membership and role in the household selected by the route. A supplied
household ID identifies the requested scope; it does not authorize access.
Never accept a client-supplied owner or creator ID as authorization. Responses
use stable IDs, ISO dates, decimal integer strings representing minor units,
and explicit currencies. This wire representation is planned; the health
endpoint has no money fields.

Normalized transaction amounts are positive strings (e.g. `"1250"` for USD
$12.50). Report totals/deltas may be signed or zero; unresolved staged amounts
may be null. Reject numeric JSON amounts and noncanonical strings, and enforce
input-length/domain bounds. Preserve raw issuer amount text separately. OpenAPI
must declare monetary wire fields as strings, not int64 numbers. Dates distinguish
`YYYY-MM-DD`, month keys `YYYY-MM`, and UTC event timestamps ending in `Z`.
See [engineering conventions](engineering-conventions.md) for formatter and
runtime-validation rules.

| Method | Planned path | Purpose |
| --- | --- | --- |
| POST | /auth/signup/ | Create a user |
| POST | /auth/login/ | Establish browser session |
| POST | /auth/logout/ | Revoke session |
| GET | /auth/me/ | Current user |
| DELETE | /auth/me/ | User offboarding under the last-owner policy; preserve shared ledger |
| GET, POST | /households/ | List joined households; create a household with server-assigned owner membership |
| GET, PATCH, DELETE | /households/{household_id}/ | View; owner updates or deletes household |
| GET, POST | /households/{household_id}/memberships/ | View members; owner adds through a future join/invitation workflow |
| PATCH, DELETE | /households/{household_id}/memberships/{id}/ | Owner changes role or removes a member |
| DELETE | /households/{household_id}/memberships/me/ | Leave household subject to last-owner protection |

All paths in the following table are also planned. They are relative to
`/api/v1/households/{household_id}/` and require verified membership. Both owner
and member roles may read and write the shared ledger; viewer is deferred.

| Method | Planned household-relative path | Purpose |
| --- | --- | --- |
| GET, POST | accounts/ | List and register shared accounts |
| GET, PATCH, DELETE | accounts/{id}/ | Inspect/update account; delete only when protected references permit |
| GET, POST | statements/ | List private files; validate upload and create extraction job |
| GET, DELETE | statements/{id}/ | Status, provenance, and service-controlled deletion |
| GET | statements/{id}/rows/ | Paginated staged review rows |
| GET | statements/{id}/download/ | Authorized short-lived access to private source |
| PATCH | statement-rows/{id}/ | Correct or resolve a staged row |
| POST | statements/{id}/confirm/ | Idempotent atomic confirmation |
| GET, PATCH, DELETE | transactions/{id}/ | Inspect and manage shared entry |
| GET | transactions/ | Filtered, paginated shared ledger |
| GET, POST | categories/ | Household categories |
| GET, POST | category-rules/ | Explicit household matching rules |
| POST | category-rules/preview/ | Preview effects without committing |
| GET | reports/monthly/ | Calculated totals with coverage |
| GET | reports/spending-changes/ | Contributions and source IDs for two months |
| GET | exports/transactions.csv | Export household's confirmed ledger |

| Operation | Owner | Member |
| --- | --- | --- |
| Ledger, imports, review, rules, reports, files and export | Allowed | Allowed |
| Manage members and roles; update/delete household | Allowed | Denied |
| Leave household | Allowed if another owner remains | Allowed |

Membership removal does not remove shared transactions. Role changes, member
removal, departure, and user offboarding must not leave a household without an
owner. Enforce this inside a transaction with a household lock. Optional
`created_by` references are audit fields, can be nulled on user offboarding, and
never supply permission to a former member.

Private accounts and settle-up between members are deferred. Signup, recovery,
invitation delivery, and a complete browser auth flow are not prerequisites for
the first synthetic CLI extraction evaluation. Network data endpoints must
still have authenticated access and cross-household denial tests before they
are complete.

Report parameters include months, currency, and selected account IDs. Validate
every account's household before constructing reporting DTOs. Read only the scoped
ledger through household-scoped Spring Data JPA repositories/query services;
the pure reporting service is not an authorization layer.

Spending-change responses should include previous/current totals, delta,
category and merchant contributions, source transaction IDs for both periods,
coverage status, and warnings. A source ID becomes a user-facing link through
the authorized transaction endpoint. An optional AI summary must use these
facts rather than supply new totals.

Write endpoints must call validated application services: authorize the actor,
verify all foreign-key references within the selected household, enforce Bean
Validation and domain invariants, and perform atomic state changes within
`@Transactional` application services. Spring MVC controllers stay thin;
validated request DTOs are separate from JPA entities. Do not bypass authorized
services through direct repository saves, bulk SQL, or entity serialization.
Spring Security provides browser session authentication and CSRF protection;
it does not replace per-operation household checks. Household scoping applies to
uploaded files, jobs, model runs and retrieved examples as well as ledger rows.
Test nonmember access and cross-household object substitutions even when the
caller belongs to both households.

Use field-level error codes for actionable validation failures. Return a generic
not-found response for inaccessible object IDs. Limit file size and row count,
paginate lists, and bound expensive operations. Confirmation needs a unique
source constraint plus atomic locking/state checks; an HTTP request retry must
not alter totals a second time. Never create category rules implicitly from a
correction or confirm extraction output without human review.

Household deletion uses the ordered service described in
[architecture](architecture.md), with foreign-key constraints and explicit
relationship checks rather than blanket JPA cascade removal. Database deletion
is atomic. Private source and provider-file
cleanup runs after commit using durable cleanup tasks that survive household
deletion; track failures and retry them. Test a complete related chain and prove
another household survives unchanged. A statement deletion service must define
what happens to its confirmed ledger references before this endpoint is built.
Verify full-chain deletion/rollback, session/CSRF behavior, idempotency, and
cross-household/revoked-member denial with PostgreSQL Testcontainers integration
tests; unit tests alone do not prove database locking or constraint behavior.

The import target begins with PDF extraction through an OpenAI service adapter,
then Claude for comparison, with metrics and privacy-safe traces from the first
call. A deterministic CSV parser provides the baseline; receipts are later.
The proposed routes do not authorize live provider calls. Provider processing
needs explicit consent for the relevant data, validated household scope, and
request/usage limits. See [AI-Tech](AI-Tech.md) and [the next build task](next-move.md).

Generate an OpenAPI description from implemented Spring MVC controllers and
DTOs when these routes exist. Generate TypeScript client types from that source.
Keep this plan separate
from generated contracts so proposed routes never appear implemented.
