# OpenAI adapter and CI review, 2026-10-16

This change extends the runnable offline extraction experiment with an opt-in
Java OpenAI Responses adapter and a synthetic-only CLI. Model and budget remain
undecided and no API key is configured for this task, so verification uses a
loopback provider; no real OpenAI request or model-quality measurement is claimed.

The adapter sends inline generated PDF bytes, `store:false`, a strict JSON schema,
and no tools, labels, CSV answers, or history. It checks the actual source-byte
hash before any reservation/send, validates response structure locally, preserves
raw row tokens, and normalizes fields through the same deterministic functions.
Refusal, incomplete output, invalid schema, timeout, transport error, and valid
but wrong facts remain distinguishable. Unknown usage/cost is not zero.

Explicit CLI consent/model/limits are required. Request count, bytes/pages, rows,
output tokens, response bytes, and elapsed time are bounded. Every attempt reserves
the caller's declared maximum request cost before sending, including retries.
This is a reservation envelope, not a verified invoice ceiling. Actual pricing
is unknown until a verified calculation is added; no model/rate is silently chosen.

Predictions and bounded metadata are saved after each completed document. A later
file/network interruption preserves prior results and a bounded abort record.
Provider raw bodies/refusal text and API keys are excluded from ordinary traces.
Full prediction rows are separate private artifacts. The CLI accepts no arbitrary
input path and creates no ledger/database entries or provider file resources.

Independent review found that the old generated PDF flattened description
newlines while its labels retained the CSV newline. Corpus `synthetic-corpus-v2`
now uses the PDF's rendered whitespace for PDF labels and preserves CSV newlines.
Word wrapping also avoids splitting ordinary words. This evidence correction
preceded any live model run; earlier offline results remain software fixtures.

Tests use only synthetic data and loopback HTTP. They cover strict request shape,
hash mismatch before sends, exact large-money strings, raw invalid values,
refusal/incomplete/malformed output, numeric coercion rejection, duplicate/trailing
JSON, response-byte caps, timeout without ambiguous retry, redirect rejection,
budget reservations/concurrency/deadlines, consent/credential preflight, and
preservation after a later source failure. Full-suite counts and current remote
CI status live in [next-move.md](../docs/next-move.md).

The Java-only offline launchers remain independent of the OpenAI/Jackson package.
Live launchers package dependencies, then use the experiment entry point without
starting Spring or requiring a database/MFA key. See
[the live experiment guide](../docs/openai-experiment.md) for options and limitations.

CI uses Java 21, a disposable PostgreSQL 16 service, Maven `verify`, the offline
experiment, and a packaged live-CLI help check without API calls. All three action
pins were independently checked against official GitHub tag commits; Spring Boot
and Maven distribution availability/checksums were checked against Maven Central.
No deployment or merge is part of this change.

API protocol sources consulted: [PDF/file inputs](https://developers.openai.com/api/docs/guides/file-inputs)
and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
