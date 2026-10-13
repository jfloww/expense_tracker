# Next-move review — 2026-10-13

Reviewed Claude's two dated reviews, open decisions, the attached GPT discussion,
and the current project guidance/model boundaries. Two independent reviews
covered the foundation and AI/data plan, followed by a consistency pass on the
adopted documents.

## Findings adopted

- Keep integer minor-unit money, provenance, staged review, and deterministic
  reporting. The seven existing user-owned models already supported a public
  multi-user app; household sharing is a new confirmed product choice.
- Git is already present at baseline commit `04e3f4b`; initialization is resolved.
- Validate future writes through authorized services. Model full_clean() alone
  does not implement API authorization and is not automatically called by save.
- Test ordered household deletion and the entire source chain; a blanket
  PROTECT-to-RESTRICT change is not an evidenced fix.
- Bring PDF extraction, evaluation, and metadata tracing into the first AI loop.
  Retain a deterministic CSV baseline and defer receipt matching.
- Preserve immutable predictions and versioned labels; accepted predictions are
  not automatically verified ground truth. Split by statement group and avoid
  held-out-label leakage. Raw trace copies are separate from private application
  source/prediction storage.

## Confirmed choices and next task

Shared household ledger with roles, PDF statements plus a CSV baseline, and
OpenAI first with Claude comparison later were confirmed by the user in chat.
Owner/member roles are the initial implementation defaults; private accounts
and settle-up are deferred.

Next: phase 0A household migration, membership guards, validated services, and
ordered deletion. Then phase 0B minimal authenticated endpoints and phase 1's
extraction/review/evaluation loop. A synthetic offline experiment can run alongside
the foundation. The implementation brief is [next-move.md](../docs/next-move.md).

These changes adopt planning and coding guidance only. Application code and
migrations were not changed, no live provider call was made, and the earlier
23-test report was not re-established by rerunning tests in this documentation
task. Document links, consistency, and whitespace were checked; the user's
existing dev.txt changes and dated Claude review snapshots were preserved.
