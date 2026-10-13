# Next move

## Decisions adopted on 2026-10-13

The goal is a functioning household tracker used to learn AI engineering end
to end. The user confirmed:

| Decision | Choice |
| --- | --- |
| Tenancy | Shared household ledger with roles; private accounts and settle-up later |
| First AI input | PDF statements; deterministic CSV baseline when available; receipts later |
| Providers | OpenAI first; compare Claude later |
| Backend | Java + Spring Boot; replaces the planned Django product backend |
| Client | Next.js + TypeScript web client, using the shared Spring Boot API |

Tenancy, input and provider choices were confirmed after reviewing the Claude
notes and attached GPT discussion. The user subsequently chose Java and Spring
Boot. Preserve integer money, staging, provenance and deterministic reporting
behavior while implementing the product backend in Java.

The Spring Boot foundation is implemented at `apps/backend`; `apps/api` remains
the unchanged Django/Python reference. Java uses a new PostgreSQL/Flyway schema
and does not convert or reset the legacy database.

## Implemented: Phase 0A.1 Spring Boot bootstrap and household/accounts

The first slice implements the Java backend foundation. Follow
[backend transition](backend-transition.md) and
[engineering conventions](engineering-conventions.md). Use owner/member roles;
both can use the ledger, and owners manage membership and household deletion.
The implemented slice is build/database setup and identity/household/account
services. Phase 0A.2 extends the ledger graph later. Phase 0B can add the first
authenticated account API after 0A.1, alongside that extension; do not expose
incomplete import or deletion operations.

1. Added `apps/backend` with Java 21, Spring Boot 4.1.1, checksum-pinned Maven
   Wrapper, PostgreSQL/Flyway, and public liveness at `GET /api/v1/health/`.
   Other HTTP requests require authentication; CSRF protection remains enabled.
2. Added User, Household, Membership and Account in feature packages. Flyway
   starts with household ownership, unique memberships, and restrictive foreign
   keys. Current enabled identity and membership/role determine access.
3. Added authorized application and scoped query services using Spring Data
   JPA. Services validate commands/domain rules, own transactions and household
   locks, and protect the last owner from removal, demotion or departure.

First-slice acceptance criteria:

- Maven Wrapper builds reproducibly and runs unit plus PostgreSQL integration
  checks; Flyway creates the initial schema and Hibernate validates its mappings.
- Membership/role and account services allow household members and deny
  nonmembers, revoked members and foreign-object substitutions.
- Owners manage membership; ordinary members cannot. Concurrent changes cannot
  remove the last owner. Removing a member preserves the shared accounts.
- Failed multi-record mutations roll back. Initial uniqueness/relationship
  constraints and locking are verified on PostgreSQL, not an in-memory substitute.
- Existing Python code and databases remain intact. No full import/deletion or
  reporting endpoint is claimed as implemented by the bootstrap slice.

Verification on 2026-10-13: Maven `verify` passed 22 unit and 31 PostgreSQL
integration tests with no skips, using an isolated PostgreSQL 16.4 test cluster.
Checks include a directly observed lock wait with membership revocation before
the waiting write resumes. See [backend setup](../apps/backend/README.md).

## Phase 0A.2: ledger graph, reporting parity and deletion

1. Add statement/staged-row/ledger/category/rule/job schema as those workflows
   are implemented, all household-scoped. Port existing amount/flow/provenance
   constraints and deterministic reporting behavior with parity fixtures. This
   is a new Java schema, not a Django `makemigrations` task.
2. Implement ordered household deletion for the complete graph before that
   operation ships. All shared writes, including jobs, use its locking protocol.
   Define durable post-commit private-file/provider cleanup as storage is added;
   cleanup records must survive household deletion. Do not cascade-delete shared
   data on user departure or infer that legacy owners share a household.

If any existing data needs transfer, use an explicit validated copy and dry run
under the transition policy. Do not reset databases, silently reinterpret old
records, or copy Django password hashes into an incompatible Spring encoder.

Full-ledger completion criteria, separate from the immediate bootstrap:

- Members of one household can share records; nonmembers cannot access or
  reference those records in services.
- Owners can manage membership; ordinary members cannot. Concurrent membership
  changes cannot remove the last owner: lock the household inside the mutation.
- Removing a member revokes access and preserves the shared ledger.
- Flyway creates and validates the Java schema against PostgreSQL. If a legacy
  transfer is needed, a two-owner fixture preserves separate data chains, source
  links and exact values; old data remains intact.
- Full household deletion removes its related records, preserves another
  household, and rolls back on failure. Unauthorized deletion changes nothing.
- Java reporting parity checks preserve the prototype's amount/flow/source and
  coverage behavior, including values beyond safe JavaScript integers and aggregate
  overflow cases. Python checks remain reference evidence, not Java validation.
- Test authorization, constraints, deletion rollback and concurrency on actual
  PostgreSQL; Testcontainers requires a working container runtime. A skipped
  database suite does not establish a completed foundation.

## Next coding task: Phase 0B session authentication and account API

Add Spring Security session login/logout/current-user and
household/account controllers. Use secure HttpOnly cookies and CSRF protection,
JSON API errors and current membership checks. Test real HTTP requests,
cross-household IDs, revoked membership and CSRF. Generate OpenAPI from real
controllers/DTOs; monetary fields remain canonical strings, not Java numeric JSON.
Signup recovery, email invitations and a large settings UI can follow; they are
not prerequisites for a synthetic extraction experiment.

## First AI deliverable: Phase 1 extraction plus evaluation

Build one complete learning loop rather than a standalone evaluation platform:

```text
PDF -> provider extraction -> code normalization and validation -> staged rows
    -> review/correction -> versioned labels -> evaluation report
```

Begin with a small paired synthetic PDF/CSV dataset and a fake provider so the
workflow and scoring run without API credentials. The Java extraction interface
and an offline evaluation runner can progress alongside phase 0A. Python may
remain an optional offline fixture/scoring tool; production normalization and
confirmation belong to Java services. Add bounded OpenAI execution after
credentials, processing consent, private artifact handling, and per-run limits
are configured. Choosing
OpenAI does not select a model or authorize sending arbitrary private files.

Deliverables:

- A versioned provider-neutral extraction contract: document metadata, source
  references, original descriptions, raw date/amount text, nullable proposed
  fields, and explicit issues. Unknown required fields block confirmation.
- One deterministic CSV baseline for an explicitly documented format; do not
  describe a synthetic layout as a verified native bank parser.
- Immutable prediction versions and separately versioned reviewed labels, with
  the prompt/schema/provider/model versions that produced each prediction.
- Metadata traces from the first call: run ID, versions, timing, attempts,
  usage, validation codes, and estimated cost where available. Missing cost is
  unknown, not zero. Raw prompts/documents/outputs require explicit private
  retention and are excluded from ordinary logs and Git.
  Required source documents and immutable prediction records live in private
  application storage; optional raw trace/debug copies have a separate opt-in.
- A runner scoring one-to-one row precision/recall, missing/extra rows, exact
  amount/date/flow/currency accuracy, source references, and document-level
  correctness. Match rows independently of the field being scored so matching
  does not hide amount/date errors. Preserve legitimate duplicate purchases.
- Refusal/incomplete output, invalid fields, failure/retry, and prompt-injection
  fixtures; reconciliation checks only where source totals support them.
- A minimal authenticated Next.js source/review screen after phase 0B, then
  atomic confirmation and a calculated monthly total. Extraction never
  silently writes confirmed transactions.

Use a frozen held-out split before prompt iteration. Group a statement's pages,
PDF/CSV representations, re-exports, and generated variants together. Treat
CSV comparisons and accepted rows as evidence to verify, not automatic ground
truth. Report sample counts and per-document results; a tiny dataset cannot
support strong model or p95-latency conclusions.

Done when the loop runs, errors and corrections are inspectable, an evaluation
result can be reproduced, and a short experiment note explains the baseline,
failures, quality, latency, usage/cost, and the next improvement. Accuracy need
not be perfect to complete an experiment; incorrect entries remain blocked or
reviewable before ledger confirmation.

The OpenAI documentation supports PDF text/page-image inputs and
schema-constrained outputs; content accuracy still needs separate validation.
[File inputs](https://developers.openai.com/api/docs/guides/file-inputs),
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
Evaluation can begin with our own deterministic scoring; hosted eval tooling
is an option later. [Working with evals](https://developers.openai.com/api/docs/guides/evals).

## Review and learning workflow

Follow [dev.txt](../dev.txt): Codex implements a bounded task, Claude reviews,
then two independent reviews check the foundation and AI/data behavior. Apply
review findings based on evidence and run checks appropriate to the changes.
Review snapshots in claude/ are proposals; adopted scope and rules live in docs/
and AGENTS.md.

The learner should be able to explain the design, baseline, observed failures,
and measured trade-offs at each phase. Provider comparison, RAG, search,
hand-written harness then SDK, MCP, operations, training, serving, and
multi-agent experiments remain in [roadmap.md](roadmap.md).
