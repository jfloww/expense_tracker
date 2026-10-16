package com.expensetracker.experiments.extraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;

/**
 * Offline fixture scoring. An annotated source anchor, never a financial value, establishes a match.
 * Duplicate predicted anchors use their first occurrence in preserved extraction order; all later
 * occurrences are extra rows, even when a later occurrence has better field values.
 */
public final class ExtractionScorer {
    private static final List<String> FIELD_NAMES = List.of(
            "postedDate", "amountMinor", "currency", "flow", "kind", "originalDescription", "source");

    /** Latest revisions must be source-verified; an older verified label is never silently substituted. */
    public Report evaluate(List<Prediction> predictions, List<ReviewedLabel> labels) {
        if (predictions == null || labels == null) throw invalid("Predictions and labels are required");
        Map<String, Map<String, ReviewedLabel>> truth = referenceLabels(labels);
        Map<String, Prediction> byDocument = new TreeMap<>();
        Set<String> predictionIds = new HashSet<>();
        for (Prediction prediction : predictions) {
            if (prediction == null) throw invalid("Null prediction");
            requireIdentifier(prediction.documentId(), "prediction document ID");
            requireIdentifier(prediction.predictionId(), "prediction ID");
            if (prediction.status() == null) throw invalid("Prediction status is required");
            if (!predictionIds.add(prediction.predictionId())) throw invalid("Repeated prediction ID");
            if (byDocument.putIfAbsent(prediction.documentId(), prediction) != null) {
                throw invalid("Evaluate only one prediction per document at a time");
            }
            if (!truth.containsKey(prediction.documentId())) {
                throw invalid("Prediction document has no reference labels");
            }
        }

        MutableRows totals = new MutableRows();
        Map<String, MutableField> fields = newFields();
        Map<String, Integer> validation = new TreeMap<>();
        List<DocumentResult> documents = new ArrayList<>();
        int correctDocuments = 0;
        int failedRows = 0;
        int unanchoredRows = 0;
        for (var document : truth.entrySet()) {
            DocumentResult result = scoreDocument(document.getKey(), byDocument.get(document.getKey()), document.getValue());
            documents.add(result);
            totals.add(result.rowMetrics());
            result.fields().forEach((name, field) -> fields.get(name).add(field));
            result.validationCodes().forEach((code, count) -> validation.merge(code, count, Math::addExact));
            failedRows = Math.addExact(failedRows, result.validationFailureRows());
            unanchoredRows = Math.addExact(unanchoredRows, result.unanchoredRows());
            if (result.correct()) correctDocuments = Math.incrementExact(correctDocuments);
        }
        return new Report(totals.freeze(), freezeFields(fields), List.copyOf(documents), immutable(validation),
                failedRows, unanchoredRows, correctDocuments, documents.size());
    }

    private DocumentResult scoreDocument(String documentId, Prediction prediction, Map<String, ReviewedLabel> truth) {
        Map<String, ProposedRow> candidates = new HashMap<>();
        List<String> extras = new ArrayList<>();
        Map<String, Integer> validation = new TreeMap<>();
        int predicted = 0;
        int unanchored = 0;
        int failedRows = 0;
        if (prediction != null) {
            Set<String> runIssues = new LinkedHashSet<>(prediction.issues());
            runIssues.forEach(issue -> addIssue(validation, issue));
            for (ProposedRow row : prediction.rows()) {
                predicted = Math.incrementExact(predicted);
                if (row == null) throw invalid("Null predicted row");
                Set<String> issues = validationIssues(row);
                if (!issues.isEmpty()) failedRows = Math.incrementExact(failedRows);
                issues.forEach(issue -> addIssue(validation, issue));
                String anchor = row.source() == null ? null : row.source().rowRef();
                if (!validAnchor(anchor)) {
                    unanchored = Math.incrementExact(unanchored);
                    extras.add("<unanchored:" + predicted + ">");
                } else if (!truth.containsKey(anchor) || candidates.putIfAbsent(anchor, row) != null) {
                    extras.add(anchor);
                }
            }
        }

        Map<String, MutableField> fields = newFields();
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        boolean referenceComplete = true;
        for (var entry : truth.entrySet()) {
            ReviewedLabel label = entry.getValue();
            ProposedRow candidate = candidates.get(entry.getKey());
            if (candidate == null) missing.add(entry.getKey());
            Map<String, Object> expectedValues = values(label);
            Map<String, Object> actualValues = candidate == null ? Map.of() : values(candidate);
            boolean exact = candidate != null;
            for (String name : FIELD_NAMES) {
                Object expected = expectedValues.get(name);
                Object actual = actualValues.get(name);
                fields.get(name).observe(expected, actual, candidate != null);
                if (expected == null) referenceComplete = false;
                if (expected == null || candidate == null || !expected.equals(actual)) exact = false;
            }
            if (candidate != null && !exact) mismatched.add(entry.getKey());
        }
        RowMetrics rowMetrics = new RowMetrics(truth.size(), predicted, candidates.size(), missing.size(), extras.size());
        boolean correct = prediction != null && prediction.status() == RunStatus.SUCCEEDED
                && missing.isEmpty() && extras.isEmpty() && mismatched.isEmpty()
                && failedRows == 0 && referenceComplete;
        return new DocumentResult(documentId, prediction == null ? "MISSING" : prediction.status().name(),
                rowMetrics, freezeFields(fields), List.copyOf(missing), List.copyOf(extras), List.copyOf(mismatched),
                failedRows, unanchored, immutable(validation), referenceComplete, correct);
    }

    private static Map<String, Map<String, ReviewedLabel>> referenceLabels(List<ReviewedLabel> labels) {
        Map<RowKey, ReviewedLabel> latest = new HashMap<>();
        Map<String, RowKey> labelOwners = new HashMap<>();
        Set<RevisionKey> revisions = new HashSet<>();
        for (ReviewedLabel label : labels) {
            if (label == null) throw invalid("Null reference label");
            requireIdentifier(label.documentId(), "label document ID");
            requireIdentifier(label.labelId(), "label ID");
            if (label.source() == null || !validAnchor(label.source().rowRef())) {
                throw invalid("Reference label needs a stable source anchor");
            }
            if (label.version() < 1) throw invalid("Reference version must be positive");
            RowKey key = new RowKey(label.documentId(), label.source().rowRef());
            RowKey owner = labelOwners.putIfAbsent(label.labelId(), key);
            if (owner != null && !owner.equals(key)) throw invalid("A label ID cannot belong to different source rows");
            if (!revisions.add(new RevisionKey(key, label.version()))) throw invalid("Repeated reference row revision");
            latest.merge(key, label, (first, second) -> first.version() > second.version() ? first : second);
        }
        Map<String, Map<String, ReviewedLabel>> truth = new TreeMap<>();
        for (var entry : latest.entrySet()) {
            ReviewedLabel label = entry.getValue();
            if (label.verification() != Verification.SOURCE_VERIFIED) {
                throw invalid("Latest reference label must be source-verified");
            }
            if (!validLocator(label.source())) throw invalid("Reference label needs a valid page or CSV record");
            truth.computeIfAbsent(entry.getKey().documentId(), ignored -> new TreeMap<>())
                    .put(entry.getKey().rowRef(), label);
        }
        return truth;
    }

    private static Map<String, Object> values(ReviewedLabel row) {
        return values(row.postedDate(), row.amountMinor(), row.currency(), row.flow(), row.kind(),
                row.originalDescription(), row.source());
    }

    private static Map<String, Object> values(ProposedRow row) {
        return values(row.postedDate(), row.amountMinor(), row.currency(), row.flow(), row.kind(),
                row.originalDescription(), row.source());
    }

    private static Map<String, Object> values(Object date, Object amount, Object currency, Object flow,
            Object kind, Object description, Source source) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("postedDate", date);
        values.put("amountMinor", amount);
        values.put("currency", currency);
        values.put("flow", flow);
        values.put("kind", kind);
        values.put("originalDescription", description);
        values.put("source", validLocator(source) ? source : null);
        return values;
    }

    private static Set<String> validationIssues(ProposedRow row) {
        Set<String> issues = new LinkedHashSet<>(row.issues());
        if (row.source() == null || !validAnchor(row.source().rowRef())) issues.add("missing_source_anchor");
        if (!validLocator(row.source())) issues.add("invalid_source_locator");
        if (row.originalDescription() == null || row.originalDescription().isBlank()
                || row.originalDescription().length() > 2_000) issues.add("invalid_description");
        if (row.postedDate() == null || row.postedDate().getYear() < 1 || row.postedDate().getYear() > 9999) {
            issues.add("invalid_posted_date");
        }
        if (row.amountMinor() == null || row.amountMinor() <= 0) issues.add("invalid_amount");
        if (row.currency() == null || !row.currency().matches("[A-Z]{3}")) issues.add("invalid_currency");
        else if (!"USD".equals(row.currency())) issues.add("unsupported_currency");
        if (row.flow() == null) issues.add("invalid_flow");
        if (row.kind() == null) issues.add("invalid_kind");
        if ((row.kind() == Kind.EXPENSE && row.flow() == Flow.CREDIT)
                || ((row.kind() == Kind.REFUND || row.kind() == Kind.INCOME) && row.flow() == Flow.DEBIT)) {
            issues.add("inconsistent_kind_flow");
        }
        return issues;
    }

    private static boolean validAnchor(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}");
    }

    private static boolean validLocator(Source source) {
        return source != null && (source.page() == null) != (source.csvRecord() == null)
                && (source.page() == null || source.page() >= 1)
                && (source.csvRecord() == null || source.csvRecord() >= 2);
    }

    private static void addIssue(Map<String, Integer> codes, String value) {
        String bounded = value != null && value.matches("[a-z][a-z0-9_]{0,79}") ? value : "invalid_issue_code";
        codes.merge(bounded, 1, Math::addExact);
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 160
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("Invalid " + name);
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static Map<String, MutableField> newFields() {
        Map<String, MutableField> result = new LinkedHashMap<>();
        FIELD_NAMES.forEach(name -> result.put(name, new MutableField()));
        return result;
    }

    private static Map<String, FieldMetrics> freezeFields(Map<String, MutableField> fields) {
        Map<String, FieldMetrics> result = new LinkedHashMap<>();
        fields.forEach((name, value) -> result.put(name, value.freeze()));
        return immutable(result);
    }

    private static <T> Map<String, T> immutable(Map<String, T> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private record RowKey(String documentId, String rowRef) {}
    private record RevisionKey(RowKey row, int version) {}

    /** Row presence is separate from field correctness; wrong amounts still match their source row. */
    public record RowMetrics(int expectedRows, int predictedRows, int matchedRows, int missingRows, int extraRows) {
        public Double precision() { return rate(matchedRows, predictedRows); }
        public Double recall() { return rate(matchedRows, expectedRows); }
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expectedRows", expectedRows); result.put("predictedRows", predictedRows);
            result.put("matchedRows", matchedRows); result.put("missingRows", missingRows);
            result.put("extraRows", extraRows); result.put("precision", precision()); result.put("recall", recall());
            return immutable(result);
        }
    }

    /** Accuracy includes every known truth value, including missing rows and unresolved predictions. */
    public record FieldMetrics(int correct, int expected, int compared, int missingPredictions,
                               int unresolvedPredictions, int unknownTruth) {
        public Double accuracy() { return rate(correct, expected); }
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("correct", correct); result.put("expected", expected); result.put("compared", compared);
            result.put("missingPredictions", missingPredictions); result.put("unresolvedPredictions", unresolvedPredictions);
            result.put("unknownTruth", unknownTruth); result.put("accuracy", accuracy());
            return immutable(result);
        }
    }

    public record DocumentResult(String documentId, String status, RowMetrics rowMetrics,
                                 Map<String, FieldMetrics> fields, List<String> missingSourceRefs,
                                 List<String> extraSourceRefs, List<String> mismatchedSourceRefs,
                                 int validationFailureRows, int unanchoredRows, Map<String, Integer> validationCodes,
                                 boolean referenceComplete, boolean correct) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId); result.put("status", status);
            result.put("rowMetrics", rowMetrics.asMap());
            Map<String, Object> metrics = new LinkedHashMap<>();
            fields.forEach((name, field) -> metrics.put(name, field.asMap()));
            result.put("fieldMetrics", immutable(metrics));
            result.put("missingSourceRefs", missingSourceRefs); result.put("extraSourceRefs", extraSourceRefs);
            result.put("mismatchedSourceRefs", mismatchedSourceRefs);
            result.put("validationFailureRows", validationFailureRows); result.put("unanchoredRows", unanchoredRows);
            result.put("validationCodes", validationCodes); result.put("referenceComplete", referenceComplete);
            result.put("correct", correct);
            return immutable(result);
        }
    }

    public record Report(RowMetrics rowMetrics, Map<String, FieldMetrics> fields, List<DocumentResult> documents,
                         Map<String, Integer> validationCodes, int validationFailureRows, int unanchoredRows,
                         int correctDocuments, int totalDocuments) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matchingPolicy", "exact_document_and_source_ref_first_prediction_occurrence");
            result.put("rowMetrics", rowMetrics.asMap());
            Map<String, Object> metrics = new LinkedHashMap<>();
            fields.forEach((name, field) -> metrics.put(name, field.asMap()));
            result.put("fieldMetrics", immutable(metrics));
            result.put("correctDocuments", correctDocuments); result.put("totalDocuments", totalDocuments);
            result.put("documentAccuracy", rate(correctDocuments, totalDocuments));
            result.put("validationFailureRows", validationFailureRows); result.put("unanchoredRows", unanchoredRows);
            result.put("validationCodes", validationCodes);
            result.put("documents", documents.stream().map(DocumentResult::asMap).toList());
            return immutable(result);
        }
    }

    private static final class MutableRows {
        private int expected, predicted, matched, missing, extra;
        void add(RowMetrics rows) {
            expected = Math.addExact(expected, rows.expectedRows()); predicted = Math.addExact(predicted, rows.predictedRows());
            matched = Math.addExact(matched, rows.matchedRows()); missing = Math.addExact(missing, rows.missingRows());
            extra = Math.addExact(extra, rows.extraRows());
        }
        RowMetrics freeze() { return new RowMetrics(expected, predicted, matched, missing, extra); }
    }

    private static final class MutableField {
        private int correct, expected, compared, missing, unresolved, unknown;
        void observe(Object truth, Object prediction, boolean rowPresent) {
            if (truth == null) { unknown = Math.incrementExact(unknown); return; }
            expected = Math.incrementExact(expected);
            if (!rowPresent) missing = Math.incrementExact(missing);
            else if (prediction == null) unresolved = Math.incrementExact(unresolved);
            else {
                compared = Math.incrementExact(compared);
                if (Objects.equals(truth, prediction)) correct = Math.incrementExact(correct);
            }
        }
        void add(FieldMetrics values) {
            correct = Math.addExact(correct, values.correct()); expected = Math.addExact(expected, values.expected());
            compared = Math.addExact(compared, values.compared()); missing = Math.addExact(missing, values.missingPredictions());
            unresolved = Math.addExact(unresolved, values.unresolvedPredictions()); unknown = Math.addExact(unknown, values.unknownTruth());
        }
        FieldMetrics freeze() { return new FieldMetrics(correct, expected, compared, missing, unresolved, unknown); }
    }
}
