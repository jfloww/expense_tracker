package com.expensetracker.experiments.extraction;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.expensetracker.experiments.extraction.ExtractionContract.*;
import static org.junit.jupiter.api.Assertions.*;

class RowNormalizerTest {
    @Test
    void preservesExactIntegersAboveJavascriptRangeAndAtLongBoundary() {
        assertEquals(9007199254740993L, RowNormalizer.normalize(raw("2026-08-02", "90071992547409.93")).amountMinor());
        assertEquals(Long.MAX_VALUE, RowNormalizer.normalize(raw("2026-08-02", "92233720368547758.07")).amountMinor());
        var overflow = RowNormalizer.normalize(raw("2026-08-02", "92233720368547758.08"));
        assertNull(overflow.amountMinor());
        assertTrue(overflow.issues().contains("invalid_amount"));
    }

    @Test
    void rejectsNoncanonicalOrNonpositiveAmountsWithoutRoundingOrTrimming() {
        for (String invalid : List.of("0.00", "-1.00", "+1.00", "01.00", "1", "1.0", "1.001", "1e3", " 1.00", "1,000.00", "1.00 ")) {
            var row = RowNormalizer.normalize(raw("2026-08-02", invalid));
            assertNull(row.amountMinor(), invalid);
            assertEquals(invalid, row.raw().amount());
        }
    }

    @Test
    void checksCalendarDatesAndPreservesInvalidSourceTokens() {
        assertEquals(LocalDate.of(2024, 2, 29), RowNormalizer.normalize(raw("2024-02-29", "1.00")).postedDate());
        for (String invalid : List.of("2026-02-29", "2026-13-01", "2026-8-02", "0000-01-01", "2026-08-02T00:00:00Z")) {
            var row = RowNormalizer.normalize(raw(invalid, "0.00"));
            assertNull(row.postedDate());
            assertNull(row.amountMinor());
            assertEquals(invalid, row.raw().postedDate());
            assertEquals("0.00", row.raw().amount());
        }
    }

    @Test
    void leavesUnknownRequiredFieldsNullAndRetainsForeignCurrencyEvidence() {
        var unknown = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Original", null, null, null, null, null));
        assertNull(unknown.postedDate()); assertNull(unknown.amountMinor()); assertNull(unknown.currency());
        assertNull(unknown.flow()); assertNull(unknown.kind());
        var foreign = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Original", "2026-08-02", "1.00", "EUR", "CREDIT", "REFUND"));
        assertEquals("EUR", foreign.currency());
        assertEquals("EUR", foreign.raw().currency());
        assertTrue(foreign.issues().contains("unsupported_currency"));
    }

    @Test
    void separatesSpendingKindFromDirectionAndFlagsContradictions() {
        var payment = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Payment", "2026-08-02", "5.00", "USD", "CREDIT", "CARD_PAYMENT"));
        assertTrue(payment.issues().isEmpty());
        assertEquals(Kind.CARD_PAYMENT, payment.kind());
        var incorrect = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Purchase", "2026-08-02", "5.00", "USD", "CREDIT", "EXPENSE"));
        assertTrue(incorrect.issues().contains("inconsistent_kind_flow"));
        var cash = RowNormalizer.normalize(new RawRow(new Source("r1", 1, null), "Cash", "2026-08-02", "5.00", "USD", "DEBIT", "ADJUSTMENT"));
        assertEquals(Kind.ADJUSTMENT, cash.kind());
    }

    @Test
    void sourceAnchorAndLocatorAreValidatedIndependentlyOfFinancialFields() {
        var unanchored = RowNormalizer.normalize(new RawRow(new Source(null, 1, null), "Original", "2026-08-02", "1.00", "USD", "DEBIT", "EXPENSE"));
        assertTrue(unanchored.issues().contains("missing_source_anchor"));
        var badLocator = RowNormalizer.normalize(new RawRow(new Source("r1", 1, 2), "Original", "2026-08-02", "1.00", "USD", "DEBIT", "EXPENSE"));
        assertTrue(badLocator.issues().contains("invalid_source_locator"));
    }

    private static RawRow raw(String date, String amount) {
        return new RawRow(new Source("r1", 1, null), "Original merchant", date, amount, "USD", "DEBIT", "EXPENSE");
    }
}
