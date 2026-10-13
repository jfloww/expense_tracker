# Product brief

## Purpose

Help households turn statements from multiple banks into a trustworthy shared
view of spending. This public, multi-user product is also a long-term AI
engineering learning project: build working features and measure their quality,
cost, and latency. The household is the target data scope. The Java Spring Boot
backend implements identity, household and account services with household scope
in its first schema. Next.js remains the planned web client. The Python/Django
scaffold and reporting prototype were removed on 2026-10-13; Java reporting,
imports, and browser authentication remain pending. See
[backend transition](backend-transition.md).

The user confirmed a shared ledger with membership roles on 2026-10-13. Start
with owner/member roles: both use the ledger; owners manage membership and
household deletion. Private accounts, viewer permissions, and settle-up come
later. Leaving a household revokes access without deleting its shared records.

The first issuer targets are Capital One, Chase, and Amex. Start AI extraction
with PDF statements and keep a deterministic CSV baseline when a matching
export is available. Verify dates, amount signs, periods, and payment/refund
behavior using synthetic fixtures and private or redacted examples. No issuer
format is currently supported. OpenAI is the first provider; Claude comparison
comes later through a provider-neutral interface. Receipt photos come later.

## First-release flow

The [feature and page plan](features-and-pages.md) defines capabilities first,
then proposes their placement in Dashboard, Statements, Transactions, and
Settings. Both original statement downloads and confirmed transaction CSV
exports were confirmed in chat; page placement is a planning proposal.

1. Sign in, create or join a household, and register a bank or card account.
2. Upload a supported statement or transaction export.
3. See AI extraction status and code-validated issues, with source references.
4. Review extracted transactions and category suggestions against their source.
5. Correct errors, resolve possible duplicates, and confirm the import. Preserve
   prediction and label versions for later evaluation; acceptance alone is not
   proof that every field was verified.
6. See shared monthly spending, categories, and transactions; use authorized
   export and deletion actions.

The review screen is the main workflow. It should preserve the bank's original
description, show normalized merchant and category separately, and explain why
a row needs attention. Corrections initially apply to the reviewed row. A later
category-rule feature will offer explicit rule saving for future matches.

## Recommended signature feature: Why did my spending change?

Show the contributions to a monthly change and let users open the transactions
behind every amount. A first example using synthetic data:

> September spending increased by $300 compared with August: dining +$180,
> groceries +$90, utilities +$60, and transport -$30.

This is an explanation of the arithmetic. It does not infer personal motives,
the items inside an Amazon order, or whether a purchase was worthwhile.

The first version compares two completed months using confirmed transactions
and one currency. Every category and merchant contribution carries source
transaction IDs for both periods. Coverage is complete, partial, or unknown
relative to the explicitly selected accounts; missing imports appear alongside
the comparison. Refunds reduce spending in their posted month. Transfers,
income, and card payments do not count as spending. The exact requirements and
synthetic acceptance cases live in [the reporting contract](reporting-contract.md).

This proposal is a useful product direction, not a claim that no competitor
offers it. Its value depends on making the supporting evidence easy to inspect.

## Other feature directions

| Feature | Concrete experience | When to build |
| --- | --- | --- |
| Import confidence report | See which rows, totals, dates, or duplicates need review before confirmation | Core import workflow |
| Category rule preview | Before saving a rule, see the transactions it would affect and the resulting category totals | After classification works |
| Recurring charge watch | Spot a possible new subscription or price change, then inspect the matching charges | After several months of history |
| Ask about my spending | Ask a question and receive calculated facts with transaction links | After trusted reporting tools exist |

A recurrence detector should label its findings as candidates. A regular
purchase is not proof of a subscription. Alerts, cancellations, and projected
savings require additional behavior beyond detecting repeated charges.

## Release boundaries

Include household memberships, shared accounts, PDF uploads and AI extraction,
a CSV baseline, staged review, versioned corrections, duplicate review,
payment/transfer handling, monthly reports, CSV export, and authorized deletion.
Tracing and evaluation begin with the first model call. Later phases add explicit
category rules, RAG, agent tools, MCP, model comparison, training, and serving.
Defer private-account permissions, settle-up, live bank connections, receipt
matching, native mobile UI, billing, and autonomous financial actions.

## Success criteria

- Members can share their household's accounts, files, staged rows, transactions,
  rules, and reports; outsiders and removed members cannot access them.
- Re-uploading an identical file to the same account does not change totals.
- Users can inspect the source behind every confirmed transaction.
- Category contributions sum exactly to the total spending change.
- A card purchase and the later payment of its card bill count as one expense.
- Uncertain imports and incomplete reporting coverage remain visible.
- Each AI experiment produces reproducible scores and a short explanation of
  its baseline, failures, and quality/cost/latency results.

See [next-move.md](next-move.md) for the immediate task and
[roadmap.md](roadmap.md) for the full learning sequence.
