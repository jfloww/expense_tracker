# Next move

This is the current implementation/status and verification summary. Keep phase
direction in [roadmap](roadmap.md), technology explanations in [AI-Tech](AI-Tech.md),
and historical evidence in dated `codex/` and `claude/` notes.

## Delivered: offline extraction/evaluation and CI configuration

The first offline slice is a runnable Java experiment before extending the ledger
schema. It runs without Spring startup, PostgreSQL, Docker, network access,
or API credentials. Its outputs are versioned JSONL, not database entities.

Acceptance criteria:

- Generate valid, explicitly synthetic PDF/CSV pairs with document-group splits,
  source references, and independently inspectable reviewed labels.
- Parse the documented synthetic CSV layout deterministically; use a scripted
  fake PDF provider through a provider-neutral interface. Neither reads labels.
- Preserve raw values, normalize exact money/date/flow fields, and retain nulls
  and bounded issues for unresolved values.
- Keep predictions, reviewed labels, execution metadata, and scores separate.
  Distinguish accepted labels from source-verified labels; unknown cost is null.
- Score one-to-one row matches independently of the financial fields being
  scored. Detect wrong fields, missing/extra rows, refusals/incomplete results,
  and legitimate duplicate purchases. Reject document-group split leakage.
- Save a short experiment note with baseline results, controlled failures, and
  limitations. Fake-provider results do not measure actual model quality.
- Add CI running Java 21 Maven `verify` against real PostgreSQL and the offline
  experiment, with test reports retained on failure and no live provider secrets.

Use [the Windows launcher](../tools/run-extraction-experiment.cmd) from the
repository root; macOS/Linux uses [the shell launcher](../tools/run-extraction-experiment.sh).
Both compile only the JDK-only experiment sources with Java 21. Output goes to a
fresh ignored `.tools/extraction-runs/` directory unless an output path is supplied.
See [the experiment note](../codex/2026-10-16-extraction-experiment.md) for the
contract, observed results, scoring denominators, and limitations.

## Current status

Updated 2026-10-16. The Java backend at `backend/` implements Java 21/Spring Boot,
PostgreSQL/Flyway, owner/member household authorization, account APIs, browser
signup/login/logout, password changes, persistent sessions, TOTP/recovery-code
MFA, optional Google login/explicit linking, and public health. See
[security](security.md), [API routes](api-plan.md), and [backend setup](../backend/README.md).

The offline experiment generates paired synthetic PDFs/CSVs, runs a deterministic
CSV parser and scripted PDF provider, validates and scores predictions, and saves
separate immutable/versioned artifacts. It measures software behavior, not real
PDF extraction or model quality. Native issuer formats are unverified.

An opt-in [OpenAI synthetic PDF command](openai-experiment.md) implements the same
contract using inline PDF inputs and structured outputs. It requires an API key,
explicit model/consent, and request/cost/time limits; ordinary tests use only a
loopback mock API. No paid run has been made, and model/budget selection remains
open. Corpus v2 aligns PDF description labels with the rendered whitespace;
CSV labels preserve their original embedded newlines.

Verification on 2026-10-16: Java 21 Maven Wrapper `verify` passed **87 unit and
88 PostgreSQL integration tests (175 total)**, with no failures/errors/skips.
This includes 27 offline experiment and 24 OpenAI adapter/budget/CLI tests. The run used isolated external PostgreSQL
16.4 schemas and a signed loopback Google mock; the test cluster was stopped
afterward. Docker/Testcontainers and live Google/model calls were not exercised.
Both Windows CMD and shell launchers ran with only the JDK and produced valid
JSONL. CSV baseline: 11/11 rows and 2/2 documents exact. The fake PDF scenarios
showed the intended omissions/extras/field errors; see the experiment note.
The packaged OpenAI CLI help command also ran without Spring/database startup;
its provider calls were exercised only against loopback mocks.

[CI configuration](../.github/workflows/backend.yml) runs Java 21 Maven `verify`
with a disposable PostgreSQL 16 service, then the standalone experiment. YAML,
action pins, Markdown links, and Git whitespace checks passed locally. The
workflow also checks packaged OpenAI CLI startup without calling the API. See
[GitHub Actions](https://github.com/jfloww/expense_tracker/actions/workflows/backend.yml)
for remote runs and the pull request's Checks tab for commit-specific evidence.

Uploads, real issuer parsers, live model extraction, persisted review/confirmation,
Java reporting, full-graph deletion, the Next.js client, email recovery/invitations,
RAG, the application harness, and MCP remain future work. Authentication/account
APIs and an offline experiment do not constitute a finished expense tracker.

## Next task and following product slices

1. Exercise the contract with bounded real-provider processing of synthetic
   documents after model choice, explicit opt-in, and run limits are configured.
   Compare against the CSV baseline; report actual errors, usage, and unknown cost.
2. Inspect a real issuer/account/layout privately and build synthetic fixtures
   for its verified date/sign conventions. Chase, Capital One, and Amex are targets
   for credit-card and bank/debit-card activity, not implemented native formats.
3. Refine the [statement schema proposal](statement-schema.md) using observed
   extraction/review needs. Add tables only with consuming workflows; the proposed
   eleven tables are not prerequisites for the offline experiment.
4. Implement authenticated upload/review, atomic confirmation, deterministic
   [reporting](reporting-contract.md), and tested ordered deletion as bounded
   product slices. External cleanup must survive parent deletion. Private accounts,
   settlement, receipts, and mobile follow later.

Keep the existing legacy database/private files intact. Any data transfer requires
the separate [transition policy](backend-transition.md). The removed Python source
remains in Git history; this experiment and the product backend use Java.

## Adopted choices and open decisions

Shared household ledger with owner/member roles; PDF-first extraction with a CSV
baseline; OpenAI first and Claude comparison later; Java/Spring Boot backend and
Next.js/TypeScript web client. Exact money, source provenance, explicit review,
household scope, evaluation, and privacy constraints remain in [AGENTS.md](../AGENTS.md).

Open: first verified issuer layout, live model and processing budget, production
storage/queue/hosting, and recovery/invitation delivery. None blocks the synthetic
offline experiment. Each AI phase needs a runnable result, simpler baseline,
measured outcome, and short experiment note; a measured null result is valid.

Follow [dev.txt](../dev.txt): implement a bounded task, then review the foundation
and AI/data behavior independently. Dated review proposals do not override adopted
guidance. Current household membership and object scope authorize every product
operation; synthetic CLI identifiers are not production authorization.
