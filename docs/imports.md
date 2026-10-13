# Statement imports

## Current status and first experiment

Imports, file storage, provider calls, review UI, and confirmation services are
planned. The legacy Django scaffold has user-owned models. The selected Java
Spring Boot backend uses household scope and owner/member roles in its
first PostgreSQL/Flyway schema and application services. Import services are
not implemented yet. Every future import
object must belong to an authorized household and account. Private accounts,
settle-up, and receipts are later work. See [backend transition](backend-transition.md).

The first AI engineering experiment is PDF statement extraction with OpenAI
behind a provider-neutral service interface. A deterministic CSV parser is the
baseline where a comparable export is available; Claude comparison comes later.
An offline synthetic extraction/eval CLI can progress alongside Phase 0's
Spring Boot foundation. Authenticated uploads and Next.js review require the
household foundation and session/account APIs first. See [roadmap.md](roadmap.md).

## Issuer support plan

| Issuer | CSV adapter | PDF adapter | Needed to begin |
| --- | --- | --- | --- |
| Capital One | Planned | Planned | Redacted export and account type |
| Chase | Planned | Planned | Redacted export and account type |
| Amex | Planned | Planned | Redacted export and account type |

Do not infer column names or amount signs from the issuer's name. Account types
and export versions can differ. No native bank format has been verified yet,
and the first issuer/layout is still open. Inspect an actual sample privately,
then build synthetic fixtures for that layout and add other adapters using the
same normalization contract. Commit synthetic fixtures only; real statements,
including redacted real examples, remain outside Git.

Production parsers, provider/storage adapters, review, confirmation, and jobs
use Java application services and the same household authorization as the API.
A separate worker process reuses those modules; Python is reserved for the
existing parity prototype or optional offline evaluation/training tools, not
a second production import ledger.

## Planned pipeline

```text
upload -> authorize and validate -> extract proposed rows with provenance
       -> code normalization and validation -> staging and duplicate candidates
       -> review/correction and versioned labels -> atomic confirmation
       -> confirmed ledger -> reports

immutable predictions + reviewed labels -> evaluation report
```

CSV is parsed as data, with explicit column/date/sign mappings, without a model.
For the first PDF experiment, compare provider extraction against that baseline
and a simpler text-extraction path where feasible. PDF and CSV must cover the
same account and period for a paired comparison. Always preserve source page or
row references. Display failures for review instead of generating plausible
missing transactions. Learned category suggestions are a later phase and do not
need to be part of the first extraction loop.

## Adapter contract

The provider-neutral extraction service returns proposed rows and document
metadata, with unknown fields allowed and explicit issues. The server attaches
authorized household/account/statement identifiers; model output never chooses
the tenant. Retain raw extracted values separately from code-normalized fields:

- Original description and raw row data; original transaction identifier if
  the issuer supplies one.
- Raw date and amount text, optional proposed posted/purchase dates and merchant,
  and any source currency/debit/credit evidence.
- Code-normalized posted date, optional purchase date, positive integer
  amount_minor, currency, flow, and kind, or null when unresolved.
- Statement identifier, CSV row or PDF page reference, and extraction method.
- Optional category suggestion, its basis, and review flags.

Schema validity is not extraction correctness. Handle refusals, incomplete
responses, invalid schemas, and semantic failures separately. Missing/invalid
posted date, amount, currency, flow, or kind blocks confirmation. Do not force
unknown values into plausible defaults. An ambiguous category can remain
Uncategorized. Do not infer an order's item contents from Amazon, Walmart, or a
similar merchant description.

Spring MVC request DTOs and Bean Validation check boundary shape and formats.
Application/domain validation repeats the authoritative rules, related-object
scope checks, and confirmation requirements for HTTP, worker, and CLI callers.
Map normalized money to canonical decimal integer strings at the JSON boundary;
use integer arithmetic internally and ISO date-only/month/UTC instant contracts
from [engineering conventions](engineering-conventions.md). Staged unresolved
amounts may be null; raw source values remain separate.

Before live calls, require explicit processing consent, verify household
membership and role permission, and bound file size/pages, output, retries,
concurrency, time, and spend. Private CLI experiments need an explicit run opt-in
and limits too. Never make a live call merely by running ordinary tests.

## Duplicates and reconciliation

Hash source bytes to detect exact re-uploads within the same household/account.
Confirmation must be idempotent: enforce a unique staged source row and commit
the ledger plus statement state in one database transaction. Retried extraction
creates an immutable prediction version; it must not silently overwrite review
corrections or create additional confirmed entries.

Confirmation is a `@Transactional` Java application operation with explicit
locking/state checks and database uniqueness constraints. Extraction/network
calls happen outside ledger transactions. Test rollback, repeated confirmation,
and cross-household substitutions on PostgreSQL via Testcontainers, rather
than relying only on mocked repositories.

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

Store each model prediction with provider/model, prompt/schema/parser versions,
run identifier, source references, and validation result. Store review labels
separately with version, reviewer, household context, and reason. Distinguish a
merely accepted suggestion from a label verified against source evidence; do not
treat acceptance as automatic ground truth. A label revision preserves its
history and never silently changes the confirmed ledger.

Statement coverage comes from verified period metadata or a clear explicit
confirmation of an export's date range. A CSV's earliest and latest transaction
dates do not prove that no other transactions are missing. Reports show partial
or unknown coverage until every selected account has verified coverage.

## Evaluation, traces, and untrusted inputs

Start with a small synthetic corpus and independently verified expected rows.
Use train/dev/test splits grouped by source document: keep all pages,
re-exports, generated variants, and CSV/PDF representations of a document in the
same split. Freeze test data before prompt iteration; never use held-out labels
in prompts, retrieval, or training. Future categorization needs chronological
evaluation and a separate test for new merchants/households where relevant.

Report row precision/recall with one-to-one matching that preserves duplicate
rows; exact amount/date/flow/currency accuracy; missing and hallucinated rows;
whole-document correctness; reviewer corrections; validation failures/retries;
cost per document; and latency. Publish matching rules, denominators, dataset
size, and versions. Compare CSV and PDF only after checking their coverage and
date semantics. CSV rows and unverified review acceptance are not automatically
ground truth. Reconciliation is a separate check when source evidence supports
it, not proof that every row is right. Small corpora do not establish stable
latency percentiles or broad accuracy claims.

Trace from the first provider call. Standard traces store identifiers, versions,
token usage, cost estimates with pricing basis, timings, and bounded issue/error
codes. Do not log raw source text, prompts, outputs, or transaction descriptions.
Optional detailed debug payloads stay private with explicit opt-in, household
access checks, retention, and deletion, including cleanup of provider files.
Private statements, predictions, and labels remain outside Git; published
fixtures and result artifacts use synthetic data.

Treat document text as untrusted data. Include merchant instructions, hidden PDF
text, attempts to change amounts/kinds, and requests to expose secrets or take
actions in injection fixtures. The extractor has no ledger-write tools; source
instructions cannot authorize actions. Check that attacks do not change factual
fields or produce unsupported rows. Repeat scope/injection tests for the later
analysis agent and MCP adapter.

## Confirmation acceptance criteria

- Invalid required fields cannot become confirmed entries.
- One staged row can create at most one ledger entry.
- An interrupted or repeated confirmation cannot produce partial or duplicate
  totals.
- Importing a purchase, a refund, and a card payment yields the expected net
  spending, with the payment excluded.
- Re-importing an identical file does not change the ledger.
- Two legitimate identical purchases remain representable.
- Nonmembers and revoked members cannot access a household's files, jobs, rows,
  prediction/label artifacts, traces, or confirmed entries; role permissions and
  nested references are checked on every operation.
- A valid shared-household member can access shared data within the role policy;
  cross-household references never establish access.
