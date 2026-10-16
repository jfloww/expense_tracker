package com.expensetracker.experiments.extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Offline synthetic experiment values. Document/account IDs do not establish product authorization. */
public final class ExtractionContract {
    public static final String VERSION = "synthetic-extraction-v1";

    private ExtractionContract() {}

    public enum Split { DEV, TEST }
    public enum SourceFormat { PDF, CSV }
    public enum AccountKind { CREDIT_CARD, BANK_ACCOUNT }
    public enum Flow { DEBIT, CREDIT }
    public enum Kind { EXPENSE, REFUND, INCOME, TRANSFER, CARD_PAYMENT, ADJUSTMENT }
    public enum RunStatus { SUCCEEDED, REFUSED, INCOMPLETE, FAILED }
    public enum Verification { SOURCE_VERIFIED, ACCEPTED_UNVERIFIED }

    public record Document(String documentId, String groupId, Split split, SourceFormat format,
                           AccountKind accountKind, String accountId, Path path) {}

    /** rowRef is an annotated synthetic anchor, independent of dates/amounts; null means unlocated. */
    public record Source(String rowRef, Integer page, Integer csvRecord) {}

    public record RawRow(Source source, String description, String postedDate, String amount,
                         String currency, String flow, String kind) {}

    public record ProposedRow(Source source, String originalDescription, LocalDate postedDate,
                              Long amountMinor, String currency, Flow flow, Kind kind, List<String> issues, RawRow raw) {
        public ProposedRow { issues = List.copyOf(issues); }
        public ProposedRow(Source source, String originalDescription, LocalDate postedDate, Long amountMinor,
                           String currency, Flow flow, Kind kind, List<String> issues) {
            this(source, originalDescription, postedDate, amountMinor, currency, flow, kind, issues, null);
        }
    }

    public record Prediction(String predictionId, String documentId, RunStatus status,
                             List<ProposedRow> rows, List<String> issues) {
        public Prediction { rows = List.copyOf(rows); issues = List.copyOf(issues); }
    }

    public record ReviewedLabel(String labelId, int version, String documentId, Source source,
                                String originalDescription, LocalDate postedDate, Long amountMinor,
                                String currency, Flow flow, Kind kind, Verification verification) {}

    public interface Extractor {
        Prediction extract(Document document) throws IOException;
    }
}
