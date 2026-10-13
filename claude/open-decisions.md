# Open decisions

Update this file as decisions are made. Record the date and the choice, and
move adopted decisions into `docs/`.

| # | Decision | Options | Claude's recommendation | What it blocks |
| --- | --- | --- | --- | --- |
| 4 | First bank | Capital One, Chase, Amex | Whichever you have real statements for. They are needed for a realistic golden set | Golden dataset |
| 5 | Tracing and eval tooling | Build a minimal version yourself (JSONL/SQL), or use an existing tool (e.g. Langfuse, or something OpenTelemetry-based) | Build a minimal version in Phase 1, then adopt a tool in Phase 7 and compare the two | Phase 1 |
| 8 | Where Python lives (added 2026-10-13) | (a) Java only. (b) Python for offline eval and dataset tooling only. (c) A separate Python AI service that the Java backend calls | **(b) now:** the extraction adapter and validation stay in Java, and the eval runner and dataset tools are Python, reading exported predictions and labels. Revisit (c) at Phase 3, where embeddings, reranking, fine-tuning and serving are mostly Python work | Phase 1 eval runner |

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

Initial implementation defaults use owner/member roles. `paid_by` remains an
optional later attribution choice rather than a blocker for the household
foundation. The Java implementation starts with household ownership in its
first Flyway schema; the old Django code/data stays as reference. See
[backend transition](../docs/backend-transition.md). These decisions describe
the target; Java household services and AI extraction are not implemented yet.
The dated review files remain snapshots.
