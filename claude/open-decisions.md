# Open decisions

Update this file as decisions are made. Record the date and the choice, and
move adopted decisions into `docs/`.

| # | Decision | Options | Claude's recommendation | What it blocks |
| --- | --- | --- | --- | --- |
| 4 | First bank format to verify | Capital One, Chase, Amex | Inspect an available statement privately, then build synthetic fixtures for its format | Issuer-specific validation, not the synthetic experiment |
| 5 | Long-term tracing and eval tooling | Keep minimal JSONL, or integrate a dedicated tracing/eval tool | Start with JSONL; compare a tool when operational needs justify it | Later tracing integration |

## Decided

Confirmed by the user in chat on **2026-10-13**, then adopted in
[next-move.md](../docs/next-move.md), [roadmap.md](../docs/roadmap.md), and the
product/architecture/AI docs:

| # | Decision | Adopted choice |
| --- | --- | --- |
| 1 | Household tenancy | Shared household ledger with memberships and roles; private accounts and settle-up later |
| 2 | Phase 1 inputs | PDF statements first, with a deterministic CSV baseline when available; receipts later |
| 3 | Providers | OpenAI first; compare Claude later through a provider-neutral interface |
| 6 | Project docs | Updated to the long-term AI engineering direction with early extraction, evaluation, and tracing |
| 7 | Backend framework | Java with Spring Boot replaces Django as the target product backend; Next.js remains the frontend |
| 8 | Current repository Python scope, decided 2026-10-13 | Remove all Python source, the Django scaffold, its dependencies, and the reporting demo; retain reporting requirements as documentation and build the first extraction/evaluation runner in Java. Later training or serving experiments may choose separately scoped tooling when needed |

Initial implementation defaults use owner/member roles. `paid_by` remains an
optional later attribution choice rather than a blocker for the household
foundation. Preserve existing data under
[backend transition](../docs/backend-transition.md). Current implementation,
verification, and next work live in [next-move.md](../docs/next-move.md).
The dated review files remain snapshots; removed source is available in Git
history at commit `75a0680`.
Current code lives in root `backend/` and `frontend/`; `apps/` paths in dated
reviews refer to the earlier layout preserved in Git history.
