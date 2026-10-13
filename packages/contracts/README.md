# Shared contracts — planned

The Spring Boot API is the authoritative contract for web and mobile. Once
request/response DTOs and controllers exist, generate OpenAPI and TypeScript
types here rather than hand-writing parallel financial models. The Python/Django
scaffold was removed on 2026-10-13. Proposed routes are in
[the API plan](../../docs/api-plan.md).

Conventions: date-only `YYYY-MM-DD`, month `YYYY-MM`, UTC instant timestamps with
`Z`, stable object IDs, explicit transaction kind, and source provenance.
Normalized monetary wire fields are canonical decimal integer strings plus
currency: `{"amount_minor": "1250", "currency": "USD"}`. Transaction amounts
are positive; report totals/deltas may be signed or zero; unresolved staged
amounts may be null. OpenAPI must describe the actual string type and
pattern/nullability. Keep generated DTO amounts as strings.
Use explicit Java DTO mappings for these monetary strings; exposing a JPA
entity or serializing its Long directly would produce the wrong wire type.
Use java.time LocalDate, YearMonth, and Instant for the corresponding backend
values, with explicit ISO serialization and UTC instants.

Household-scoped report endpoints must return coverage and supporting
transaction IDs as well as calculated totals. Formatting and runtime helper
implementations belong in [shared](../shared/README.md), following
[engineering conventions](../../docs/engineering-conventions.md). Generated
TypeScript types do not replace backend validation or authorization.

No generated client or runnable package exists yet.
