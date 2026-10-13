# Import worker - planned

A separate Java process will run bounded import jobs using the Spring Boot
backend's application services from apps/backend, initially as another execution
mode of the same codebase rather than a separate business-logic implementation.
It will extract source rows, normalize them, validate results, and stage them
for review. It will not confirm transactions automatically.

Keep one business-logic implementation in the Java backend. Spring-managed
services enforce the same household scope, domain validation, and transaction
boundaries for HTTP and worker entry points. Do not import the legacy Django
models or start a parallel Python ledger. Choose the queue as the
first bounded PDF extraction workflow takes shape. A job needs household/account/
statement scope, privacy-safe traces and evaluation metadata,
idempotent stages, attempt limits, timeout handling, and sanitized failure data.
No queue library, worker process, or automatic retry is installed yet.

See [next move](../../docs/next-move.md) and
[the backend transition](../../docs/backend-transition.md) for PDF extraction
with OpenAI, a deterministic CSV baseline,
and later Claude comparison. An offline synthetic experiment can precede the
queue; authenticated import jobs depend on the household foundation.
