# Import worker — planned

A separate process will run bounded import jobs using services from apps/api.
It will extract source rows, normalize them, validate results, and stage them
for review. It will not confirm transactions automatically.

Keep one business-logic implementation in the backend. Choose the queue after
the first verified CSV adapter works. A job needs a user/statement scope,
idempotent stages, attempt limits, timeout handling, and sanitized failure data.
No queue library, worker process, or automatic retry is installed yet.
