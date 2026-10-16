# Roadmap

## Direction

Build a working public household expense tracker as a long-term AI engineering
project. Learn by implementing, evaluating, and operating each capability.
Applied AI/backend engineering is the main learning track; training, serving,
and multi-agent work are later experiments with measured results.

Confirmed choices:

- Shared household ledger with memberships and roles. Private accounts and
  settle-up are later product features.
- PDF statements are the first AI input. Keep a deterministic CSV baseline
  where a matching export is available; receipt photos come later.
- OpenAI is the first extraction provider; compare Claude later through a
  provider-neutral interface. The live model and spending limits remain open.
- Next.js + TypeScript for web; Java 21, Spring Boot 4.x, and Maven Wrapper for
  shared services; PostgreSQL and Flyway for the new schema. Expo follows the
  web flow. Spring MVC/DTOs, application/query services, scoped Spring Data JPA
  repositories, and Spring Security sessions/CSRF are the selected backend
  conventions; Spring AI is optional later.

Current implementation, verification results, and the immediate task live only
in [next-move.md](next-move.md). Establish the extraction contract with an
offline Java experiment, synthetic documents, a fake provider, a CSV baseline,
and JSONL scoring before committing to statement persistence tables. This
experiment does not require a database or the complete ledger/deletion/UI flow.
Live extraction still requires consent and explicit processing limits.

## Learning and product phases

These are the adopted sequence, not completed features or a fixed schedule.

| Phase | Deliverable | What we learn | Exit evidence |
| --- | --- | --- | --- |
| 0 | Spring Boot foundation, Flyway household schema, roles, services, deletion, session/account API | Modular monolith, tenancy, authorization, schema migrations, safe writes | Maven Wrapper build; real PostgreSQL integration tests; shared members allowed, foreign/revoked members denied; full-chain deletion rollback and last-owner concurrency; Java reporting-contract acceptance tests; legacy data preserved |
| 1 | PDF extraction, CSV baseline, staging, review, versioned labels, eval runner | Multimodal input, structured outputs, validation, tracing | Reproducible per-document row/field scores, missing/extra rows, correction counts, failures, latency, and usage; source links and injection fixtures |
| 2 | Provider and model comparison | Model selection and quality/cost/latency trade-offs | Same frozen dataset run on OpenAI and Claude plus smaller/larger candidates where available; comparison and selection rationale |
| 3 | Household category suggestions: rules → retrieved examples → embeddings/reranking | Prompting, few-shot, RAG | Held-out comparison against rules-only and simpler retrieval; correction rate and retrieval quality |
| 4 | Natural-language transaction search | Keyword, vector, hybrid search, reranking | Precision/recall at k on labeled queries; comparison against simpler search |
| 5 | Spending-analysis agent: hand-written loop, then SDK | Tool calling, harness, state, limits, resuming | Task success, invalid calls, unsupported claims, and cost; amounts match reporting tools |
| 6 | Authenticated read-only MCP tools | Tool schemas, client integration, scoped access | External client can use its household's reports; denial, revoked-membership, and injection cases pass |
| 7 | Public deployment and operational tooling | LLMOps, CI/CD, tracing, monitoring, routing, rollback | Working service with quality/failure/latency/cost views and a demonstrated prompt/model rollback |
| 8 | Small classification model, then optional language-model fine-tuning | Supervised learning, splits, training, evaluation | Same held-out comparison with rules, prompts, and RAG; actual improvement or documented lack of improvement |
| 9 | Self-hosted model for one bounded task | Inference serving, batching, quantization, load tests | Quality, latency, resource usage, and cost comparison with hosted APIs |
| 10 | Multi-agent monthly-report experiment | Task delegation and result verification | Measured improvement over a single agent, or a useful documented null result |

Monitoring, evaluation, privacy, and CI checks start with the first applicable
feature; phase 7 expands them into production operations. Receipts can enter
after statement imports work, with receipt/statement purchase matching to avoid
counting an expense twice. Mobile is a later client integration, not a reason
to duplicate financial logic.

## Rules for completing a phase

Each phase leaves runnable code, a reproducible evaluation or appropriate
correctness checks, and a short experiment note: why this approach, its simpler
baseline, what failed, and the change in quality/cost/latency. Results may show
that extra complexity did not help; that is still a completed experiment.

Keep real statements and detailed provider payloads in private ignored storage.
Commit synthetic fixtures and sanitized results only. Split by document/group
before prompt iteration; all versions of the same statement stay in one split.
Test examples and labels must never become prompt examples, retrieval context,
or training data. Verified labels are distinct from simply accepting a proposal.

Preserve existing legacy data under [the transition policy](backend-transition.md).
Use [the reporting contract](reporting-contract.md) for exact money, coverage,
refund, and source-evidence behavior. Resolve operational choices when their
workflow needs them; they do not block the offline experiment.
