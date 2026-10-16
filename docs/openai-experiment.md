# OpenAI synthetic PDF experiment

This optional Java CLI sends two freshly generated, one-page synthetic PDFs to
the OpenAI Responses API and compares extracted rows with independent labels
and the paired CSV baseline. It does not accept arbitrary input files, expose
an HTTP upload endpoint, or write ledger entries. The offline fake-provider
command remains available without credentials/network access.

## Configure and run

Use Java 21 and configure `OPENAI_API_KEY` in the process environment through
your local secret mechanism. Do not put a real key in source, command arguments,
chat, or a committed environment file. `.env.example` documents the variable;
the launcher does not load `.env` automatically.

First view the options from the repository root:

```powershell
.\tools\run-openai-experiment.cmd --help
```

On macOS/Linux use `sh tools/run-openai-experiment.sh --help`. These launchers
package the backend, then launch only the experiment entry point using its
bundled Jackson dependency; they do not start Spring or need a database/MFA key.
The build may download Maven dependencies. Full tests are run separately by CI.

Choose a PDF/vision and Structured Outputs capable model available to your API
project. No model or spending amount is selected automatically. A live command
has this form; replace the placeholders with your actual configuration:

```powershell
.\tools\run-openai-experiment.cmd --allow-live --model MODEL_ID --output .tools/openai-runs/run-001 --max-cost-usd RUN_BUDGET --request-cost-ceiling-usd REQUEST_CEILING --pricing-basis "Your reviewed pricing/reservation basis"
```

The shell launcher accepts the same arguments. USD arguments are positive
decimal dollar strings, not cents. Each HTTP attempt reserves the full declared
request ceiling before sending; failed/timed-out/retried requests retain it.
**This enforces the declared reservation envelope, not the provider invoice.**
The declaration must account for PDF text/page-image input and output billing.
Actual dollar estimates stay null until a verified pricing calculation exists;
token usage, when provided, is recorded separately. `model` metadata records the
requested model ID; use a model snapshot ID for reproducible future comparisons.
Set account/project controls
appropriately and inspect the selected model's current pricing before opting in.

Defaults: two total HTTP attempts, one attempt per document, 2,048 output tokens
per request, 60 seconds per document, 180 seconds for the provider phase, one-page
generated inputs, 1 MB per PDF, 100 rows, and a bounded 1 MB response. Optional
flags are `--max-requests`, `--max-attempts`, `--max-output-tokens`,
`--timeout-seconds`, and `--deadline-seconds`; limits have tested upper bounds.
Only explicit 429/5xx responses can retry when configured; ambiguous network
failures/timeouts do not retry. Every retry consumes the shared request/cost
reservation. Redirects are disabled and the production endpoint is fixed.

## Results and limits

The output directory must be new or empty. It contains generated source files,
hash/split manifest, reviewed-label history, explicit `run-plan.jsonl`,
`predictions.jsonl`, `run-metadata.jsonl`, `evaluation.jsonl`, and a short run note.
Predictions and metadata are appended after each completed document, preserving
completed outcomes if a later request is interrupted. Full provider responses,
refusal text, prompts, and API keys are not ordinary trace payloads. Structured
row values are private prediction artifacts, separate from metadata.

The adapter sends inline PDF bytes with `store:false`, without creating provider
file uploads, retrieval resources, tools, or shared conversation state. Before
sending, it checks the source hash and bounded generated-PDF shape. There is no
automatic real-statement upload or live call in Maven tests/CI. Inline input and
`store:false` do not constitute a general zero-retention guarantee.

Responses are parsed strictly, with duplicate/trailing JSON rejection and local
field/type/size validation. Refusal, incomplete output, HTTP/transport failures,
invalid schema, and valid-but-wrong row fields remain distinct outcomes. Schema
validity does not establish factual correctness. Required unresolved values
remain null/flagged; accepted suggestions never silently become ledger entries.

Only two underlying document groups are present. Synthetic source anchors make
one-to-one scoring independent of dates/amounts; they do not establish robust
source matching on native bank PDFs. These fixtures are smoke tests, not a
held-out model benchmark. Corpus v2 labels PDF descriptions using the whitespace
actually rendered in the PDF, while CSV labels retain quoted embedded newlines.
The change corrects source evidence before any real model evaluation.

Current implementation and verified results live in [next move](next-move.md).
A real run still needs a chosen model, configured key, and approved limits. Next
expand independent source groups, freeze evaluation splits before prompt tuning,
measure real failures, and refine persistence from observed review needs.

API implementation references: [PDF/file inputs](https://developers.openai.com/api/docs/guides/file-inputs)
and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
