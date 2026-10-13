# Statement imports

## Issuer support plan

| Issuer | CSV adapter | PDF adapter | Needed to begin |
| --- | --- | --- | --- |
| Capital One | Planned | Planned | Redacted export and account type |
| Chase | Planned | Planned | Redacted export and account type |
| Amex | Planned | Planned | Redacted export and account type |

Do not infer column names or amount signs from the issuer's name. Account types
and export versions can differ. No native bank format has been verified yet.
Build one adapter against an actual sample, then add the others using the same
normalization contract. Use only synthetic or properly redacted fixtures in Git.

## Planned pipeline

```text
upload -> validate file -> select issuer adapter -> extract staged rows
       -> normalize dates/signs/currency -> validate and check duplicates
       -> apply category rules -> suggest unknown categories -> user review
       -> atomic confirmation -> confirmed ledger -> reports
```

CSV is parsed as data, with explicit column/date/sign mappings. For a PDF,
attempt text extraction first; add OCR or model extraction only if needed.
Always preserve source page or row references. Display extraction failures for
review instead of generating plausible missing transactions.

## Adapter contract

An adapter should return staged rows and document metadata, with separate
blocking errors and nonblocking review issues. Proposed normalized fields:

- Original description and raw row data; original transaction identifier if
  the issuer supplies one.
- Posted date, optional purchase date, normalized merchant, positive integer
  amount_minor, currency, flow, and kind.
- Statement identifier, CSV row or PDF page reference, and extraction method.
- Optional category suggestion, its basis, and review flags.

Missing or invalid dates, currency, or amounts block confirmation. An ambiguous
category can remain Uncategorized. Do not infer an order's item contents from
Amazon, Walmart, or a similar merchant description.

## Duplicates and reconciliation

Hash source bytes to detect exact re-uploads within the same user and account.
Confirmation must be idempotent: enforce a unique staged source row and commit
the ledger plus statement state in one database transaction. Retried jobs may
update a staged result; they cannot create additional confirmed entries.

Across overlapping exports, prefer a reliable issuer transaction identifier.
Otherwise a date/amount/merchant fingerprint is only a duplicate candidate.
Ask for review because two identical purchases can be legitimate. Preserve both
sources when a duplicate is resolved.

Where a statement supplies suitable totals or balances, verify the relevant
sum using its debit/credit conventions. Many CSV transaction exports contain
no such totals. Do not claim reconciliation when the source lacks evidence.
Balances must account for all flows, including payments and adjustments, even
though the spending report excludes those kinds.

Credit card bill payments and bank-side payments are not new purchase expenses.
Transfers are excluded from spending and can be linked across accounts later.
Do not automatically equate equal opposite amounts with a proven transfer.

## Review and coverage

Make source values, proposed fields, and flags visible together. The user can
correct a row, resolve a duplicate, exclude it with a reason, or confirm it.
Saving a merchant rule is a separate explicit action with a preview of matches.

Statement coverage comes from verified period metadata or a clear explicit
confirmation of an export's date range. A CSV's earliest and latest transaction
dates do not prove that no other transactions are missing. Reports show partial
or unknown coverage until every selected account has verified coverage.

## Confirmation acceptance criteria

- Invalid required fields cannot become confirmed entries.
- One staged row can create at most one ledger entry.
- An interrupted or repeated confirmation cannot produce partial or duplicate
  totals.
- Importing a purchase, a refund, and a card payment yields the expected net
  spending, with the payment excluded.
- Re-importing an identical file does not change the ledger.
- Two legitimate identical purchases remain representable.
- A different user cannot access a file, job, staged row, or confirmed entry.
