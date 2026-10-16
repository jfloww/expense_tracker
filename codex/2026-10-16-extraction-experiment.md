# Offline extraction experiment, 2026-10-16

The first extraction/evaluation slice is a standalone Java 21 program under
`backend/src/main/java/com/expensetracker/experiments/extraction`. It runs without
Spring startup, database tables, Docker, API credentials, dependencies beyond
the JDK, or network calls. The proposed statement schema remains provisional.

## Reproduce

From the repository root with `JAVA_HOME` pointing to a Java 21 JDK:

```powershell
.\tools\run-extraction-experiment.cmd
```

On macOS/Linux:

```sh
sh tools/run-extraction-experiment.sh
```

An optional first argument supplies the output directory; relative paths follow
the current working directory. The default is a new ignored directory under
`.tools/extraction-runs/`. Existing nonempty outputs are rejected. From compiled
backend classes the equivalent entry point is:

```sh
java -cp target/classes com.expensetracker.experiments.extraction.ExtractionExperiment --output target/extraction-experiment
```

Output contains `manifest.jsonl`, `predictions.jsonl`, `reviewed-labels.jsonl`,
`run-metadata.jsonl`, `evaluation.jsonl`, `experiment-note.md`, and generated
`corpus/*.pdf`/`*.csv`. Source hashes and versions identify inputs/results.
Predictions preserve raw tokens and nullable normalized values; normalized
minor-unit amounts serialize as canonical integer strings. Labels preserve
revision history and accepted-unverified versus source-verified status. Run
metadata has only IDs, versions, timing, status, and bounded validation codes;
unknown provider/model/usage/cost values are null.

## Corpus, baseline, and observed results

The invented credit-card statement has five transactions, including two real
duplicate-looking purchases, a refund, a payment, and an amount exceeding
JavaScript's safe integer range. The invented bank statement has six rows,
including a debit-card purchase, income, card payment, transfer, cash withdrawal,
and an instruction-like description. These are not native issuer layouts.

The credit group is DEV: CSV plus PDF, refusal, and incomplete scenarios. The
bank group is TEST: paired CSV/PDF. Every representation of each statement stays
in its group/split. There are 32 latest source-verified document-row labels and
one older accepted-unverified revision. This hard-coded software fixture corpus
does not establish held-out model generalization.

The deterministic parser reads `source_ref,posted_date,description,amount,currency,
flow,kind` columns with ISO dates, positive decimal USD amounts with two fraction
digits, explicit debit/credit flow, and explicit kind. It handles quoted commas,
escaped quotes, and quoted newlines; rejects malformed headers/records/UTF-8;
and bounds file size, field size, and row count. It reads source files, not labels.

Observed from the Windows and shell launchers on 2026-10-16:

| Scope | Documents | Expected rows | Predicted | Matched | Missing | Extra | Entirely correct documents |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CSV baseline | 2 | 11 | 11 | 11 | 0 | 0 | 2 |
| Scripted PDF scenarios | 4 | 21 | 12 | 10 | 11 | 2 | 0 |
| Combined fixture run | 6 | 32 | 23 | 21 | 11 | 2 | 2 |

The CSV baseline matches all eleven known values for each scored field. Scripted
PDF amount accuracy is 7/21 and posted-date accuracy 8/21; missing rows and
unresolved values stay in their known-truth denominators. Four predicted rows
have validation issues; other deliberately wrong facts have valid shapes.
One extra row is unlocated. Refusal/incomplete scenarios retain their status and
missing-row counts rather than appearing as successful empty documents.

Matching uses document ID and an annotated synthetic source anchor, independently
of amounts/dates/other scored fields. The first occurrence of a repeated predicted
anchor is used; later occurrences are extra rows even if their values are better.
This preserves legitimate identical purchases with different source occurrences.
Exact field comparisons include source locator and original description. Unknown
truth is reported separately; null versus null never earns correctness credit.

## Limits and next experiment

The fake PDF adapter checks a bounded PDF input and returns scripted predictions;
it does not extract PDF text or invoke a model. Corrupted fields, omissions,
hallucinations, refusal, and incomplete output exercise the pipeline/scorer.
An instruction-like description deliberately yields a corrupted fake amount;
detecting it is not evidence of prompt-injection resistance. Generated PDFs
include synthetic anchors and verified byte-offset/xref/stream structure; real
model source-location matching remains untested. Empty reference documents are
outside this scorer's current fixture scope.

This run measures software behavior and local execution duration. It supplies
no model accuracy, provider latency, token, or price evidence. No real statements
were used, no ledger entries were created, and no provider was contacted.

Next add a separately opted-in, bounded provider run against synthetic PDFs and
the same independent labels, then inspect one actual issuer/account layout
privately. Expand independent documents and freeze evaluation splits before
prompt tuning. Refine persistence only after observing extraction/review needs.
Full product imports, reporting, RAG, the analysis harness, and MCP are later.

CI configuration is in [backend.yml](../.github/workflows/backend.yml): Java 21,
disposable PostgreSQL 16, Maven `verify`, then the JDK-only experiment, with
failure reports and synthetic results retained. Actions use pinned official
release commits and read-only repository permissions. Workflow configuration
was parsed locally; a first remote GitHub Actions run is still unverified.
Current full-suite verification belongs in [next-move.md](../docs/next-move.md).
