package com.expensetracker.experiments.extraction.openai;

import com.expensetracker.experiments.extraction.ExtractionExperiment;
import com.expensetracker.experiments.extraction.ExtractionScorer;
import com.expensetracker.experiments.extraction.JsonLines;
import com.expensetracker.experiments.extraction.SyntheticCorpus;
import com.expensetracker.experiments.extraction.SyntheticCsvExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;

/** Opt-in synthetic-only provider experiment. This CLI exposes no user-supplied PDF input path. */
public final class OpenAiExtractionExperiment {
    private static final Set<String> EVALUATED = Set.of("credit-csv", "credit-pdf", "bank-csv", "bank-pdf");
    private static final Set<String> VALUE_OPTIONS = Set.of("--output", "--model", "--max-cost-usd",
            "--request-cost-ceiling-usd", "--pricing-basis", "--max-output-tokens", "--timeout-seconds",
            "--max-requests", "--deadline-seconds", "--max-attempts");

    private OpenAiExtractionExperiment() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && arguments[0].equals("--help")) {
            System.out.println("Usage: --allow-live --output <new-directory> --model <model> "
                    + "--max-cost-usd <USD> --request-cost-ceiling-usd <USD> --pricing-basis <basis> "
                    + "[--max-output-tokens 2048] [--timeout-seconds 60] [--max-requests 2] "
                    + "[--deadline-seconds 180] [--max-attempts 1]");
            System.out.println("Requires OPENAI_API_KEY. Sends only freshly generated synthetic PDFs. "
                    + "Cost ceilings are caller declarations, not a guaranteed provider invoice cap.");
            return;
        }
        try {
            Options options = parse(arguments);
            String apiKey = LiveRunLimits.requireApiKey(System.getenv("OPENAI_API_KEY"));
            Path output = run(options, apiKey, OpenAiStatementExtractor::new);
            System.out.println("Synthetic OpenAI extraction artifacts written to " + output);
            System.out.println("Inspect evaluation.jsonl and run-metadata.jsonl for outcomes; "
                    + "provider failures remain visible in the results.");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            // Config validation messages contain only bounded field names/codes, never argument/key values.
            System.err.println("Live experiment configuration rejected. Use --help and configure "
                    + "OPENAI_API_KEY, model, consent, and explicit request/cost/time limits.");
            System.exit(2);
        } catch (IOException failed) {
            System.err.println("Live experiment failed; inspect any saved artifacts for completed request outcomes.");
            System.exit(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Live experiment interrupted; completed request outcomes remain in saved artifacts.");
            System.exit(130);
        }
    }

    static Options parse(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        boolean allowLive = false;
        for (int index = 0; index < arguments.length; index++) {
            String name = arguments[index];
            if (name.equals("--allow-live")) {
                if (allowLive) throw new IllegalArgumentException("Repeated live consent");
                allowLive = true;
            } else {
                if (!VALUE_OPTIONS.contains(name) || index + 1 >= arguments.length
                        || values.putIfAbsent(name, arguments[++index]) != null) {
                    throw new IllegalArgumentException("Unknown, missing, or repeated option");
                }
            }
        }
        String output = required(values, "--output");
        var limits = new LiveRunLimits(required(values, "--model"), allowLive,
                integer(values, "--max-output-tokens", 2048), SyntheticCsvExtractor.MAX_BYTES, 100,
                integer(values, "--timeout-seconds", 60), integer(values, "--max-requests", 2),
                integer(values, "--deadline-seconds", 180),
                LiveRunLimits.parseUsd(required(values, "--max-cost-usd")),
                LiveRunLimits.parseUsd(required(values, "--request-cost-ceiling-usd")),
                required(values, "--pricing-basis"));
        int maxAttempts = integer(values, "--max-attempts", 1);
        if (maxAttempts < 1 || maxAttempts > 2) throw new IllegalArgumentException("Invalid max attempts");
        return new Options(Path.of(output), limits, maxAttempts);
    }

    static Path run(Options options, String apiKey, ExtractorFactory factory) throws IOException, InterruptedException {
        apiKey = LiveRunLimits.requireApiKey(apiKey);
        LiveRunLimits limits = options.limits();
        Path output = options.output().toAbsolutePath().normalize();
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IOException("Output must be a directory");
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) throw new IOException("Output directory must be empty");
            }
        } else Files.createDirectories(output);
        String runId = "openai-synthetic-" + java.util.UUID.randomUUID();
        Instant startedAt = Instant.now();
        var corpus = SyntheticCorpus.create(output);
        List<Document> documents = corpus.documents().stream().filter(doc -> EVALUATED.contains(doc.documentId())).toList();
        List<ReviewedLabel> labels = corpus.labels().stream().filter(label -> EVALUATED.contains(label.documentId())).toList();
        SyntheticCorpus.validateManifest(corpus.documents());
        var budget = limits.newBudget(Clock.systemUTC());
        var adapterConfig = new OpenAiStatementExtractor.Config(apiKey, limits.model(), (int) limits.maxFileBytes(),
                limits.maxOutputTokens(), Duration.ofSeconds(limits.timeoutSeconds()), options.maxAttempts());
        var attemptBudget = new OpenAiStatementExtractor.AttemptBudget() {
            @Override public void reserveAttempt() { budget.reserveAttempt(); }
            @Override public Duration remainingTime() { return budget.remainingTime(); }
        };
        var adapter = factory.create(adapterConfig, attemptBudget);
        var predictions = new ArrayList<Prediction>();
        var manifest = new ArrayList<Map<String, ?>>();
        var sourceHashes = new HashMap<String, String>();
        for (Document document : corpus.documents()) {
            String sourceHash = hash(document.path());
            sourceHashes.put(document.documentId(), sourceHash);
            manifest.add(JsonLines.object("corpusVersion", SyntheticCorpus.VERSION, "documentId", document.documentId(),
                    "groupId", document.groupId(), "split", document.split(), "sourceFormat", document.format(),
                    "accountKind", document.accountKind(), "syntheticAccountId", document.accountId(),
                    "includedInEvaluation", EVALUATED.contains(document.documentId()),
                    "relativePath", output.relativize(document.path()).toString().replace('\\', '/'),
                    "sha256", sourceHash));
        }
        // Save inputs and reference labels before network processing. They never enter adapter inputs.
        JsonLines.write(output.resolve("manifest.jsonl"), manifest);
        JsonLines.write(output.resolve("reviewed-labels.jsonl"), labels.stream().map(ExtractionExperiment::labelJson).toList());
        JsonLines.write(output.resolve("run-plan.jsonl"), List.of(JsonLines.object("artifactVersion", VERSION,
                "runId", runId, "startedAt", startedAt, "syntheticOnly", true, "model", limits.model(),
                "maxRequests", limits.maxRequests(), "maxAttemptsPerDocument", options.maxAttempts(),
                "maxFileBytes", limits.maxFileBytes(), "maxPagesPerDocument", 1, "maxRows", limits.maxRows(),
                "maxOutputTokens", limits.maxOutputTokens(), "timeoutSeconds", limits.timeoutSeconds(),
                "deadlineSeconds", limits.deadlineSeconds(), "maxCostUsd", limits.maxCostUsd().toPlainString(),
                "declaredRequestCostCeilingUsd", limits.requestCostCeilingUsd().toPlainString(),
                "pricingBasis", limits.pricingBasis(), "providerInvoiceCapGuaranteed", false)));
        Path predictionPath = Files.createFile(output.resolve("predictions.jsonl"));
        Path metadataPath = Files.createFile(output.resolve("run-metadata.jsonl"));
        for (Document document : documents) {
            if (document.format() == SourceFormat.CSV) {
                Prediction baseline = new SyntheticCsvExtractor().extract(document);
                predictions.add(baseline);
                append(predictionPath, ExtractionExperiment.predictionJson(baseline));
                continue;
            }
            int previousAttempts = budget.attemptsReserved();
            OpenAiStatementExtractor.Result result;
            try {
                result = adapter.extractWithMetadata(document, sourceHashes.get(document.documentId()));
            } catch (IOException | InterruptedException aborted) {
                Prediction failed = new Prediction("openai-aborted-" + java.util.UUID.randomUUID(), document.documentId(),
                        RunStatus.FAILED, List.of(), List.of("provider_run_aborted"));
                append(predictionPath, ExtractionExperiment.predictionJson(failed));
                append(metadataPath, JsonLines.object("artifactVersion", VERSION, "runId", runId,
                        "predictionId", failed.predictionId(), "documentId", document.documentId(), "provider", "OPENAI",
                        "model", limits.model(), "status", RunStatus.FAILED, "attempts", budget.attemptsReserved() - previousAttempts,
                        "inputTokens", null, "outputTokens", null, "estimatedCost", null,
                        "totalDeclaredCostReservedUsd", budget.totalReservedUsd().toPlainString(),
                        "validationCodes", List.of("provider_run_aborted")));
                throw aborted;
            }
            predictions.add(result.prediction());
            append(predictionPath, ExtractionExperiment.predictionJson(result.prediction()));
            var details = result.metadata();
            List<String> codes = java.util.stream.Stream.concat(details.codes().stream(),
                    result.prediction().rows().stream().flatMap(row -> row.issues().stream())).distinct().sorted().toList();
            Map<String, Object> documentMetadata = JsonLines.object("artifactVersion", VERSION, "runId", runId,
                    "predictionId", result.prediction().predictionId(), "documentId", document.documentId(),
                    "groupId", document.groupId(), "split", document.split(), "provider", "OPENAI", "model", details.model(),
                    "promptVersion", OpenAiStatementExtractor.PROMPT_VERSION, "schemaVersion", OpenAiStatementExtractor.SCHEMA_VERSION,
                    "method", "LIVE_PDF", "status", result.prediction().status(), "finishStatus", details.finishStatus(),
                    "requestId", details.requestId(), "responseId", details.responseId(), "attempts", details.attempts(),
                    "inputTokens", details.inputTokens(), "outputTokens", details.outputTokens(),
                    "cachedInputTokens", details.cachedInputTokens(),
                    "localDurationNanos", details.elapsed().toNanos(), "estimatedCost", null, "pricingBasis", null,
                    "totalDeclaredCostReservedUsd", budget.totalReservedUsd().toPlainString(), "validationCodes", codes);
            append(metadataPath, documentMetadata);
        }
        var scorer = new ExtractionScorer();
        List<Prediction> csv = predictions.stream().filter(p -> p.documentId().endsWith("-csv")).toList();
        List<Prediction> pdf = predictions.stream().filter(p -> p.documentId().endsWith("-pdf")).toList();
        JsonLines.write(output.resolve("evaluation.jsonl"), List.of(
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "ALL", "report", scorer.evaluate(predictions, labels).asMap()),
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "CSV_BASELINE", "report", scorer.evaluate(csv, labelsFor(labels, csv)).asMap()),
                JsonLines.object("artifactVersion", VERSION, "runId", runId, "scope", "OPENAI_PDF", "report", scorer.evaluate(pdf, labelsFor(labels, pdf)).asMap())));
        Files.writeString(output.resolve("experiment-note.md"), "# Synthetic OpenAI extraction run\n\n"
                + "This run compares two generated synthetic PDFs with their paired CSV baseline and independent labels. "
                + "Inspect evaluation.jsonl for per-document results, missing/extra rows and exact field denominators. "
                + "Provider failures and refusals are measured outcomes, not silently accepted rows.\n\n"
                + "The tiny annotated corpus is a smoke test, not a held-out generalization benchmark. "
                + "The instruction-like bank description is an adversarial example; success on it does not establish injection resistance. "
                + "No real statement input or ledger writes are supported by this CLI.\n\n"
                + "Hard bounds cover requests, output tokens, input bytes/pages, rows, response size, and time. "
                + "Cost reservations use the caller's declared per-request ceiling and pricing basis; they do not guarantee the provider invoice. "
                + "Actual dollar estimates remain unknown unless a verified pricing calculation is added. "
                + "No full provider responses or API keys are retained in ordinary metadata.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return output;
    }

    private static List<ReviewedLabel> labelsFor(List<ReviewedLabel> labels, List<Prediction> predictions) {
        Set<String> ids = predictions.stream().map(Prediction::documentId).collect(java.util.stream.Collectors.toSet());
        return labels.stream().filter(label -> ids.contains(label.documentId())).toList();
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required option missing");
        return value;
    }

    private static int integer(Map<String, String> values, String name, int fallback) {
        String value = values.get(name);
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException("Invalid integer option");
        return Integer.parseInt(value);
    }

    private static String hash(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 unavailable", missing); }
    }

    private static void append(Path path, Map<String, ?> row) throws IOException {
        Files.writeString(path, JsonLines.encode(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    record Options(Path output, LiveRunLimits limits, int maxAttempts) {}
    @FunctionalInterface interface ExtractorFactory {
        OpenAiStatementExtractor create(OpenAiStatementExtractor.Config config, OpenAiStatementExtractor.AttemptBudget budget);
    }
}
