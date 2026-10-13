# Backend foundation review (Phase 0A.1) — 2026-10-13

Reviewed: uncommitted working tree on `main`, on top of `04e3f4b`. That covers
`apps/backend/` (Spring Boot 4.1.1, Java 21), the rewritten docs and AGENTS.md,
and [codex/2026-10-13-next-move-review.md](../codex/2026-10-13-next-move-review.md).

## Verification (rerun by Claude)

`mvnw verify` was run against the disposable PostgreSQL 16.4 test cluster in
`.tools/`, using the `TEST_DATABASE_*` variables. The cluster was stopped
afterwards.

| Suite | Result |
| --- | --- |
| Unit tests (Surefire) | 22 run, 0 failed, 0 skipped |
| PostgreSQL integration tests (Failsafe) | 31 run, 0 failed, 0 skipped |
| Build | `BUILD SUCCESS` |

This matches the result reported in [next-move.md](../docs/next-move.md).
Docker/Testcontainers was not exercised because Docker is not installed.

## Verdict

The slice does what it set out to do. It is small, layered as the conventions
describe, and tested against real PostgreSQL, including a lock wait observed in
PostgreSQL itself (`HouseholdWriteLockIT`) and a last-owner race. Nothing found
blocks building on it. The findings below are mismatches with the documented
API contract, and they should be fixed **before Phase 0B adds controllers**,
because the controllers and generated TypeScript types would lock them in.

Codex's reply to my first review was correct: the
PROTECT→RESTRICT idea was untested, and an ordered deletion service is the
better-supported approach.

## Findings, most severe first

### 1. Denied household access returns 403, but the API plan says not-found, and writes reveal whether a household exists (Medium)

[api-plan.md:107](../docs/api-plan.md#L107) says: "Return a generic not-found
response for inaccessible object IDs." The code does this:

| Call | Household doesn't exist | Exists, caller not a member |
| --- | --- | --- |
| Read (`requireMember`) | 403 `forbidden` | 403 `forbidden` |
| Write (`lockAndRequire*`) | **404** `not_found` (the lock lookup fails first) | **403** `forbidden` |

For writes, the different status codes tell a non-member whether a household
ID exists ([HouseholdAccess.java:51-60](../apps/backend/src/main/java/com/expensetracker/households/application/HouseholdAccess.java#L51-L60)).
Several tests assert the 403 behavior:
[AccountServicesIT.java:51-58](../apps/backend/src/test/java/com/expensetracker/foundation/AccountServicesIT.java#L51-L58)
and [HouseholdServicesIT.java:34-40](../apps/backend/src/test/java/com/expensetracker/foundation/HouseholdServicesIT.java#L34-L40).
Account-level substitution already returns 404
([AccountServicesIT.java:98](../apps/backend/src/test/java/com/expensetracker/foundation/AccountServicesIT.java#L98)),
so the two levels are also inconsistent with each other.

**Fix:** in `HouseholdAccess`, raise `not_found` for both "missing" and "not
a member". Keep `forbidden` only for a member who lacks the needed role (a
member attempting an owner action). Update the tests to match, and add a test
for a write to an unknown household ID. Alternatively, change api-plan.md on
purpose; either way, the code and the docs must agree.

### 2. JSON leaves out null fields, but the contract says nullable fields are present as `null` (Medium, before 0B)

[application.yml:15](../apps/backend/src/main/resources/application.yml#L15)
sets `spring.jackson.default-property-inclusion: non_null`. This is a real
Boot 4.1.1 property, present in the `spring-boot-jackson` metadata, so it takes
effect. The docs, however, describe fields that are present with a `null`
value: unresolved staged amounts
([api-plan.md:21-22](../docs/api-plan.md#L21-L22)), and OpenAPI declaring
"appropriate patterns/nullability"
([engineering-conventions.md:254-261](../docs/engineering-conventions.md#L254-L261)).
`AccountView.lastFour` already disappears from the JSON when it's empty.

Leaving a field out and sending `null` produce different generated TypeScript
types (`lastFour?: string` vs. `lastFour: string | null`), and the web client
would need to handle both. **Fix:** remove the setting so nulls are serialized
explicitly. If omission is wanted, document it as a rule instead.

### 3. A PATCH without `active` would deactivate the account (Low–Medium, before 0B)

`UpdateAccountCommand.active` is a primitive `boolean`
([UpdateAccountCommand.java:16](../apps/backend/src/main/java/com/expensetracker/accounts/application/UpdateAccountCommand.java#L16)),
and the planned route is `PATCH accounts/{id}/`
([api-plan.md:49](../docs/api-plan.md#L49)). If a controller binds a partial
JSON body to this command, a missing `active` becomes `false` and the account
is deactivated without any error. **Fix:** either use `@NotNull Boolean` with
full-replacement (PUT-style) semantics, or use a separate PATCH DTO in which
every field is optional.

### 4. `.gitattributes` is missing, so `mvnw` can break on other machines (Low)

`mvnw` is currently LF, and this machine has `core.autocrlf=false`. A Windows
clone with `core.autocrlf=true` would convert it to CRLF, and the script then
fails on macOS, Linux and CI. The Spring starter's `.gitattributes`
(`.tools/backend-starter/.gitattributes`) wasn't copied over. **Fix:** add it
to `apps/backend/` (or the repo root): `/mvnw text eol=lf`,
`*.cmd text eol=crlf`.

### Note, no change needed now

`lockAndRequire*` takes the household row lock before checking membership. It
has to, so that a member removed while the write is waiting is denied once the
lock is acquired. As a side effect, any signed-in user can briefly lock a
household whose ID they know. Random UUIDs make that impractical today.
Revisit when household IDs appear in shareable links or invitations.

## Process and direction

1. **Commit the work.** The doc rewrite and the whole backend slice are
   uncommitted on `main`: 17 modified files, plus the untracked
   `apps/backend/`, `claude/`, `codex/` and four new docs. Commit at least two
   pieces (decisions/docs, then backend 0A.1) so later reviews have a diff to
   look at and the work is safe. Consider a branch per phase.
2. **The docs are growing faster than the code, and they're hard to read.**
   Docs, AGENTS.md and the README total 2,113 lines, against 1,055 lines of
   main Java/SQL. AGENTS.md has grown from about 40 lines to 117, and much of it
   covers later phases: cache hits, multi-household lock ordering, dataset
   splits, trace retention. Every Codex task loads all of it. Keep AGENTS.md to
   rules for code being written now, and move phase-specific rules into the
   phase docs. The prose is also very compressed. Since the goal includes
   being able to *explain* each design, docs the learner struggles to read
   work against it.
3. **Start the AI loop now.** Day one produced a careful foundation, but no AI
   yet. [next-move.md](../docs/next-move.md) already allows the offline Phase 1
   experiment (synthetic PDFs, a fake provider, the scorer) to run in parallel,
   and it doesn't need authentication. Make it the next Codex task, alongside
   or before 0B. 0B is only required later, for the review screen.
4. **Decide where Python lives.** With Java chosen, Phases 3, 8 and 9 (search
   and reranking libraries, fine-tuning, model serving) are still mostly Python
   work. Decide now whether Python stays limited to offline eval and dataset
   tooling, or becomes a separate AI service the Java backend calls. Phase 1's
   eval runner should be built in the place it will stay. See
   [open-decisions.md](open-decisions.md).

## Tasks for Codex

Before or within Phase 0B:

1. Finding 1: unify inaccessible-household responses as `not_found`, update the
   tests, and add the unknown-ID write test.
2. Finding 2: remove `default-property-inclusion: non_null`, and add a JSON
   test that `lastFour: null` is serialized.
3. Finding 3: choose PUT or PATCH semantics for account updates and make the
   command or DTO match.
4. Finding 4: add `.gitattributes` for `mvnw`/`*.cmd`.

Separately: the Phase 1 offline extraction/eval skeleton, once the Python
decision is made.
