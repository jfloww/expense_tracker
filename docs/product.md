# Product brief

## Purpose

Help users turn statements from multiple banks into a trustworthy view of
spending. The first audience is an individual with several US bank or credit
card accounts. Each user has a private workspace; household sharing is later.

The first issuer targets are Capital One, Chase, and Amex. Start with whichever
actual downloadable CSV is easiest to verify. Support a format only after
testing a redacted example, its dates, debit/credit signs, and payment/refund
behavior. PDF support follows the working CSV path.

## First-release flow

1. Sign up and register a bank or card account.
2. Upload a supported statement or transaction export.
3. See processing status and any validation issues.
4. Review extracted transactions and category suggestions against their source.
5. Correct errors, resolve possible duplicates, and confirm the import.
6. See monthly spending, categories, and transactions; export or delete records.

The review screen is the main workflow. It should preserve the bank's original
description, show normalized merchant and category separately, and explain why
a row needs attention. A correction can apply to this row only, or save an
explicit rule for future matching transactions.

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
income, and card payments do not count as spending.

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

Include private accounts, uploads, staged review, category rules, duplicate
review, payment/transfer handling, monthly reports, CSV export, and deletion.
Defer live bank connections, native mobile UI, household sharing, item-level
receipt matching, billing, tax guidance, and autonomous financial actions.

## Success criteria

- Two users can independently import statements and cannot access each other's
  accounts, files, staged rows, transactions, rules, or reports.
- Re-uploading an identical file to the same account does not change totals.
- Users can inspect the source behind every confirmed transaction.
- Category contributions sum exactly to the total spending change.
- A card purchase and the later payment of its card bill count as one expense.
- Uncertain imports and incomplete reporting coverage remain visible.
