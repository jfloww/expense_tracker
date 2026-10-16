package com.expensetracker.experiments.extraction.openai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiExtractionExperimentTest {
    private static final String KEY = "synthetic-cli-test-key";
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test
    void requiresExplicitConsentModelAndCostOptionsAndRejectsRepeatedUnknownOrMissingArguments() {
        Path output = temporary.resolve("rejected");
        for (String name : List.of("--allow-live", "--model", "--max-cost-usd", "--request-cost-ceiling-usd", "--pricing-basis")) {
            var arguments = arguments(output, "0.02");
            int index = arguments.indexOf(name);
            arguments.remove(index);
            if (!name.equals("--allow-live")) arguments.remove(index);
            assertThrows(IllegalArgumentException.class, () -> parse(arguments), name);
        }
        for (List<String> suffix : List.of(List.of("--allow-live"), List.of("--model", "other-model"),
                List.of("--input", "private-statement.pdf"), List.of("--output"), List.of("--max-attempts", "3"))) {
            var arguments = arguments(output, "0.02");
            arguments.addAll(suffix);
            assertThrows(IllegalArgumentException.class, () -> parse(arguments));
        }
        var accepted = parse(arguments(output, "0.02"));
        assertEquals("explicit-test-model", accepted.limits().model());
        assertEquals(2, accepted.limits().maxRequests());
        assertEquals(1, accepted.maxAttempts());
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsMissingOrInvalidKeyBeforeCreatingAnyOutputOrAdapter() {
        Path output = temporary.resolve("no-side-effects");
        var options = parse(arguments(output, "0.02"));
        var factoryCalled = new AtomicBoolean();
        OpenAiExtractionExperiment.ExtractorFactory factory = (config, budget) -> {
            factoryCalled.set(true);
            throw new AssertionError("An invalid key must be rejected before constructing the adapter");
        };
        for (String key : new String[]{null, "", "short", "synthetic-secret\nheader", "x".repeat(513)}) {
            var error = assertThrows(IllegalArgumentException.class, () -> OpenAiExtractionExperiment.run(options, key, factory));
            assertFalse(error.getMessage().contains("synthetic-secret"));
            assertFalse(Files.exists(output));
        }
        assertFalse(factoryCalled.get());
    }

    @Test
    void twoMockRequestsUseFreshPdfsOnlyAndPersistSeparateExactImmutableArtifacts() throws Exception {
        Path output = temporary.resolve("complete");
        try (var provider = new MockProvider()) {
            Path result = OpenAiExtractionExperiment.run(parse(arguments(output, "0.02")), KEY,
                    (config, budget) -> new OpenAiStatementExtractor(config, budget, provider.endpoint));
            assertEquals(output.toAbsolutePath(), result);
            assertEquals(2, provider.requests.size());
            var manifest = lines(output.resolve("manifest.jsonl"));
            assertEquals(6, manifest.size());
            for (int index = 0; index < provider.requests.size(); index++) {
                String requestText = provider.requests.get(index);
                JsonNode request = json.readTree(requestText);
                assertFalse(request.get("store").booleanValue());
                assertFalse(requestText.contains(output.toString()));
                for (String forbidden : List.of(KEY, "reviewed-label", "labelId", "verification", "credit-csv", "bank-csv", "CSV_BASELINE")) {
                    assertFalse(requestText.contains(forbidden), forbidden);
                }
                var content = request.get("input").get(0).get("content");
                assertEquals(2, content.size());
                assertEquals("input_file", content.get(0).get("type").asText());
                assertEquals("input_text", content.get(1).get("type").asText());
                String data = content.get(0).get("file_data").asText();
                assertTrue(data.startsWith("data:application/pdf;base64,"));
                byte[] sent = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
                String documentId = index == 0 ? "credit-pdf" : "bank-pdf";
                assertArrayEquals(Files.readAllBytes(output.resolve("corpus/" + documentId + ".pdf")), sent);
                assertTrue(new String(sent, StandardCharsets.US_ASCII).contains("SYNTHETIC DOCUMENT - NOT A BANK STATEMENT FORMAT"));
                String sentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sent));
                assertEquals(sentHash, by(manifest, "documentId", documentId).get("sha256").asText());
            }

            var predictions = lines(output.resolve("predictions.jsonl"));
            assertEquals(4, predictions.size());
            JsonNode exact = by(predictions, "documentId", "credit-pdf").get("rows").get(0);
            assertTrue(exact.get("amountMinor").isString());
            assertEquals("9007199254740993", exact.get("amountMinor").asText());
            assertEquals("90071992547409.93", exact.get("raw").get("amount").asText());
            var metadata = lines(output.resolve("run-metadata.jsonl"));
            assertEquals(2, metadata.size());
            for (JsonNode row : metadata) {
                assertTrue(row.get("estimatedCost").isNull());
                assertEquals(1, row.get("attempts").asInt());
                assertEquals(120, row.get("inputTokens").asInt());
                assertFalse(row.has("rows"));
                assertFalse(row.toString().contains("Synthetic large exact amount"));
            }
            assertEquals("0.02", metadata.getLast().get("totalDeclaredCostReservedUsd").asText());
            var evaluations = lines(output.resolve("evaluation.jsonl"));
            assertEquals(3, evaluations.size());
            JsonNode baseline = by(evaluations, "scope", "CSV_BASELINE").get("report");
            assertEquals(2, baseline.get("correctDocuments").asInt());
            assertEquals(11, baseline.get("rowMetrics").get("expectedRows").asInt());
            JsonNode pdf = by(evaluations, "scope", "OPENAI_PDF").get("report");
            assertEquals(9, pdf.get("rowMetrics").get("missingRows").asInt());
            assertEquals(11, pdf.get("fieldMetrics").get("amountMinor").get("expected").asInt());
            assertEquals(2, pdf.get("fieldMetrics").get("amountMinor").get("correct").asInt());
            assertEquals(23, lines(output.resolve("reviewed-labels.jsonl")).size());
            JsonNode plan = lines(output.resolve("run-plan.jsonl")).getFirst();
            assertFalse(plan.get("providerInvoiceCapGuaranteed").booleanValue());
            assertEquals("0.02", plan.get("maxCostUsd").asText());
            String note = Files.readString(output.resolve("experiment-note.md"));
            assertTrue(note.contains("not a held-out generalization benchmark"));
            try (var files = Files.walk(output)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(KEY), file.toString());
                }
            }
            byte[] saved = Files.readAllBytes(output.resolve("predictions.jsonl"));
            assertThrows(IOException.class, () -> OpenAiExtractionExperiment.run(parse(arguments(output, "0.02")), KEY,
                    (config, budget) -> new OpenAiStatementExtractor(config, budget, provider.endpoint)));
            assertArrayEquals(saved, Files.readAllBytes(output.resolve("predictions.jsonl")));
            assertEquals(2, provider.requests.size());
        }
    }

    @Test
    void declaredCostExhaustionKeepsFirstResultAndScoresTheSecondFailureWithoutSendingIt() throws Exception {
        Path output = temporary.resolve("one-reservation");
        try (var provider = new MockProvider()) {
            OpenAiExtractionExperiment.run(parse(arguments(output, "0.01")), KEY,
                    (config, budget) -> new OpenAiStatementExtractor(config, budget, provider.endpoint));
            assertEquals(1, provider.requests.size());
            var predictions = lines(output.resolve("predictions.jsonl"));
            assertEquals("SUCCEEDED", by(predictions, "documentId", "credit-pdf").get("status").asText());
            JsonNode failed = by(predictions, "documentId", "bank-pdf");
            assertEquals("FAILED", failed.get("status").asText());
            assertEquals("request_budget_exhausted", failed.get("issues").get(0).asText());
            var metadata = lines(output.resolve("run-metadata.jsonl"));
            assertEquals(0, by(metadata, "documentId", "bank-pdf").get("attempts").asInt());
            assertEquals("0.01", metadata.getLast().get("totalDeclaredCostReservedUsd").asText());
            JsonNode report = by(lines(output.resolve("evaluation.jsonl")), "scope", "OPENAI_PDF").get("report");
            JsonNode bank = by(elements(report.get("documents")), "documentId", "bank-pdf");
            assertEquals("FAILED", bank.get("status").asText());
            assertEquals(6, bank.get("rowMetrics").get("missingRows").asInt());
            assertEquals(1, bank.get("validationCodes").get("request_budget_exhausted").asInt());
        }
    }

    @Test
    void secondSourceReadFailureLeavesCompletedPredictionAndBoundedAbortMetadata() throws Exception {
        Path output = temporary.resolve("interrupted-run");
        try (var provider = new MockProvider()) {
            provider.beforeFirstResponse = () -> Files.delete(output.resolve("corpus/bank-pdf.pdf"));
            assertThrows(IOException.class, () -> OpenAiExtractionExperiment.run(parse(arguments(output, "0.02")), KEY,
                    (config, budget) -> new OpenAiStatementExtractor(config, budget, provider.endpoint)));
            assertEquals(1, provider.requests.size());
            var predictions = lines(output.resolve("predictions.jsonl"));
            assertEquals("SUCCEEDED", by(predictions, "documentId", "credit-pdf").get("status").asText());
            JsonNode failed = by(predictions, "documentId", "bank-pdf");
            assertEquals("FAILED", failed.get("status").asText());
            assertEquals("provider_run_aborted", failed.get("issues").get(0).asText());
            JsonNode metadata = by(lines(output.resolve("run-metadata.jsonl")), "documentId", "bank-pdf");
            assertEquals(0, metadata.get("attempts").asInt());
            assertEquals("0.01", metadata.get("totalDeclaredCostReservedUsd").asText());
            assertEquals(1, metadata.get("validationCodes").size());
            assertEquals("provider_run_aborted", metadata.get("validationCodes").get(0).asText());
            assertTrue(metadata.get("estimatedCost").isNull());
        }
    }

    private ArrayList<String> arguments(Path output, String budget) {
        return new ArrayList<>(List.of("--allow-live", "--output", output.toString(), "--model", "explicit-test-model",
                "--max-cost-usd", budget, "--request-cost-ceiling-usd", "0.01", "--pricing-basis",
                "Caller conservative PDF/text/output reservation; invoice unknown", "--timeout-seconds", "5", "--deadline-seconds", "10"));
    }

    private static OpenAiExtractionExperiment.Options parse(List<String> arguments) {
        return OpenAiExtractionExperiment.parse(arguments.toArray(String[]::new));
    }

    private List<JsonNode> lines(Path path) throws IOException {
        var result = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) result.add(json.readTree(line));
        return result;
    }

    private static List<JsonNode> elements(JsonNode array) {
        var result = new ArrayList<JsonNode>();
        array.forEach(result::add);
        return result;
    }

    private static JsonNode by(List<JsonNode> values, String key, String value) {
        return values.stream().filter(row -> row.get(key).asText().equals(value)).findFirst().orElseThrow();
    }

    private final class MockProvider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final URI endpoint;
        private IoAction beforeFirstResponse = () -> {};

        MockProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/responses", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (requests.size() == 1) beforeFirstResponse.run();
                Map<String, Object> row = requests.size() == 1
                        ? Map.of("source_ref", "c5", "page", 1, "description", "Synthetic large exact amount", "date", "2026-08-05",
                                "amount", "90071992547409.93", "currency", "USD", "flow", "DEBIT", "kind", "EXPENSE")
                        : Map.of("source_ref", "b1", "page", 1, "description", "Debit-card lunch", "date", "2026-08-06",
                                "amount", "15.00", "currency", "USD", "flow", "DEBIT", "kind", "EXPENSE");
                String payload = json.writeValueAsString(Map.of("rows", List.of(row)));
                byte[] response = json.writeValueAsBytes(Map.of("id", "resp_cli_mock", "status", "completed",
                        "usage", Map.of("input_tokens", 120, "output_tokens", 30), "output", List.of(Map.of("type", "message",
                                "content", List.of(Map.of("type", "output_text", "text", payload))))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("x-request-id", "req_cli_mock");
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) { body.write(response); }
            });
            server.start();
            endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses");
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
