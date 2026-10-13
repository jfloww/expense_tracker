# Backend transition to Java and Spring Boot

The user selected Java with Spring Boot on 2026-10-13. This replaces Django as
the target product backend. Next.js/TypeScript, household roles, PDF-first
extraction, CSV baseline, OpenAI-first experiments, and the feature/page plan
continue to apply. The first Java foundation slice is now implemented.

## Current and target code

| Location | Status and purpose |
| --- | --- |
| `apps/api` | Existing Django/Python reference: user-owned models, migration, health endpoint, reporting prototype and tests |
| `tools/demo_spending_changes.py` | Existing synthetic reporting demo; behavior reference for the Java port |
| `apps/backend` | Spring Boot 4.1.1 application: Flyway, identity/household/account services, public health, and PostgreSQL tests |
| `apps/web` | Planned Next.js client using the Java REST API |
| `apps/worker` | Planned launcher/process guidance; reuse Java backend modules for production jobs |

Existing Python code, migrations and databases remain intact. Java uses its own
database and Flyway migrations. The Maven Wrapper and run/test commands are now
available in [the backend README](../apps/backend/README.md). `.env.example`
documents separate Java and Django settings; neither app loads it automatically.

## Implementation baseline

- Java 21, Spring Boot 4.1.1, and checksum-pinned Maven 3.10.0 Wrapper.
- Spring MVC for REST controllers, Spring Security for session authentication
  and CSRF, Spring Data JPA for PostgreSQL persistence, Jakarta Bean Validation
  for request shapes/ranges, and Flyway for versioned schema changes.
- Feature packages containing controllers/DTOs, application/query services,
  domain rules and repositories. Use constructor injection; create integration
  interfaces only where a provider/storage boundary requires substitution.
- JUnit unit checks for pure calculations and PostgreSQL integration checks
  for persistence/authorization/locking. Testcontainers needs a supported
  container runtime; availability is checked during setup.

These are implementation defaults for this repository. The official structure
guide illustrates feature packages, and the requirements page identifies
supported runtime/build versions. [Spring Boot code structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html),
[Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).

Spring AI, an agent SDK,
MCP server, queue broker, or Modulith dependency is not required for the first
backend slice. A small Java provider interface and fake adapter are sufficient
for the first extraction experiment.

## Rules carried into Java

| Concern | Java implementation policy |
| --- | --- |
| Authorization | Current actor membership/role plus household-scoped object queries; a route ID never grants access |
| Writes | Authorized service operation, domain validation, database constraints and service transaction |
| Concurrency | Consistent household lock protocol; last-owner operations and background writes participate |
| DTO boundary | Explicit request/response types; never expose JPA entities or let transport coercion change meaning |
| Money | Positive `long` stored minor units; `BigInteger` report sums/deltas; canonical string JSON; no floating point |
| Dates | `LocalDate` posted/purchase dates, `YearMonth` report keys, `Instant` UTC event times |
| Imports | Source provenance, immutable predictions, versioned review labels and explicit atomic confirmation |
| Reporting | Confirmed expenses minus posted-month refunds; exclude payments/transfers/income/adjustments; account coverage remains explicit |
| Deletion | Tested ordered graph deletion with durable post-commit external cleanup; member departure preserves shared records |

Python's arbitrary-precision arithmetic cannot be replaced with unchecked Java
`long` addition. Port the synthetic reporting examples and their failure cases,
using exact aggregate arithmetic. Money input requires actual canonical JSON
strings: disable or reject Jackson's number-to-string coercion for these fields.

## New schema and existing data

Create a separate PostgreSQL database/schema for the Java backend. Household
ownership starts in the first Flyway migration, so a new Java installation
does not need an intermediate user-owned schema. Hibernate validates the schema;
it does not update or recreate it automatically.

Retain the old database while implementing the Java foundation. If there is
data to carry over, inspect it and prepare an explicit copy with dry-run output:

1. Audit related owners, constraints, supported currencies, ranges and source
   references. Fail on invalid links rather than guessing a household.
2. Map each legacy owner to a separate household and owner membership. Only an
   explicit later sharing action combines membership; never auto-merge ledgers.
3. Preserve stable IDs or a complete tested ID mapping, amounts, dates,
   descriptions, file metadata/hashes, review state and source relationships.
   Reconcile counts and per-currency totals plus linked records in both systems.
4. Define user identity and credential compatibility separately. Django hashes
   are not automatically accepted by a Spring password encoder; any real users
   need a vetted compatibility or reset/re-enrollment policy before cutover.
5. Use an idempotent, transactional copy and verify a two-owner/full-chain
   fixture. Keep a recoverable source snapshot and a documented rollback path.

Use a consistent source snapshot for validation and freeze source writes during
the final copy/cutover if existing data is transferred. Select one live write
authority at a time; never run both backends against the same live ledger or
allow the old source to change unnoticed after a validated copy. A rollback
after new Java writes needs an explicit reconciliation plan, not a switch back
to stale data. Executing a real cutover is a separate implementation task.

This policy does not authorize resetting a database, deleting existing data,
or migrating real credentials/files as part of a documentation edit.

## Build order and completion evidence

Spring Boot setup, health, household/membership/account schema and services are
implemented. Next add session/auth and account endpoints. Add the full
statement/review/ledger schema and deterministic reporting port before the
complete import workflow. See [next move](next-move.md) for acceptance criteria.

Keep `/api/v1/` and the household-prefixed planned routes, ISO date formats,
canonical string monetary fields, coverage and source references. Generate
OpenAPI/TypeScript types from implemented controllers/DTOs, not the old Python
models or this proposal. Pin a Boot-compatible OpenAPI tool when implementing it.

The first Java slice is verified by a reproducible Maven build, PostgreSQL
migrations/constraint tests, household/account service isolation and last-owner
concurrency checks. Full foundation completion additionally needs authenticated
HTTP/CSRF tests, full graph deletion/rollback and reporting parity as those
workflows are implemented. Passing the Python suite alone cannot verify this
replacement.

Historical dated reviews remain snapshots of the Django scaffold. Adopted
guidance lives in [architecture](architecture.md),
[engineering conventions](engineering-conventions.md), and [roadmap](roadmap.md).
