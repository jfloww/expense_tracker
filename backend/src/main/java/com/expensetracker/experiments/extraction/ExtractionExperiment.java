package com.expensetracker.experiments.extraction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;

/** Standalone JDK entry point. Does not start Spring, connect to a database, or call a provider. */
public final class ExtractionExperiment {
    private ExtractionExperiment() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !arguments[0].equals("--output") || arguments[1].isBlank()) {
            throw new IllegalArgumentException("Usage: ExtractionExperiment --output <new-or-empty-directory>");
        }
        Path output = run(Path.of(arguments[1]));
        System.out.println("Offline synthetic extraction experiment written to " + output);
        System.out.println("CSV baseline parses invented CSV; FAKE PDF outputs are scripted, not model measurements.");
    }

    public static Path run(Path requestedOutput) throws IOException {
        Path output = requestedOutput.toAbsolutePath().normalize();
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IOException("Output must be a directory");
            try (var existing = Files.list(output)) {
                if (existing.findAny().isPresent()) throw new IOException("Output directory must be empty; existing runs are immutable");
            }
        } else Files.createDirectories(output);
        String runId = "offline-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        var corpus = SyntheticCorpus.create(output);
        var predictions = new ArrayList<Prediction>();
        var metadata = new ArrayList<Map<String, ?>>();
        var manifest = new ArrayList<Map<String, ?>>();
        for (Document document : corpus.documents()) {
            Extractor extractor = document.format() == SourceFormat.CSV ? new SyntheticCsvExtractor() : new FakePdfExtractor();
            long started = System.nanoTime();
            Prediction prediction = extractor.extract(document);
            long elapsed = System.nanoTime() - started;
            predictions.add(prediction);
            String method = document.format() == SourceFormat.CSV ? "CSV_BASELINE" : "FAKE";
            List<String> validationCodes = java.util.stream.Stream.concat(prediction.issues().stream(),
                    prediction.rows().stream().flatMap(row -> row.issues().stream())).distinct().sorted().toList();
            metadata.add(JsonLines.object("artifactVersion", VERSION, "runId", runId, "predictionId", prediction.predictionId(),
                    "documentId", document.documentId(), "groupId", document.groupId(), "split", document.split(),
                    "method", method, "provider", null, "model", null, "promptVersion", null,
                    "parserVersion", document.format() == SourceFormat.CSV ? "synthetic-csv-v1" : "scripted-pdf-v1",
                    "schemaVersion", VERSION, "status", prediction.status(), "localDurationNanos", elapsed,
                    "inputTokens", null, "outputTokens", null, "estimatedCost", null, "pricingBasis", null,
                    "validationCodes", validationCodes));
            manifest.add(JsonLines.object("corpusVersion", SyntheticCorpus.VERSION, "documentId", document.documentId(),
                    "groupId", document.groupId(), "split", document.split(), "sourceFormat", document.format(),
                    "accountKind", document.accountKind(), "syntheticAccountId", document.accountId(),
                    "relativePath", output.relativize(document.path()).toString().replace('\\', '/'),
                    "sha256", sha256(document.path())));
        }
        var scorer = new ExtractionScorer();
        var overall = scorer.evaluate(predictions, corpus.labels());
        var baseline = scorer.evaluate(select(predictions, "csv"), labelsFor(corpus.labels(), select(predictions, "csv")));
        List<Prediction> fakePredictions = predictions.stream().filter(value -> !value.documentId().endsWith("-csv")).toList();
        var fake = scorer.evaluate(fakePredictions, labelsFor(corpus.labels(), fakePredictions));
        if (baseline.correctDocuments() != baseline.totalDocuments()) {
            throw new IllegalStateException("Deterministic CSV baseline does not match independently specified labels");
        }
        JsonLines.write(output.resolve("manifest.jsonl"), manifest);
        JsonLines.write(output.resolve("predictions.jsonl"), predictions.stream().map(ExtractionExperiment::predictionJson).toList());
        JsonLines.write(output.resolve("reviewed-labels.jsonl"), corpus.labels().stream().map(ExtractionExperiment::labelJson).toList());
        JsonLines.write(output.resolve("run-metadata.jsonl"), metadata);
        JsonLines.write(output.resolve("evaluation.jsonl"), List.of(
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "ALL", "report", overall.asMap()),
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "CSV_BASELINE", "report", baseline.asMap()),
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "FAKE_PDF", "report", fake.asMap())));
        String note = "# Offline extraction experiment\n\n"
                + "Run: " + runId + "; started " + startedAt + ".\n\n"
                + "Six invented documents in two source groups; 32 latest source-verified row labels, plus one older accepted/unverified revision. "
                + "DEV contains four representations/scenarios of the same credit document; TEST contains the paired bank documents. "
                + "This tiny, hard-coded corpus validates software behavior, not held-out model generalization.\n\n"
                + "CSV baseline: " + baseline.correctDocuments() + "/" + baseline.totalDocuments() + " documents exact. "
                + "Scripted PDF provider: " + fake.correctDocuments() + "/" + fake.totalDocuments() + " documents exact; "
                + "controlled errors include missing/extra/unlocated rows, wrong amounts/dates/flows/currencies/kinds, invalid fields, refusal and incomplete output.\n\n"
                + "The fake adapter checks PDF input but does not parse its text or call a model. It never reads reviewed labels or CSV answers. "
                + "The valid generated PDFs annotate synthetic source anchors; real model location/matching remains unverified. "
                + "The CSV baseline implements only the documented synthetic layout, not a verified issuer format.\n\n"
                + "An instruction-like merchant description deliberately produces a corrupted fake amount for evaluation. "
                + "This tests detection of a scripted field error, not prompt-injection resistance. "
                + "No provider timing, token usage, price, or model accuracy is measured; unknown provider fields are null. "
                + "Raw source values occur only in these explicit synthetic experiment artifacts, not ordinary application traces.\n\n"
                + "Next: inspect one actual issuer/account layout privately, independently label more paired documents, freeze grouped splits, "
                + "then add a separately opted-in bounded provider adapter and measure real extraction failures. "
                + "Database staging, web review, ledger confirmation, RAG, analysis harness and MCP remain pending.\n";
        Files.writeString(output.resolve("experiment-note.md"), note, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return output;
    }

    private static List<Prediction> select(List<Prediction> predictions, String suffix) {
        return predictions.stream().filter(value -> value.documentId().endsWith("-" + suffix)).toList();
    }

    private static List<ReviewedLabel> labelsFor(List<ReviewedLabel> labels, List<Prediction> predictions) {
        Set<String> ids = predictions.stream().map(Prediction::documentId).collect(Collectors.toSet());
        return labels.stream().filter(value -> ids.contains(value.documentId())).toList();
    }

    public static Map<String, Object> predictionJson(Prediction prediction) {
        return JsonLines.object("artifactVersion", VERSION, "predictionId", prediction.predictionId(),
                "documentId", prediction.documentId(), "status", prediction.status(), "issues", prediction.issues(),
                "rows", prediction.rows().stream().map(row -> JsonLines.object("source", sourceJson(row.source()),
                        "raw", rawJson(row.raw()), "originalDescription", row.originalDescription(), "postedDate", row.postedDate(),
                        "amountMinor", row.amountMinor() == null ? null : row.amountMinor().toString(), "currency", row.currency(),
                        "flow", row.flow(), "kind", row.kind(), "issues", row.issues())).toList());
    }

    public static Map<String, Object> labelJson(ReviewedLabel label) {
        return JsonLines.object("artifactVersion", VERSION, "labelId", label.labelId(), "version", label.version(),
                "documentId", label.documentId(), "source", sourceJson(label.source()), "originalDescription", label.originalDescription(),
                "postedDate", label.postedDate(), "amountMinor", label.amountMinor() == null ? null : label.amountMinor().toString(),
                "currency", label.currency(), "flow", label.flow(), "kind", label.kind(), "verification", label.verification());
    }

    private static Map<String, Object> sourceJson(Source source) {
        return source == null ? null : JsonLines.object("rowRef", source.rowRef(), "page", source.page(), "csvRecord", source.csvRecord());
    }

    private static Map<String, Object> rawJson(RawRow raw) {
        return raw == null ? null : JsonLines.object("source", sourceJson(raw.source()), "description", raw.description(),
                "postedDate", raw.postedDate(), "amount", raw.amount(), "currency", raw.currency(), "flow", raw.flow(), "kind", raw.kind());
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 primitive unavailable", unavailable);
        }
    }
}
