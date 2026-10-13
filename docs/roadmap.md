# Roadmap

## 0. Foundation — this scaffold

Deliver module boundaries, ledger models, migration, health endpoint, and a
deterministic spending-change prototype using synthetic data. Verify arithmetic
and model rules locally. No real financial data or external AI is required.

## 1. Private user workspaces

Implement signup/login/logout/recovery, own accounts and categories, and
server-side ownership checks. Establish same-origin browser sessions and CSRF.
Build the first Next.js shell against real API responses.

Done when two test users can create accounts and all cross-user reads, writes,
foreign-key substitutions, reports, and file access are denied. Repeat these
checks on PostgreSQL. Include deletion and request limits in the usable release.

## 2. One verified CSV format from upload to report

Confirm a Capital One, Chase, or Amex export sample and its account type. Add
private storage, bounded import jobs, one parser, staging, review, atomic
confirmation, and monthly totals. Prefer CSV for the first complete path.

Done when a month of transactions can be imported, reviewed, and confirmed;
re-upload and retry leave totals unchanged; legitimate identical purchases are
preserved; purchases/refunds/payments yield the correct total; every entry links
to its source. Users can export and delete their data.

## 3. Remaining issuer formats and learned rules

Add the other verified CSV adapters, explicit merchant rules, rule preview, and
the PDF extraction path. Add a model classifier only for unresolved rows after
the deterministic path works, with opt-in data processing and usage limits.

Done when each supported format has representative redacted/synthetic fixtures
and handles dates, signs, duplicate candidates, and source provenance correctly.
Ambiguous merchants remain reviewable rather than silently finalized.

## 4. Spending-change feature connected to real data

Connect the prototype to authorized queries and the web dashboard. Show two
completed months, one currency, selected accounts, category and merchant
contributions, and source transaction links. Make coverage visible.

Done when all displayed contributions reconcile to the exact overall delta;
changing a category/refund updates the report; incomplete imports cannot appear
as a complete-month comparison; different currencies never silently combine.

## 5. Mobile and agent tools

Connect Expo to the same API. Add read-only analysis tools, then a bounded agent
that explains calculated facts. Add owner-scoped historical-example retrieval
if rules alone cannot personalize classification. An authenticated MCP adapter
can expose those tools to external clients later.

Done when mobile and web share behavior, every tool enforces user ownership,
answers reference source transactions, and agent executions stop at configured
step, time, and cost limits. These additions depend on a reliable ledger.

## Decisions still open

- Actual CSV/PDF export formats for Capital One, Chase, and Amex, including
  account types and verified coverage metadata.
- Product name, visual direction, and the chosen signature feature; the
  spending-change explanation is the initial recommendation.
- Production hosting, private object storage, and job queue choice.
- Email/authentication provider, recovery flow, and later mobile authentication.
- Whether users want optional AI processing and which provider to use.
