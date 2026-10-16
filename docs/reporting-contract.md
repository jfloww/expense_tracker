# Spending-change reporting contract

This contract preserves the behavior and synthetic example from the removed
Python prototype for Java reporting. Its source and tests remain in Git
history. See [next-move.md](next-move.md) for implementation and verification.

## Inputs and exact calculations

Accept household-authorized records, one explicit supported currency (USD
initially), strictly formatted `YYYY-MM` periods, and optional coverage evidence
with an explicit selected-account set. The previous month must precede the current
month, and both must have
ended before the explicit `as_of` calendar date. Do not derive a period from an
implicit server time zone. Reject repeated transaction IDs, even if one copy
would otherwise be excluded. Equal fingerprints with distinct IDs can represent
legitimate purchases.

Only confirmed expenses and refunds in the requested posted months contribute.
Expenses add their positive stored amount; refunds subtract it in their posted
month. Exclude unconfirmed entries, income, transfers, card payments, and
unresolved adjustments. Never combine currencies or perform implicit conversion.
Report omitted currencies explicitly if supplied records contain them.

Stored individual amounts must be positive bounded Java `long` values. Report
totals and deltas use `BigInteger`, including totals exceeding `Long.MAX_VALUE`.
The delta is current net spending minus previous net spending. Use canonical
decimal integer strings and an explicit currency at the API boundary.

Produce category and merchant contributions whose sums each equal the overall
delta. Keep uncategorized entries explicit. Merchant grouping normalizes case
and repeated/outer whitespace while preserving original descriptions. Order
groups by descending absolute delta, then stable group key; order supporting
transaction IDs deterministically. Each group retains its previous/current
totals and source transaction IDs, with statement/row/page references available
for drill-down. Reordering input must not change the report.

## Coverage

Coverage concerns the entire selected account set, including accounts with no
transactions. Selected transactions must belong to that set; an explicitly
empty set is invalid. Coverage intervals have inclusive date-only boundaries
and cannot end before they start. Merge overlapping and adjacent verified
statement intervals for each account.

- `complete`: every selected account has uninterrupted coverage from the first
  through the last day of the month.
- `partial`: coverage and account selection are supplied, but an account has a
  gap or no covering statement. An explicitly empty interval list is partial.
- `unknown`: either coverage evidence or the account set is not supplied.

Return the relevant statement IDs and uncovered account IDs, with warnings for
partial or unknown coverage. Transaction dates alone never prove completeness.

## Synthetic acceptance example

Compare August and September 2026 as of `2026-10-13`, currency `USD`, selecting
`chase-card`, `capital-one-card`, and `amex-card`. Each account has a verified
statement spanning August 1–31 and another spanning September 1–30. Both months
therefore have complete coverage. These are invented records, not bank exports.
All amounts below are integer USD minor units.

| Category | Merchant | Account | August expense | September expense | September refund | Net delta |
| --- | --- | --- | --- | --- | --- | --- |
| Dining | Local Restaurant | chase-card | 12000 | 32000 | 2000 | 18000 |
| Groceries | Grocery Market | capital-one-card | 26000 | 35000 | 0 | 9000 |
| Utilities | Electric Utility | amex-card | 10000 | 16000 | 0 | 6000 |
| Transport | Transit Pass | chase-card | 10000 | 7000 | 0 | -3000 |

Expense IDs are `<category>-8` and `<category>-9`, posted on the 15th of their
month, where category is `dining`, `groceries`, `utilities`, or `transport`.
The `dining-refund` posts September 20. A confirmed `card-payment` of `50000`
posts September 25 to `chase-card` and is excluded. Statement IDs are
`<account>-2026-<month>` with a two-digit month. Expense source rows are 1–4
in table order; refund and payment source rows are 5 and 6.

Expected August total: `58000`; September net total: `88000`; delta: `30000`
($300 increase). Category and merchant contributions each reconcile to `30000`.
Dining's September sources include `dining-9` and `dining-refund`; the payment
never appears among spending sources.

The Java implementation must also cover year boundaries, invalid/current
months, negative net spending, excluded rows, missing/gapped/overlapping/adjacent
coverage, accounts without transactions, duplicate IDs, input-order invariance,
uncategorized/normalized merchants, unsupported currencies, and aggregates
beyond both JavaScript's safe integer range and Java's stored-amount range.
