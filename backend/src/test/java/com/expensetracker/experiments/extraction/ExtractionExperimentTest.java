package com.expensetracker.experiments.extraction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionExperimentTest {
    @TempDir Path temporary;

    @Test
    void csvReadsQuotedCommaEscapedQuotesAndMultilineRecordsWithStableOrdinals() throws Exception {
        var corpus = corpus("quoted");
        Document csv = document(corpus, "credit-csv");
        var prediction = new SyntheticCsvExtractor().extract(csv);
        assertEquals(RunStatus.SUCCEEDED, prediction.status());
        assertEquals(5, prediction.rows().size());
        assertEquals("Corner \"Market\", groceries\nSecond line", prediction.rows().getFirst().originalDescription());
        assertEquals(2, prediction.rows().getFirst().source().csvRecord());
        assertEquals(3, prediction.rows().get(1).source().csvRecord());
        assertEquals(9007199254740993L, prediction.rows().getLast().amountMinor());
        assertTrue(prediction.rows().stream().allMatch(row -> row.issues().isEmpty()));
    }

    @Test
    void csvRejectsMalformedRecordsUnsupportedHeadersAndDuplicateAnchors() throws Exception {
        String header = String.join(",", SyntheticCsvExtractor.HEADER) + "\n";
        String good = "r1,2026-08-02,Merchant,1.00,USD,DEBIT,EXPENSE\n";
        List<String> malformed = List.of(
                header + "r1,2026-08-02,\"unclosed,1.00,USD,DEBIT,EXPENSE",
                header + "r1,2026-08-02,\"closed\" trailing,1.00,USD,DEBIT,EXPENSE\n",
                header + "r1,2026-08-02,Merchant,1.00,USD,DEBIT\n",
                header + good + good,
                "date,amount\n2026-08-02,1.00\n",
                header + "r1,2026-08-02,NUL\0,1.00,USD,DEBIT,EXPENSE\n");
        for (int index = 0; index < malformed.size(); index++) {
            Path path = temporary.resolve("malformed-" + index + ".csv");
            Files.writeString(path, malformed.get(index));
            var result = new SyntheticCsvExtractor().extract(csv(path));
            assertEquals(RunStatus.FAILED, result.status());
            assertTrue(result.rows().isEmpty(), "Malformed files cannot silently return partial rows");
        }
    }

    @Test
    void csvEnforcesInputAndRowLimits() throws Exception {
        Path large = temporary.resolve("large.csv");
        Files.writeString(large, "x".repeat(SyntheticCsvExtractor.MAX_BYTES + 1));
        assertEquals(List.of("input_too_large"), new SyntheticCsvExtractor().extract(csv(large)).issues());
        Path many = temporary.resolve("many.csv");
        StringBuilder content = new StringBuilder(String.join(",", SyntheticCsvExtractor.HEADER) + "\n");
        for (int index = 0; index <= SyntheticCsvExtractor.MAX_ROWS; index++) {
            content.append("r").append(index).append(",2026-08-02,Merchant,1.00,USD,DEBIT,EXPENSE\n");
        }
        Files.writeString(many, content);
        assertEquals(RunStatus.FAILED, new SyntheticCsvExtractor().extract(csv(many)).status());
    }

    @Test
    void generatedPdfsHaveAccurateCrossReferencesStreamLengthAndVisibleSourceAnchors() throws Exception {
        var corpus = corpus("pdf");
        for (Document document : corpus.documents().stream().filter(value -> value.format() == SourceFormat.PDF).toList()) {
            byte[] bytes = Files.readAllBytes(document.path());
            String pdf = new String(bytes, StandardCharsets.US_ASCII);
            assertTrue(pdf.startsWith("%PDF-1.4\n"));
            assertTrue(pdf.endsWith("%%EOF\n"));
            assertTrue(pdf.contains("[" + (document.accountKind() == AccountKind.CREDIT_CARD ? "c1" : "b1") + "]"));
            int start = pdf.lastIndexOf("startxref\n") + "startxref\n".length();
            int xref = Integer.parseInt(pdf.substring(start, pdf.indexOf('\n', start)));
            assertTrue(pdf.substring(xref).startsWith("xref\n0 6\n"));
            String[] xrefLines = pdf.substring(xref).split("\n");
            for (int object = 1; object <= 5; object++) {
                int offset = Integer.parseInt(xrefLines[object + 2].substring(0, 10));
                assertTrue(pdf.substring(offset).startsWith(object + " 0 obj\n"));
            }
            int lengthStart = pdf.indexOf("/Length ") + "/Length ".length();
            int declared = Integer.parseInt(pdf.substring(lengthStart, pdf.indexOf(' ', lengthStart)));
            int streamStart = pdf.indexOf("stream\n") + "stream\n".length();
            assertEquals(declared, pdf.indexOf("endstream", streamStart) - streamStart);
        }
    }

    @Test
    void manifestGroupsKeepEveryRepresentationInOneSplitAndRejectLeakage() throws Exception {
        var corpus = corpus("split");
        assertEquals(6, corpus.documents().size());
        assertEquals(2, corpus.documents().stream().map(Document::groupId).distinct().count());
        SyntheticCorpus.validateManifest(corpus.documents());
        var leaked = new ArrayList<>(corpus.documents());
        Document original = leaked.getFirst();
        leaked.set(0, new Document(original.documentId(), original.groupId(), Split.TEST, original.format(),
                original.accountKind(), original.accountId(), original.path()));
        assertThrows(IllegalArgumentException.class, () -> SyntheticCorpus.validateManifest(leaked));
        var duplicated = new ArrayList<>(corpus.documents());
        duplicated.add(original);
        assertThrows(IllegalArgumentException.class, () -> SyntheticCorpus.validateManifest(duplicated));
    }

    @Test
    void fakeProviderProducesControlledErrorsWithoutReadingAnswersAndRetainsRawInvalidValues() throws Exception {
        var corpus = corpus("fake");
        var fake = new FakePdfExtractor();
        var credit = fake.extract(document(corpus, "credit-pdf"));
        assertEquals(6, credit.rows().size());
        assertEquals(1251L, credit.rows().getFirst().amountMinor());
        assertTrue(credit.rows().stream().noneMatch(row -> "c4".equals(row.source().rowRef())));
        assertTrue(credit.rows().stream().anyMatch(row -> row.source().rowRef() == null));
        var bank = fake.extract(document(corpus, "bank-pdf"));
        var invalidDate = bank.rows().stream().filter(row -> row.source().rowRef().equals("b4")).findFirst().orElseThrow();
        assertNull(invalidDate.postedDate());
        assertEquals("2026-13-09", invalidDate.raw().postedDate());
        var zero = bank.rows().stream().filter(row -> row.source().rowRef().equals("b5")).findFirst().orElseThrow();
        assertNull(zero.amountMinor()); assertEquals("0.00", zero.raw().amount());
        assertEquals(RunStatus.REFUSED, fake.extract(document(corpus, "credit-refusal")).status());
        assertEquals(RunStatus.INCOMPLETE, fake.extract(document(corpus, "credit-incomplete")).status());
        // Changing/deleting label objects cannot influence an extractor that accepts only Document.
        assertEquals(credit, fake.extract(document(corpus, "credit-pdf")));
    }

    @Test
    void jsonWriterEscapesQuotedMultilineRawFieldsAndKeepsMoneyAsStrings() {
        var raw = new RawRow(new Source("r1", 1, null), "Quote \" and newline\n", "bad-date", "0.00", "USD", "DEBIT", "EXPENSE");
        var prediction = new Prediction("p1", "d1", RunStatus.SUCCEEDED, List.of(RowNormalizer.normalize(raw)), List.of());
        String json = JsonLines.encode(ExtractionExperiment.predictionJson(prediction));
        assertTrue(json.contains("\\\" and newline\\n"));
        assertTrue(json.contains("\"raw\":{\"source\":"));
        assertTrue(json.contains("\"postedDate\":\"bad-date\""));
        assertTrue(json.contains("\"amount\":\"0.00\""));
        assertTrue(json.contains("\"amountMinor\":null"));
        var large = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Large", "2026-08-02", "90071992547409.93", "USD", "DEBIT", "EXPENSE"));
        String exact = JsonLines.encode(ExtractionExperiment.predictionJson(new Prediction("p2", "d1", RunStatus.SUCCEEDED, List.of(large), List.of())));
        assertTrue(exact.contains("\"amountMinor\":\"9007199254740993\""));
        assertThrows(IllegalArgumentException.class, () -> JsonLines.encode(Map.of("bad", Double.NaN)));
    }

    @Test
    void cliWritesSeparateImmutableArtifactsWithoutSpringOrAnExternalService() throws Exception {
        Path output = ExtractionExperiment.run(temporary.resolve("run"));
        for (String artifact : List.of("manifest.jsonl", "predictions.jsonl", "reviewed-labels.jsonl", "run-metadata.jsonl", "evaluation.jsonl", "experiment-note.md")) {
            assertTrue(Files.size(output.resolve(artifact)) > 0, artifact);
        }
        assertEquals(6, Files.readAllLines(output.resolve("predictions.jsonl")).size());
        assertEquals(33, Files.readAllLines(output.resolve("reviewed-labels.jsonl")).size());
        String metadata = Files.readString(output.resolve("run-metadata.jsonl"));
        assertTrue(metadata.contains("\"inputTokens\":null"));
        assertTrue(metadata.contains("\"estimatedCost\":null"));
        String bankTrace = Files.readAllLines(output.resolve("run-metadata.jsonl")).stream()
                .filter(line -> line.contains("\"documentId\":\"bank-pdf\"")).findFirst().orElseThrow();
        assertTrue(bankTrace.contains("invalid_posted_date"));
        assertTrue(bankTrace.contains("invalid_amount"));
        String creditTrace = Files.readAllLines(output.resolve("run-metadata.jsonl")).stream()
                .filter(line -> line.contains("\"documentId\":\"credit-pdf\"")).findFirst().orElseThrow();
        assertTrue(creditTrace.contains("unsupported_currency"));
        String predictions = Files.readString(output.resolve("predictions.jsonl"));
        assertTrue(predictions.contains("\"amountMinor\":\"9007199254740993\""));
        byte[] before = Files.readAllBytes(output.resolve("predictions.jsonl"));
        assertThrows(java.io.IOException.class, () -> ExtractionExperiment.run(output));
        assertArrayEquals(before, Files.readAllBytes(output.resolve("predictions.jsonl")));
    }

    private SyntheticCorpus.Corpus corpus(String name) throws Exception {
        return SyntheticCorpus.create(Files.createDirectory(temporary.resolve(name)));
    }

    private static Document document(SyntheticCorpus.Corpus corpus, String id) {
        return corpus.documents().stream().filter(value -> value.documentId().equals(id)).findFirst().orElseThrow();
    }

    private static Document csv(Path path) {
        return new Document("csv-test", "test-group", Split.DEV, SourceFormat.CSV, AccountKind.BANK_ACCOUNT, "synthetic", path);
    }
}
