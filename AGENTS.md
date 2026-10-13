# Project guidance

Read README.md, docs/architecture.md, docs/engineering-conventions.md, and
docs/next-move.md before changing module boundaries. Read
docs/backend-transition.md for the Java backend switch. docs/roadmap.md records
the adopted learning sequence.

This is a public household expense tracker and long-term AI engineering project.
Target: shared household ledger with owner/member roles; private accounts and
settle-up later. The backend uses Java 21, Spring Boot 4.1.1,
Maven Wrapper, Spring Data JPA, and PostgreSQL in apps/backend. Its foundation
implements identity, household/account services, Flyway, and public health;
browser login and data endpoints remain pending. apps/api is the Django/Python
reporting reference; do not extend it as the product backend. Web uses Next.js;
mobile reuses the Spring Boot API later. Capital One, Chase, and Amex are
planned issuers. PDF extraction with OpenAI comes early, with a deterministic
CSV baseline and Claude comparison later. No issuer parser or AI call exists yet.

- Target authorization uses the actor's household membership and role. Never
  trust a client-supplied owner/household ID as authorization. Validate the scope
  of every related object, file, job, label, report, and retrieval result.
- The legacy scaffold's model validation is not complete API authorization. Future
  endpoints need same-household allowed and cross-household/revoked-member denial
  tests. Protect the last owner; preserve shared records when a member leaves.
- Organize the Spring Boot modular monolith by feature. Keep controllers thin;
  use authorized application services for writes, household-scoped query services
  for reads, Spring Data JPA repositories for persistence, and pure Java functions
  for reporting. External SDKs belong in adapters. Do not wrap Spring Data
  repositories in a generic repository framework.
- Keep request/response DTOs separate from JPA entities. Bean Validation checks
  boundary inputs; application services must also enforce domain rules and
  related-object scope for HTTP, worker, and other callers. Repositories and
  entity callbacks do not provide complete authorization.
- Own transaction boundaries in application services with @Transactional and
  explicit locking for membership and confirmation races. Keep slow network
  calls outside ledger transactions. Use Flyway for schema migrations; verify
  persistence behavior against PostgreSQL with Testcontainers.
- Shared writes acquire the household lock before current membership/role and
  related-object checks. Membership changes, deletion and worker writes use the
  same protocol; multi-household operations acquire locks in stable ID order.
- Use Spring Security server-side sessions, secure HttpOnly cookies, and CSRF
  protection for the first same-origin web client. Do not disable CSRF to make
  browser mutations easier. Authentication does not replace household checks.
- Organize Next.js by feature. Keep reusable web UI in components/ui, generated
  DTOs in packages/contracts, and pure date/money/error helpers in packages/shared.
  Shared modules must not import features, frameworks, secrets, or network code.
- Private responses bypass shared caches by default. Any future cache hit still
  requires current household membership/role checks before returning data.
- Use an explicit tested deletion service and restrictive source foreign keys.
  Do not add broad JPA cascade removal or orphan deletion without evidence from
  the complete related-data graph and rollback tests. Track external cleanup
  durably and run it after commit.
- Store money as integer minor units and an explicit currency. USD cents are the
  first supported interpretation. Do not use binary floats or silently add
  currencies. Store individual amounts as Java Long/PostgreSQL BIGINT with
  bounded inputs. Use BigInteger for report totals/deltas and checked long
  arithmetic for bounded operations; never allow silent overflow. AI may propose
  labels; code calculates totals.
- Planned normalized API money fields use canonical decimal integer strings,
  including report totals/deltas. Keep client DTOs as strings and format exactly;
  do not round-trip through JavaScript Number. Unresolved staged values may be null.
- Keep date-only YYYY-MM-DD and report months separate from UTC instants.
  Use java.time LocalDate, YearMonth, and Instant for those distinct meanings.
  Date-only display never shifts time zone. Timestamp display requires an explicit
  time zone; server/client formatting use the same explicit locale/settings.
- Preserve original descriptions and source statement/row/page references.
- Stage imports for review. Only confirmed ledger transactions enter reports.
- Treat identical transaction fingerprints as duplicate candidates, not proof.
  Two purchases at the same merchant on one day can both be legitimate.
- Apply refunds as negative spending in their posted month. Exclude income,
  transfers, card payments, and unresolved adjustments from spending totals.
- Do not claim a month is complete without coverage for all selected accounts.
- Category corrections create rules only when the user explicitly chooses that
  behavior. Never silently rewrite historical transactions.
- Keep real statements, secrets, and user transaction logs out of source control
  and test fixtures. Use synthetic data in demos and tests.
- Put optional model providers, RAG, and MCP behind service interfaces. Do not
  add live provider calls without request limits, ownership checks, and consent
  to process the relevant data.
- Trace and evaluate from the first model call. Ordinary traces contain versions,
  usage, timing, and validation codes; raw prompts/outputs belong only in explicit
  private retention. Preserve immutable predictions and versioned reviewed labels.
- Keep all representations/variants of one statement in one dataset split. Held-
  out test labels never enter prompts, retrieval context, training, or fine-tuning.
- Every AI phase includes a runnable result, simpler baseline, measured outcomes,
  and short experiment note. Synthetic offline work must run without an API key.

Verify the Spring Boot foundation from apps/backend with Java 21:

```powershell
.\mvnw.cmd verify
```

Maven verify executes unit and PostgreSQL integration tests through Failsafe.
Use Testcontainers with Docker, or all three TEST_DATABASE_URL,
TEST_DATABASE_USERNAME and TEST_DATABASE_PASSWORD variables against a disposable
PostgreSQL database. Every test JVM creates a fresh schema. No Docker/external
database means verification fails; never silently skip or substitute H2.

Existing Python reporting/reference checks from the workspace root:

```powershell
python -m unittest discover -s apps/api/tests -t apps/api -p test_spending_changes.py
```

Legacy Django scaffold checks, only when that reference code changes:

```powershell
.\.venv\Scripts\python.exe apps/api/manage.py check
.\.venv\Scripts\python.exe apps/api/manage.py makemigrations --check --dry-run
.\.venv\Scripts\python.exe apps/api/manage.py test tests
```

Report what works and what is reserved for later. A health endpoint and models
do not constitute a finished authenticated product. .env.example documents
separate Java and Django variables and is not automatically loaded. Keep the
Python demo as an offline behavior reference, not a second live ledger service.
