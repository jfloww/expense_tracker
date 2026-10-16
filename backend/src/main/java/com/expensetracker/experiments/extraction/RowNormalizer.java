package com.expensetracker.experiments.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;

/** Strict documented synthetic USD format only; issuer-specific sign/date semantics remain unverified. */
public final class RowNormalizer {
    private RowNormalizer() {}

    public static ProposedRow normalize(RawRow raw) {
        List<String> issues = new ArrayList<>();
        Source source = raw.source();
        if (source == null || source.rowRef() == null || !source.rowRef().matches("[A-Za-z0-9_-]{1,80}")) {
            issues.add("missing_source_anchor");
        }
        if (source == null || (source.page() == null) == (source.csvRecord() == null)
                || (source.page() != null && source.page() < 1)
                || (source.csvRecord() != null && source.csvRecord() < 2)) {
            issues.add("invalid_source_locator");
        }
        String description = raw.description();
        if (description == null || description.isBlank() || description.length() > 2_000) {
            issues.add("invalid_description");
        }
        LocalDate postedDate = null;
        try {
            if (raw.postedDate() == null || !raw.postedDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new DateTimeParseException("Noncanonical date", "", 0);
            }
            postedDate = LocalDate.parse(raw.postedDate());
            if (postedDate.getYear() < 1 || postedDate.getYear() > 9999) {
                throw new DateTimeParseException("Out of range", "", 0);
            }
        } catch (DateTimeParseException invalid) {
            postedDate = null;
            issues.add("invalid_posted_date");
        }
        Long amount = null;
        try {
            if (raw.amount() == null || raw.amount().length() > 22
                    || !raw.amount().matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}")) {
                throw new ArithmeticException("Noncanonical amount");
            }
            amount = new BigDecimal(raw.amount()).movePointRight(2).longValueExact();
            if (amount <= 0) throw new ArithmeticException("Nonpositive amount");
        } catch (ArithmeticException invalid) {
            amount = null;
            issues.add("invalid_amount");
        }
        String currency = raw.currency();
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            currency = null;
            issues.add("invalid_currency");
        } else if (!currency.equals("USD")) {
            issues.add("unsupported_currency");
        }
        Flow flow = value(Flow.class, raw.flow(), "invalid_flow", issues);
        Kind kind = value(Kind.class, raw.kind(), "invalid_kind", issues);
        if ((kind == Kind.EXPENSE && flow == Flow.CREDIT)
                || ((kind == Kind.REFUND || kind == Kind.INCOME) && flow == Flow.DEBIT)) {
            issues.add("inconsistent_kind_flow");
        }
        return new ProposedRow(source, description, postedDate, amount, currency, flow, kind, issues, raw);
    }

    private static <T extends Enum<T>> T value(Class<T> type, String raw, String issue, List<String> issues) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw);
        } catch (IllegalArgumentException invalid) {
            issues.add(issue);
            return null;
        }
    }
}
