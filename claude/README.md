# Claude reviews

Notes from Claude, the reviewer in step 2 of [dev.txt](../dev.txt). Codex
implements, and ChatGPT does a second review. Everything here is review or
proposal. Project rules live in [AGENTS.md](../AGENTS.md) and [docs/](../docs/),
and nothing here counts as adopted until it is moved there.

| File | Contents |
| --- | --- |
| [2026-10-13-project-review.md](2026-10-13-project-review.md) | Historical Django/Python scaffold review, checks that were run, strengths, issues |
| [2026-10-13-ai-roadmap-assessment.md](2026-10-13-ai-roadmap-assessment.md) | Assessment of the GPT 10-phase AI plan and a revised phase outline |
| [2026-10-13-backend-foundation-review.md](2026-10-13-backend-foundation-review.md) | Review of the Spring Boot Phase 0A.1 slice: rerun verification, 4 findings, process notes |
| [open-decisions.md](open-decisions.md) | Decisions that block the next work, with recommendations |

## Latest — backend foundation review, 2026-10-13

Phase 0A.1 (Spring Boot identity, household and account services) checks out:
`mvnw verify` was rerun, and 22 unit plus 31 PostgreSQL integration tests
passed with no skips. Phase 0B now addresses that review's four findings:
inaccessible-household responses, explicit JSON nulls, required `active` on
full PUT, and declared Maven wrapper line endings. Authentication/MFA, optional
Google login/linking, persistent sessions and household/account endpoints are
implemented. Extension verification on 2026-10-14 passed 36 unit and 88
PostgreSQL 16.4 integration tests, with no failures, errors or skips; this used
an isolated external database and signed mock Google provider. Docker/Testcontainers
and real Google were not exercised. See [the implementation note](../codex/2026-10-14-security-backend.md).
Start the offline Phase 1 AI loop next. The user subsequently resolved decision 8
by requesting removal of the Python scaffold and prototype; the first
evaluation runner will use Java.

## Adopted after review — 2026-10-13

The user chose a shared household ledger with roles, PDF statements with a CSV
baseline and receipts later, and OpenAI first with Claude comparison later.
These choices now live in [docs/next-move.md](../docs/next-move.md) and
[docs/roadmap.md](../docs/roadmap.md). The user subsequently selected Java and
Spring Boot for the product backend. Its identity, household and account
foundation and authentication/account API are implemented; imports, reporting
and the client remain pending. A synthetic Java extraction/eval experiment can
progress now. See [security](../docs/security.md) for implemented login/MFA,
Google configuration and session flows.
The user requested removal of all Python code on 2026-10-13. The Django scaffold
and reporting prototype are removed; their source is recoverable from Git history
at commit `75a0680`. See [backend transition](../docs/backend-transition.md) and
[the reporting contract](../docs/reporting-contract.md).
The following summary preserves the review's earlier context.

## Summary as of 2026-10-13

- **The foundation is solid.** It has integer-cent money, source provenance,
  staged review and deterministic reporting. All 23 tests pass.
- **The docs don't match the goal.** They describe an app for one person, with
  AI at the end. The goal is a household tracker, built as a long-term AI
  engineering portfolio.
- **Next steps, in order:** settle the household tenancy model, then rewrite
  the roadmap and product docs, then build Phase 1 (upload → AI extraction →
  review → corrections saved as labels → eval score).

Dated files are snapshots of that day and are only edited to fix errors.
Their Python commands and links to removed source paths describe the historical
scaffold; use commit `75a0680` to inspect that source. `open-decisions.md` is
updated as decisions are made.
Their `apps/backend`/`apps/web` paths also describe the pre-restructure layout;
current code lives in root `backend/` and `frontend/`. Use Git history before
the root-directory move when following dated backend-source links.
