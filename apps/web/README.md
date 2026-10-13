# Web client — planned

Next.js with TypeScript and the App Router is the intended client. No frontend
dependencies, routes, or UI have been generated in this foundation task.

Build pages around signup, accounts, upload, import review, transactions,
monthly dashboard, spending changes, and settings. Review is the first central
workflow; the dashboard follows confirmed ledger data.

Suggested layout when implementation starts:

```text
src/
  app/                 Routes and layouts
  features/
    accounts/
    imports/           Upload, progress, source-aware row review
    transactions/
    reports/           Monthly totals and spending-change detail
  components/          Reusable presentation components
  lib/api/             Generated API types and authenticated client
```

Keep money calculations and authorization in the API. Render integer minor
units using an explicit currency formatter. UI source links must open only
authorized records. Build the client after the account/import API contract is
implemented; use the [official installation guide](https://nextjs.org/docs/app/getting-started/installation)
to select current dependency versions at that time.
