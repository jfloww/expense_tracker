package com.expensetracker.experiments.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;

/** Parser for synthetic-csv-v1, not a native Chase/Capital One/Amex export. */
public final class SyntheticCsvExtractor implements Extractor {
    public static final List<String> HEADER = List.of("source_ref", "posted_date", "description",
            "amount", "currency", "flow", "kind");
    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_ROWS = 1_000;

    @Override
    public Prediction extract(Document document) throws IOException {
        if (document.format() != SourceFormat.CSV) throw new IllegalArgumentException("CSV input required");
        byte[] bytes;
        try (var input = Files.newInputStream(document.path())) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) return failed(document, "input_too_large");
        final String contents;
        try {
            contents = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return failed(document, "invalid_utf8");
        }
        final List<List<String>> records;
        try {
            records = parse(contents);
        } catch (IllegalArgumentException invalid) {
            return failed(document, "invalid_csv");
        }
        if (records.isEmpty() || !records.getFirst().equals(HEADER)) return failed(document, "unsupported_csv_header");
        if (records.size() - 1 > MAX_ROWS) return failed(document, "too_many_rows");
        var rows = new ArrayList<ProposedRow>();
        var anchors = new HashSet<String>();
        for (int index = 1; index < records.size(); index++) {
            List<String> values = records.get(index);
            if (values.size() != HEADER.size()) return failed(document, "invalid_csv_width");
            if (!anchors.add(values.get(0))) return failed(document, "duplicate_source_anchor");
            var raw = new RawRow(new Source(values.get(0), null, index + 1), values.get(2), values.get(1),
                    values.get(3), values.get(4), values.get(5), values.get(6));
            rows.add(RowNormalizer.normalize(raw));
        }
        return new Prediction("csv-v1-" + document.documentId(), document.documentId(), RunStatus.SUCCEEDED, rows, List.of());
    }

    private Prediction failed(Document document, String code) {
        return new Prediction("csv-v1-" + document.documentId(), document.documentId(), RunStatus.FAILED, List.of(), List.of(code));
    }

    /** RFC-style quoted fields, doubled quotes, CRLF/LF records and embedded quoted newlines. */
    static List<List<String>> parse(String csv) {
        var records = new ArrayList<List<String>>();
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean recordStarted = false;
        for (int index = 0; index < csv.length(); index++) {
            char value = csv.charAt(index);
            if (value == '\0') throw new IllegalArgumentException("NUL is unsupported");
            recordStarted = true;
            if (quoted) {
                if (value == '"') {
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                        field.append('"'); index++;
                    } else {
                        quoted = false; closedQuote = true;
                    }
                } else {
                    field.append(value);
                }
            } else if (value == ',') {
                fields.add(field.toString()); field.setLength(0); closedQuote = false;
            } else if (value == '\r' || value == '\n') {
                if (value == '\r') {
                    if (index + 1 >= csv.length() || csv.charAt(index + 1) != '\n') {
                        throw new IllegalArgumentException("Bare CR is unsupported");
                    }
                    index++;
                }
                fields.add(field.toString()); records.add(List.copyOf(fields));
                fields.clear(); field.setLength(0); closedQuote = false; recordStarted = false;
            } else if (value == '"' && field.isEmpty() && !closedQuote) {
                quoted = true;
            } else {
                if (closedQuote || value == '"') throw new IllegalArgumentException("Invalid quoted field");
                field.append(value);
            }
            if (field.length() > 4_000 || records.size() > MAX_ROWS + 1 || fields.size() > HEADER.size()) {
                throw new IllegalArgumentException("CSV limits exceeded");
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted field");
        if (recordStarted) {
            fields.add(field.toString()); records.add(List.copyOf(fields));
        }
        return List.copyOf(records);
    }
}
