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

Current implementation, verification, and the next task live in
[docs/next-move.md](../docs/next-move.md). Adopted direction and phases live in
[docs/roadmap.md](../docs/roadmap.md); Java stack and data-preservation rules live
in [backend transition](../docs/backend-transition.md).

Dated files are snapshots of that day and are only edited to fix errors.
Their Python commands and links to removed source paths describe the historical
scaffold; use commit `75a0680` to inspect that source. `open-decisions.md` is
updated as decisions are made.
Their `apps/backend`/`apps/web` paths also describe the pre-restructure layout;
current code lives in root `backend/` and `frontend/`. Use Git history before
the root-directory move when following dated backend-source links.
