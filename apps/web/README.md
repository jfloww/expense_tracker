# Web client — planned

Next.js with TypeScript and the App Router is the intended client. No frontend
dependencies, routes, or UI have been generated in this foundation task.
The authoritative product API will be Java Spring Boot in apps/backend; the
existing Django scaffold is a reference. Web authentication uses Spring
Security sessions and CSRF protection behind the same origin.

Plan features before routes using [features and pages](../../docs/features-and-pages.md).
Proposed primary navigation is Dashboard, Statements, Transactions, and Settings.
Upload is a wizard action; original downloads and transaction CSV exports are
actions on their relevant views. A statement library lists all files; statement
detail reviews one file. Review is the first central workflow; the dashboard
follows confirmed ledger data.

Adopted layout when implementation starts:

```text
src/
  app/                 Routes/layouts composing feature modules
  features/
    accounts/
    imports/           Upload, progress, source-aware row review
    transactions/
    reports/           Monthly totals and spending-change detail
  components/ui/       Reusable web presentation primitives
  lib/api/             HTTP transport, CSRF, and error mapping
  lib/server/          Server-only session/configuration helpers
  lib/formatting-context.ts  Explicit locale and display time zone
```

Generated API DTOs belong in `packages/contracts`. Pure date, money, and common
error helpers belong in `packages/shared`. Keep domain components/hooks inside
their feature; use explicit public entries for cross-feature imports. Separate
server-only exports from client components.

Keep money calculations and authorization in the Spring Boot API. Monetary API
values are planned as canonical decimal integer strings plus currency; use exact
`formatMoney`, never `Number(amount_minor) / 100`. Use separate
`formatPostedDate`, `formatMonth`, and `formatTimestamp` helpers so calendar
dates never shift time zone. Resolve explicit formatting settings once and use
the same settings for server and client rendering.

UI source links must open only authorized records. Build the client after the
account/import API contract is implemented, following
[engineering conventions](../../docs/engineering-conventions.md) and
[backend transition](../../docs/backend-transition.md). Use the
[official installation guide](https://nextjs.org/docs/app/getting-started/installation)
to select current dependency versions at that time.
