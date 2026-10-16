# Spring Boot backend

The product backend uses **Java 21, Spring Boot 4.1.1, Maven 3.10.0 Wrapper,
Spring Data JPA, PostgreSQL, and Flyway**. The Maven distribution is SHA-256
pinned; Spring Boot manages library versions. See [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).

The backend implements signup/login/logout, password changes, persistent JDBC
sessions, optional Google OpenID Connect and TOTP/recovery-code MFA, and
household/account HTTP routes.
Public health, CSRF bootstrap and authentication entry points are available;
all browser mutations remain CSRF-protected. Passwords use BCrypt with at least
12 Unicode code points and at most 72 UTF-8 bytes. Current enabled identity,
session version and household membership govern access; the last owner is protected.

See [security and client flow](../docs/security.md) for exact routes and
required configuration. Google is disabled unless explicitly configured. The
current implementation status and verification results live in
[next-move.md](../docs/next-move.md).

## Structure

```text
src/main/java/com/expensetracker/
  ExpenseTrackerApplication.java
  identity/       Authentication API, persistent sessions, passwords, throttling
  mfa/            TOTP, encrypted enrollment, hashed recovery codes
  households/     HTTP API, membership, roles, access locks, write/query services
  accounts/       HTTP API, commands/views, bank/card domain, write/query services
  experiments/extraction/  Standalone synthetic extraction/evaluation CLI
  shared/         Health HTTP controller, errors, security configuration
src/main/resources/
  application.yml
  db/migration/   Household foundation, authentication, MFA, JDBC sessions/limits
src/test/java/com/expensetracker/
  accounts/       Service validation and authorization-order unit tests
  foundation/     PostgreSQL service, constraint, race, rollback, HTTP tests
  support/        Isolated PostgreSQL test-schema configuration
```

Features contain application, domain, and persistence packages. Controllers
map authenticated identity and DTOs to services; repositories are internal
persistence APIs. Services own transactions, and every existing-household write
locks the household before checking current membership and related object scope.
Query services return views rather than exposing entities. There are no broad
JPA removal cascades. Hibernate validates the Flyway schema; Open EntityManager
in View is disabled. See [engineering conventions](../docs/engineering-conventions.md).

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
$env:MFA_ENCRYPTION_KEY = '<stable Base64 encoding of 32 random bytes>'
$env:SESSION_COOKIE_SECURE = 'false' # Local HTTP only; production requires true and HTTPS.
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
and validates mappings and the required MFA encryption key. Keep that key stable
across restarts and instances; losing it makes stored TOTP secrets undecryptable.
See [key setup](../docs/security.md). The server binds to 127.0.0.1 by default. Set
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
rollback, real HTTP CSRF/fixation/login/MFA behavior, persistent opaque sessions,
password/version revocation, and durable authentication-limit races. Tests
supply a synthetic MFA encryption key; no provider key or private statement is needed.

See [current verification evidence](../docs/next-move.md) for the last recorded
run and its limits. Dated implementation notes retain historical findings.

The Python/Django scaffold and reporting prototype were removed on 2026-10-13.
[The reporting contract](../docs/reporting-contract.md) preserves their
calculation requirements for the future Java implementation. The
[transition policy](../docs/backend-transition.md) preserves existing data.
See [the immediate task](../docs/next-move.md) and the root README for the
standalone extraction experiment commands.
