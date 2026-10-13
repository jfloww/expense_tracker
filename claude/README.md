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
passed with no skips. Before Phase 0B adds controllers, fix the 4 findings: the
403/404 mismatch with the API plan, JSON leaving out null fields, primitive
`active` on a PATCH, and the missing `.gitattributes`. Also: commit the work,
start the offline Phase 1 AI loop. The user subsequently resolved decision 8
by requesting removal of the Python scaffold and prototype; the first
evaluation runner will use Java.

## Adopted after review — 2026-10-13

The user chose a shared household ledger with roles, PDF statements with a CSV
baseline and receipts later, and OpenAI first with Claude comparison later.
These choices now live in [docs/next-move.md](../docs/next-move.md) and
[docs/roadmap.md](../docs/roadmap.md). The user subsequently selected Java and
Spring Boot for the product backend. Its identity, household and account
foundation is implemented; session authentication and account HTTP endpoints
are next. A synthetic Java extraction/eval experiment can progress in parallel.
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
