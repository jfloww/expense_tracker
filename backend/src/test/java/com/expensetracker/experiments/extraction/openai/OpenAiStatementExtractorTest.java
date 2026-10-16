package com.expensetracker.experiments.extraction.openai;

import com.expensetracker.experiments.extraction.SyntheticCorpus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiStatementExtractorTest {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private Document document;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger reservations = new AtomicInteger();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private URI endpoint;

    @BeforeEach
    void setup() throws Exception {
        document = SyntheticCorpus.create(Files.createDirectory(temporary.resolve("fixtures"))).documents().stream()
                .filter(value -> value.documentId().equals("credit-pdf")).findFirst().orElseThrow();
        response.set(completed(payload(row("12.50", "2026-08-02"))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-request-id", "req_mock_123");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses");
    }

    @AfterEach
    void cleanup() {
        server.stop(0); executor.shutdownNow();
    }

    @Test
    void sendsOnlyInlinePdfWithStrictSchemaAndNormalizesRawFieldsLocally() throws Exception {
        var extractor = adapter(1, Duration.ofSeconds(5));
        var result = extractor.extractWithMetadata(document);
        assertEquals(RunStatus.SUCCEEDED, result.prediction().status());
        assertEquals(1250L, result.prediction().rows().getFirst().amountMinor());
        assertEquals("12.50", result.prediction().rows().getFirst().raw().amount());
        assertEquals("req_mock_123", result.metadata().requestId());
        assertEquals("resp_mock", result.metadata().responseId());
        assertEquals(120L, result.metadata().inputTokens());
        assertEquals(5L, result.metadata().cachedInputTokens());
        assertEquals(1, result.metadata().attempts()); assertEquals(1, reservations.get());
        var request = json.readTree(received.get());
        assertFalse(request.get("store").booleanValue());
        assertEquals("explicit-test-model", request.get("model").asText());
        assertTrue(request.get("text").get("format").get("strict").booleanValue());
        assertFalse(request.get("text").get("format").get("schema").get("additionalProperties").booleanValue());
        var file = request.get("input").get(0).get("content").get(0);
        assertEquals("input_file", file.get("type").asText());
        assertEquals("low", file.get("detail").asText());
        assertTrue(file.get("file_data").asText().startsWith("data:application/pdf;base64,JVBER"));
        assertFalse(received.get().contains("reviewed-label"));
        assertFalse(received.get().contains("label-credit"));
        assertFalse(received.get().contains("CSV_BASELINE"));
        assertFalse(result.toString().contains("Merchant"));
        assertFalse(config(1, Duration.ofSeconds(5)).toString().contains("synthetic-api-key"));
    }

    @Test
    void preservesSemanticFailuresAsUnresolvedRawEvidence() throws Exception {
        response.set(completed(payload(row("0.00", "2026-13-02"))));
        var result = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
        var row = result.prediction().rows().getFirst();
        assertNull(row.amountMinor()); assertNull(row.postedDate());
        assertEquals("0.00", row.raw().amount()); assertEquals("2026-13-02", row.raw().postedDate());
        assertTrue(result.metadata().codes().containsAll(List.of("invalid_amount", "invalid_posted_date")));
    }

    @Test
    void rejectsSchemaViolationsDuplicateKeysTrailingJsonAndNumericMoney() throws Exception {
        String valid = row("12.50", "2026-08-02");
        for (String invalid : List.of(
                "{\"rows\":[" + valid.replace("\"amount\":\"12.50\"", "\"amount\":12.50") + "]}",
                payload(valid.replace("\"page\":1", "\"page\":2")),
                payload(valid.replace("\"page\":1", "\"page\":1.0")),
                payload(valid.replace("\"amount\":\"12.50\"", "\"amount\":\"12.50\",\"amount\":\"13.00\"")),
                payload(valid) + " {}",
                "{\"rows\":[" + valid + "],\"unexpected\":true}",
                payload(valid.replace(",\"currency\":\"USD\"", "")),
                "{\"rows\":[" + String.join(",", java.util.Collections.nCopies(101, valid)) + "]}")) {
            response.set(completed(invalid));
            var result = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
            assertEquals(RunStatus.FAILED, result.prediction().status());
            assertEquals(List.of("invalid_provider_response"), result.metadata().codes());
            assertTrue(result.prediction().rows().isEmpty());
        }
        response.set(completed(payload(valid)) + " {}");
        assertEquals(RunStatus.FAILED, adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document).prediction().status());
    }

    @Test
    void handlesRefusalIncompleteUnknownUsageAndNeverCopiesProviderBodyIntoErrors() throws Exception {
        response.set(json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message",
                "content", List.of(Map.of("type", "refusal", "refusal", "PRIVATE_PROVIDER_ERROR synthetic-api-key")))))));
        var refused = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(RunStatus.REFUSED, refused.prediction().status());
        assertNull(refused.metadata().inputTokens());
        assertFalse(refused.toString().contains("PRIVATE_PROVIDER_ERROR"));
        response.set("{\"status\":\"incomplete\",\"output\":[]}");
        assertEquals(RunStatus.INCOMPLETE, adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document).prediction().status());
        response.set("PRIVATE_PROVIDER_ERROR synthetic-api-key"); status.set(400);
        var failed = adapter(2, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(List.of("provider_http_error"), failed.metadata().codes());
        assertEquals(1, failed.metadata().attempts());
        assertFalse(failed.toString().contains("synthetic-api-key"));
    }

    @Test
    void retriesOnlyExplicitRateOrServerErrorsAndReservesEveryAttempt() throws Exception {
        status.set(429); response.set("private response body");
        var result = adapter(2, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(2, requests.get()); assertEquals(2, reservations.get());
        assertEquals(2, result.metadata().attempts());
        assertEquals(List.of("provider_rate_limited"), result.metadata().codes());
        var denied = new OpenAiStatementExtractor(config(2, Duration.ofSeconds(5)), () -> {
            throw new IllegalStateException("private budget detail");
        }, endpoint).extractWithMetadata(document);
        assertEquals(0, denied.metadata().attempts()); assertEquals(2, requests.get());
        assertEquals(List.of("request_budget_exhausted"), denied.metadata().codes());
    }

    @Test
    void invalidConfigurationSourceSizeAndGlobalDeadlineSendNoRequest() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiStatementExtractor.Config("synthetic-api-key", "bad\nmodel", 100, 128, Duration.ofSeconds(5), 1));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiStatementExtractor.Config("secret\nkey", "test", 100, 128, Duration.ofSeconds(5), 1));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiStatementExtractor.Config("synthetic-api-key", "test", 100, 128, Duration.ofSeconds(5), 3));
        var small = new OpenAiStatementExtractor(new OpenAiStatementExtractor.Config("synthetic-api-key", "test", 10, 128, Duration.ofSeconds(5)), reservations::incrementAndGet, endpoint);
        assertEquals(List.of("input_too_large"), small.extractWithMetadata(document).metadata().codes());
        var expired = new OpenAiStatementExtractor(config(1, Duration.ofSeconds(5)), new OpenAiStatementExtractor.AttemptBudget() {
            public void reserveAttempt() { reservations.incrementAndGet(); }
            public Duration remainingTime() { return Duration.ZERO; }
        }, endpoint);
        assertEquals(List.of("deadline_exceeded"), expired.extractWithMetadata(document).metadata().codes());
        var csv = new Document("bad", "group", Split.DEV, SourceFormat.CSV, AccountKind.CREDIT_CARD, "account", document.path());
        assertThrows(IllegalArgumentException.class, () -> adapter(1, Duration.ofSeconds(5)).extractWithMetadata(csv));
        assertEquals(0, requests.get()); assertEquals(0, reservations.get());
    }

    @Test
    void sourceHashIsCheckedAgainstActualBytesBeforeAnyReservationOrRequest() throws Exception {
        var extractor = adapter(1, Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> extractor.extractWithMetadata(document, "not-a-hash"));
        var mismatched = extractor.extractWithMetadata(document, "0".repeat(64));
        assertEquals(List.of("source_hash_mismatch"), mismatched.metadata().codes());
        assertEquals(0, requests.get()); assertEquals(0, reservations.get());
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(document.path())));
        assertEquals(RunStatus.SUCCEEDED, extractor.extractWithMetadata(document, expected).prediction().status());
        assertEquals(1, requests.get()); assertEquals(1, reservations.get());
        Files.writeString(document.path(), "\nchanged", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(List.of("source_hash_mismatch"), extractor.extractWithMetadata(document, expected).metadata().codes());
        assertEquals(1, requests.get()); assertEquals(1, reservations.get());
    }

    @Test
    void unknownOrMalformedUsageIsNotReportedAsZeroAndProviderIdentifiersAreSanitized() throws Exception {
        response.set("{\"id\":\"provider private body\",\"status\":\"incomplete\",\"usage\":{\"input_tokens\":\"120\",\"output_tokens\":-1,\"input_tokens_details\":{\"cached_tokens\":4.5}},\"output\":[]}");
        var result = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(RunStatus.INCOMPLETE, result.prediction().status());
        assertNull(result.metadata().responseId());
        assertNull(result.metadata().inputTokens()); assertNull(result.metadata().outputTokens());
        assertNull(result.metadata().cachedInputTokens());
        assertFalse(result.toString().contains("provider private body"));
        assertEquals("req_mock_123", result.metadata().requestId());
    }

    @Test
    void redirectsAreNeverFollowedAndLargeChunkedResponsesAreAborted() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.removeContext("/responses");
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet(); exchange.getResponseHeaders().add("Location", endpoint.resolve("/redirected").toString());
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        var result = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(RunStatus.FAILED, result.prediction().status()); assertEquals(0, redirected.get());
        server.removeContext("/responses");
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(new byte[1_000_001]); }
        });
        var large = adapter(1, Duration.ofSeconds(5)).extractWithMetadata(document);
        assertEquals(List.of("response_too_large"), large.metadata().codes());
    }

    @Test
    void bodyTimeoutIsBoundedAndNeverRetried() throws Exception {
        server.removeContext("/responses");
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0);
            try { Thread.sleep(1_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var result = adapter(2, Duration.ofMillis(250)).extractWithMetadata(document);
        assertEquals(List.of("request_timeout"), result.metadata().codes());
        assertEquals(1, result.metadata().attempts()); assertEquals(1, requests.get()); assertEquals(1, reservations.get());
    }

    private OpenAiStatementExtractor adapter(int attempts, Duration timeout) {
        return new OpenAiStatementExtractor(config(attempts, timeout), reservations::incrementAndGet, endpoint);
    }
    private static OpenAiStatementExtractor.Config config(int attempts, Duration timeout) {
        return new OpenAiStatementExtractor.Config("synthetic-api-key", "explicit-test-model", 1_000_000, 1024, timeout, attempts);
    }
    private static String payload(String row) { return "{\"rows\":[" + row + "]}"; }
    private static String row(String amount, String date) {
        return "{\"source_ref\":\"c1\",\"page\":1,\"description\":\"Merchant\",\"date\":\"" + date
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\",\"flow\":\"DEBIT\",\"kind\":\"EXPENSE\"}";
    }
    private String completed(String payload) {
        return json.writeValueAsString(Map.of("id", "resp_mock", "status", "completed", "usage",
                Map.of("input_tokens", 120, "output_tokens", 30, "input_tokens_details", Map.of("cached_tokens", 5)),
                "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", payload))))));
    }
}
