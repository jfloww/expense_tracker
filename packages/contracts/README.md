# Shared contracts — planned

The API is the authoritative contract for web and mobile. Once serializers and
routes exist, generate OpenAPI and TypeScript types here rather than hand-writing
parallel financial models. Proposed routes are in docs/api-plan.md.

Conventions: ISO dates, stable object IDs, integer amount_minor plus currency,
explicit transaction kind, and source provenance. User-scoped report endpoints
must return coverage and supporting transaction IDs as well as calculated totals.

No generated client or runnable package exists yet.
