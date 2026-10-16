package com.expensetracker.experiments.extraction;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionScorerTest {
    private final ExtractionScorer scorer = new ExtractionScorer();
    private static final LocalDate DATE = LocalDate.of(2026, 8, 15);

    @Test
    void legitimateIdenticalPurchasesHaveIndependentSourceOccurrences() {
        var first = row("purchase-1", 1200L);
        var second = row("purchase-2", 1200L);
        var report = score(List.of(first, second), List.of(label("purchase-1", 1200L), label("purchase-2", 1200L)));

        assertEquals(new ExtractionScorer.RowMetrics(2, 2, 2, 0, 0), report.rowMetrics());
        assertEquals(1, report.correctDocuments());
        assertEquals(1.0, report.rowMetrics().precision());
        assertTrue(report.fields().values().stream().allMatch(field -> field.correct() == 2 && field.expected() == 2));
    }

    @Test
    void wrongDatesAndAmountsRemainMatchedAndCannotDisappearFromAccuracy() {
        var prediction = new ProposedRow(source("row-1"), "Synthetic purchase", DATE.plusDays(1), 1501L,
                "USD", Flow.DEBIT, Kind.EXPENSE, List.of());
        var report = score(List.of(prediction), List.of(label("row-1", 1500L)));

        assertEquals(1, report.rowMetrics().matchedRows());
        assertEquals(0, report.rowMetrics().missingRows());
        assertEquals(new ExtractionScorer.FieldMetrics(0, 1, 1, 0, 0, 0), report.fields().get("amountMinor"));
        assertEquals(0.0, report.fields().get("postedDate").accuracy());
        assertEquals(1.0, report.fields().get("originalDescription").accuracy());
        assertEquals(List.of("row-1"), report.documents().getFirst().mismatchedSourceRefs());
        assertEquals(0, report.validationFailureRows(), "Valid shape and wrong facts are separate outcomes");
        assertEquals(0, report.correctDocuments());
    }

    @Test
    void repeatedAnchorsUseFirstOccurrenceEvenWhenSecondCandidateHasCorrectFields() {
        var unanchored = new ProposedRow(null, "Synthetic purchase", DATE, 300L,
                "USD", Flow.DEBIT, Kind.EXPENSE, List.of());
        var report = score(List.of(row("row-1", 999L), row("row-1", 100L), row("extra-row", 300L), unanchored),
                List.of(label("row-1", 100L), label("missing-row", 200L)));

        assertEquals(new ExtractionScorer.RowMetrics(2, 4, 1, 1, 3), report.rowMetrics());
        assertEquals(0.25, report.rowMetrics().precision());
        assertEquals(0.5, report.rowMetrics().recall());
        assertEquals(new ExtractionScorer.FieldMetrics(0, 2, 1, 1, 0, 0), report.fields().get("amountMinor"));
        assertEquals(1, report.unanchoredRows());
        assertEquals(List.of("missing-row"), report.documents().getFirst().missingSourceRefs());
        assertEquals(List.of("row-1", "extra-row", "<unanchored:4>"), report.documents().getFirst().extraSourceRefs());
    }

    @Test
    void sourcePageIsScoredIndependentlyFromTheStableRowAnchor() {
        var movedPage = new ProposedRow(new Source("row-1", 2, null), "Synthetic purchase", DATE,
                100L, "USD", Flow.DEBIT, Kind.EXPENSE, List.of());
        var report = score(List.of(movedPage), List.of(label("row-1", 100L)));

        assertEquals(1, report.rowMetrics().matchedRows());
        assertEquals(0.0, report.fields().get("source").accuracy());
        assertEquals(1.0, report.fields().get("amountMinor").accuracy());
        assertEquals(0, report.correctDocuments());
    }

    @Test
    void unresolvedPredictionsAndMissingRowsRemainInKnownTruthDenominators() {
        var unresolved = new ProposedRow(source("row-1"), "Synthetic purchase", null, null, null, null, null, List.of());
        var report = score(List.of(unresolved), List.of(label("row-1", 100L), label("row-2", 200L)));

        assertEquals(new ExtractionScorer.FieldMetrics(0, 2, 0, 1, 1, 0), report.fields().get("amountMinor"));
        assertEquals(0.0, report.fields().get("postedDate").accuracy());
        assertEquals(1, report.validationFailureRows());
        assertEquals(1, report.validationCodes().get("invalid_amount"));
        assertEquals(1, report.validationCodes().get("invalid_posted_date"));
    }

    @Test
    void unknownTruthNeverReceivesNullEqualsNullCreditOrClaimsDocumentCorrectness() {
        var unknown = new ReviewedLabel("label-1", 1, "document-1", source("row-1"), "Synthetic purchase",
                null, null, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.SOURCE_VERIFIED);
        var prediction = new ProposedRow(source("row-1"), "Synthetic purchase", null, null,
                "USD", Flow.DEBIT, Kind.EXPENSE, List.of());
        var report = score(List.of(prediction), List.of(unknown));

        assertEquals(new ExtractionScorer.FieldMetrics(0, 0, 0, 0, 0, 1), report.fields().get("amountMinor"));
        assertNull(report.fields().get("amountMinor").accuracy());
        assertFalse(report.documents().getFirst().referenceComplete());
        assertEquals(0, report.correctDocuments());
        assertTrue(report.fields().get("amountMinor").asMap().containsKey("accuracy"));
        assertNull(report.fields().get("amountMinor").asMap().get("accuracy"));
    }

    @Test
    void latestSourceVerifiedCorrectionOverridesEarlierLabelWithoutChangingPrediction() {
        var earlier = label("row-1", 100L);
        var corrected = new ReviewedLabel("label-corrected", 2, "document-1", source("row-1"), "Synthetic purchase",
                DATE, 200L, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.SOURCE_VERIFIED);
        var report = score(List.of(row("row-1", 100L)), List.of(corrected, earlier));

        assertEquals(0.0, report.fields().get("amountMinor").accuracy());
        assertEquals(1, report.rowMetrics().expectedRows());
        var unverifiedLatest = new ReviewedLabel("label-unverified", 3, "document-1", source("row-1"),
                "Synthetic purchase", DATE, 100L, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.ACCEPTED_UNVERIFIED);
        assertThrows(IllegalArgumentException.class,
                () -> score(List.of(row("row-1", 100L)), List.of(earlier, corrected, unverifiedLatest)));
    }

    @Test
    void refusalIncompleteAndFailureExposeMissingRowsAndCannotPassAsEmptySuccess() {
        for (RunStatus status : List.of(RunStatus.REFUSED, RunStatus.INCOMPLETE, RunStatus.FAILED)) {
            var report = scorer.evaluate(List.of(new Prediction("run-1", "document-1", status, List.of(), List.of("fake_refusal"))),
                    List.of(label("row-1", 100L)));
            assertEquals(new ExtractionScorer.RowMetrics(1, 0, 0, 1, 0), report.rowMetrics());
            assertNull(report.rowMetrics().precision());
            assertEquals(0.0, report.rowMetrics().recall());
            assertEquals(status.name(), report.documents().getFirst().status());
            assertEquals(0, report.correctDocuments());
            assertEquals(1, report.validationCodes().get("fake_refusal"));
        }
    }

    @Test
    void absentDocumentPredictionIsVisibleAndDocumentOrderDoesNotChangeResults() {
        var first = label("row-1", 100L);
        var second = new ReviewedLabel("label-second", 1, "document-2", source("row-1"), "Synthetic purchase",
                DATE, 100L, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.SOURCE_VERIFIED);
        var prediction = new Prediction("run-1", "document-1", RunStatus.SUCCEEDED, List.of(row("row-1", 100L)), List.of());
        var report = scorer.evaluate(List.of(prediction), List.of(second, first));
        assertEquals(2, report.totalDocuments());
        assertEquals(1, report.correctDocuments());
        assertEquals("MISSING", report.documents().get(1).status());
        assertEquals(new ExtractionScorer.FieldMetrics(1, 2, 1, 1, 0, 0), report.fields().get("amountMinor"));
        assertEquals(report.asMap(), scorer.evaluate(List.of(prediction), List.of(first, second)).asMap());
    }

    @Test
    void financialKindsFlowCurrencyAndDescriptionUseExactIndependentComparisons() {
        var changed = new ProposedRow(source("row-1"), "Rewritten description", DATE, 100L,
                "EUR", Flow.CREDIT, Kind.REFUND, List.of("unsupported_currency", "unsupported_currency"));
        var report = score(List.of(changed), List.of(label("row-1", 100L)));
        for (String field : List.of("currency", "flow", "kind", "originalDescription")) {
            assertEquals(0.0, report.fields().get(field).accuracy(), field);
        }
        assertEquals(1, report.validationFailureRows());
        assertEquals(1, report.validationCodes().get("unsupported_currency"), "One code per affected row");
    }

    @Test
    void longAmountsAboveJavascriptSafeIntegerRangeStayExact() {
        var report = score(List.of(row("row-1", 9_007_199_254_740_992L)),
                List.of(label("row-1", 9_007_199_254_740_993L)));
        assertEquals(0.0, report.fields().get("amountMinor").accuracy());
        assertEquals(1, report.rowMetrics().matchedRows());
    }

    @Test
    void duplicateRevisionsCrossDocumentLabelReuseAndAmbiguousPredictionDocumentsAreRejected() {
        var reference = label("row-1", 100L);
        assertThrows(IllegalArgumentException.class, () -> score(List.of(row("row-1", 100L)), List.of(reference, reference)));
        var reusedLabel = new ReviewedLabel(reference.labelId(), 1, "document-2", source("row-2"),
                "Synthetic purchase", DATE, 100L, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.SOURCE_VERIFIED);
        assertThrows(IllegalArgumentException.class,
                () -> scorer.evaluate(List.of(), List.of(reference, reusedLabel)));
        var prediction = new Prediction("run-1", "document-1", RunStatus.SUCCEEDED, List.of(row("row-1", 100L)), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> scorer.evaluate(List.of(prediction, prediction), List.of(reference)));
        var foreign = new Prediction("run-foreign", "another-document", RunStatus.SUCCEEDED, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> scorer.evaluate(List.of(foreign), List.of(reference)));
    }

    @Test
    void arbitraryIssuePayloadsAreBoundedAndNoDatasetDoesNotClaimPerfectAccuracy() {
        var row = new ProposedRow(source("row-1"), "Synthetic purchase", DATE, 100L,
                "USD", Flow.DEBIT, Kind.EXPENSE, List.of("private raw statement text\n".repeat(50)));
        var report = score(List.of(row), List.of(label("row-1", 100L)));
        assertEquals(Map.of("invalid_issue_code", 1), report.validationCodes());
        var empty = scorer.evaluate(List.of(), List.of());
        assertEquals(0, empty.totalDocuments());
        assertNull(empty.asMap().get("documentAccuracy"));
        assertNull(empty.rowMetrics().precision());
        assertNull(empty.rowMetrics().recall());
        assertTrue(empty.fields().values().stream().allMatch(field -> field.accuracy() == null));
    }

    private ExtractionScorer.Report score(List<ProposedRow> rows, List<ReviewedLabel> labels) {
        return scorer.evaluate(List.of(new Prediction("run-1", "document-1", RunStatus.SUCCEEDED, rows, List.of())), labels);
    }

    private static Source source(String anchor) { return new Source(anchor, 1, null); }
    private static ProposedRow row(String anchor, Long amount) {
        return new ProposedRow(source(anchor), "Synthetic purchase", DATE, amount,
                "USD", Flow.DEBIT, Kind.EXPENSE, List.of());
    }
    private static ReviewedLabel label(String anchor, Long amount) {
        return new ReviewedLabel("label-" + anchor, 1, "document-1", source(anchor), "Synthetic purchase",
                DATE, amount, "USD", Flow.DEBIT, Kind.EXPENSE, Verification.SOURCE_VERIFIED);
    }
}
