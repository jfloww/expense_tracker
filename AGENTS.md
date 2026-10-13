# Project guidance

Read README.md and docs/architecture.md before changing module boundaries.

This is a public, multi-user expense tracker. Web comes first; mobile reuses the
API. Capital One, Chase, and Amex are planned issuers, not implemented parsers.

- Keep user data scoped to the authenticated user on the server. Never trust a
  client-supplied owner ID. Validate ownership on every referenced object.
- The scaffold's model validation is not complete API authorization. Any future
  data endpoint needs cross-user access tests before it is considered complete.
- Store money as integer minor units and an explicit currency. USD cents are the
  first supported interpretation. Do not use binary floats or silently add
  currencies. AI may propose labels; code calculates totals.
- Preserve original descriptions and source statement/row/page references.
- Stage imports for review. Only confirmed ledger transactions enter reports.
- Treat identical transaction fingerprints as duplicate candidates, not proof.
  Two purchases at the same merchant on one day can both be legitimate.
- Apply refunds as negative spending in their posted month. Exclude income,
  transfers, card payments, and unresolved adjustments from spending totals.
- Do not claim a month is complete without coverage for all selected accounts.
- Category corrections create rules only when the user explicitly chooses that
  behavior. Never silently rewrite historical transactions.
- Keep real statements, secrets, and user transaction logs out of source control
  and test fixtures. Use synthetic data in demos and tests.
- Put optional model providers, RAG, and MCP behind service interfaces. Do not
  add live provider calls without request limits, ownership checks, and consent
  to process the relevant data.

Validation from the workspace root:

```powershell
python -m unittest discover -s apps/api/tests -t apps/api -p test_spending_changes.py
.\.venv\Scripts\python.exe apps/api/manage.py check
.\.venv\Scripts\python.exe apps/api/manage.py makemigrations --check --dry-run
.\.venv\Scripts\python.exe apps/api/manage.py test tests
```

Report what works and what is reserved for later. A health endpoint and models
do not constitute a finished authenticated product.
