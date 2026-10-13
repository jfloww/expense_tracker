# Spring Boot backend

The product backend uses **Java 21, Spring Boot 4.1.1, Maven 3.10.0 Wrapper,
Spring Data JPA, PostgreSQL, and Flyway**. The Maven distribution is SHA-256
pinned; Spring Boot manages library versions. See [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).

The first slice implements internal identity registration, household creation
and role/departure services, shared bank/card account services, and a public
`GET /api/v1/health/`. Registration uses BCrypt with an explicit UTF-8 byte
limit. Current enabled identity and membership are checked on each operation;
owners manage membership and the last owner is protected.

Browser signup/login/logout and household/account HTTP routes are **next work**.
Only health is publicly accessible. Other requests receive JSON security errors;
CSRF stays enabled, with secure HttpOnly session-cookie defaults. The credential
adapter prevents a generated framework user; it does not provide a login route.
Statement imports, reports, full household deletion, and AI calls are pending.

## Structure

```text
src/main/java/com/expensetracker/
  ExpenseTrackerApplication.java
  identity/       User, registration, identity access, credential lookup
  households/     Membership, roles, access locks, write/query services
  accounts/       Commands/views, bank/card domain, write/query services
  shared/         Health HTTP controller, errors, security configuration
src/main/resources/
  application.yml
  db/migration/V1__household_foundation.sql
src/test/java/com/expensetracker/
  accounts/       Service validation and authorization-order unit tests
  foundation/     PostgreSQL service, constraint, race, rollback, HTTP tests
  support/        Isolated PostgreSQL test-schema configuration
```

Features contain application, domain, and persistence packages. Controllers
will map authenticated identity and DTOs to services; repositories are internal
persistence APIs. Services own transactions, and every existing-household write
locks the household before checking current membership and related object scope.
Query services return views rather than exposing entities. There are no broad
JPA removal cascades. Hibernate validates the Flyway schema; Open EntityManager
in View is disabled. See [engineering conventions](../../docs/engineering-conventions.md).

Capital One, Chase, Amex, and Other are supported account issuer values;
this is account metadata, not statement parsing. USD is the first currency.
Only optional last four digits are stored, never a complete account number.

## Run locally

Install/use Java 21 and a separate PostgreSQL database. The root `.env.example`
lists environment variables but is not automatically loaded by Spring Boot.
From this directory, set process variables (replace the password):

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/expense_tracker_java'
$env:SPRING_DATASOURCE_USERNAME = 'expense_tracker'
$env:SPRING_DATASOURCE_PASSWORD = '<your local database password>'
```

With Docker installed, the included Compose file can create that development
database. PostgreSQL listens on loopback port 5433; a named volume retains data:

```powershell
docker compose up -d postgres
```

Without Docker, provision a PostgreSQL database/user separately and set the
matching connection variables. Use a dedicated application database; removing
legacy source code does not authorize changing or deleting existing databases.
Then start the application:

```powershell
.\mvnw.cmd spring-boot:run
```

Open `http://127.0.0.1:8080/api/v1/health/`:

```json
{"status":"ok","service":"expense-tracker"}
```

Health reports process liveness, not database readiness. Startup applies Flyway
and validates mappings. The server binds to 127.0.0.1 by default. Set
`SERVER_ADDRESS`/`SERVER_PORT` explicitly for another deployment. Keep
`SESSION_COOKIE_SECURE=true` for HTTPS; false is only for local HTTP development.

This workspace has a portable Java 21 runtime under the ignored `.tools` folder.
To use it from the repository root without changing system Java:

```powershell
$env:JAVA_HOME = (Get-ChildItem '.tools/jdk-21' -Directory | Select-Object -First 1).FullName
```

On a fresh clone, install Java 21 or point JAVA_HOME at your own Java 21 runtime.

## Verify

From this directory with Java 21:

```powershell
.\mvnw.cmd verify
```

Surefire runs unit tests; Failsafe runs every `*IT` against actual PostgreSQL.
By default, Testcontainers starts PostgreSQL 16 and needs a working Docker
runtime. Alternatively, set all three variables against a **disposable test
database**, separate from application data:

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55433/expense_tracker_test'
$env:TEST_DATABASE_USERNAME = 'expense_tracker_test'
$env:TEST_DATABASE_PASSWORD = '<your test database password>'
.\mvnw.cmd verify
```

The test role needs schema-creation permissions. Each test JVM creates a random
schema and migrates it; cleanup deletes only fixture rows in that schema. An
external test database retains these schemas until you clean it separately.
No configured PostgreSQL/Docker runtime means verification fails rather than
skipping database checks. `mvnw.cmd test` runs only unit tests and is not full
verification. On macOS/Linux, use `sh ./mvnw verify`.

Checks cover scoped/revoked/disabled access, account validation, unique and
restrictive database constraints, last-owner races, observed row-lock blocking,
rollback after a second-record failure, and real HTTP health/401/CSRF403 behavior.
All fixtures are synthetic; no provider key or private statement is needed.

Verified on 2026-10-13 with Java 21 and the machine's PostgreSQL 16.4 binaries:
`verify` passed 22 unit and 31 integration tests, with no skips. An isolated
workspace test cluster was used and stopped afterward. Docker Compose and
Testcontainers execution were not exercised on this machine because Docker
is not installed.

The Python/Django scaffold and reporting prototype were removed on 2026-10-13.
[The reporting contract](../../docs/reporting-contract.md) preserves their
calculation requirements for the future Java implementation. The
[transition policy](../../docs/backend-transition.md) preserves existing data.
The next slice is [session authentication and the account API](../../docs/next-move.md).
