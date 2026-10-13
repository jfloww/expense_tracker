from dataclasses import FrozenInstanceError, replace
from datetime import date
import unittest

from ledger.services.spending_changes import (
    ReportingTransaction,
    StatementCoverage,
    compare_spending_changes,
)


def transaction(identifier, day, amount, **overrides):
    values = dict(
        id=identifier, posted_date=date.fromisoformat(day), amount_minor=amount,
        currency="USD", kind="expense", confirmed=True,
        category_id="food", category_name="Food", merchant_name="Cafe",
        account_id="checking", statement_id="statement", source_row=1,
    )
    values.update(overrides)
    return ReportingTransaction(**values)


def compare(rows, **overrides):
    values = dict(previous_month="2025-12", current_month="2026-01", currency="USD", as_of=date(2026, 2, 1))
    values.update(overrides)
    return compare_spending_changes(rows, **values)


class SpendingChangesTests(unittest.TestCase):
    def test_exact_differences_and_source_ids_across_year_boundary(self):
        rows = [
            transaction("old-food", "2025-12-31", 101),
            transaction("new-food", "2026-01-01", 201),
            transaction("new-rent", "2026-01-31", 10000, category_id="rent", category_name="Rent", merchant_name="Landlord"),
            transaction("outside", "2026-02-01", 99999),
        ]
        result = compare(rows)
        self.assertEqual((result.previous_total_minor, result.current_total_minor, result.delta_minor), (101, 10201, 10100))
        self.assertEqual(sum(group.delta_minor for group in result.category_changes), result.delta_minor)
        self.assertEqual(sum(group.delta_minor for group in result.merchant_changes), result.delta_minor)
        food = next(group for group in result.category_changes if group.key == "category:food")
        self.assertEqual((food.previous_transaction_ids, food.current_transaction_ids), (("old-food",), ("new-food",)))
        self.assertEqual(compare(reversed(rows)), result)

    def test_refunds_reduce_posted_month_and_other_kinds_or_unconfirmed_rows_are_excluded(self):
        rows = [
            transaction("purchase", "2026-01-10", 500),
            transaction("refund", "2026-01-12", 200, kind="refund"),
            transaction("pending", "2026-01-10", 500, confirmed=False),
        ] + [transaction(kind, "2026-01-10", 700, kind=kind) for kind in ("income", "transfer", "card_payment", "adjustment")]
        result = compare(rows)
        self.assertEqual(result.current_total_minor, 300)
        self.assertEqual(result.category_changes[0].current_transaction_ids, ("purchase", "refund"))

    def test_currency_is_explicit_and_never_combined(self):
        rows = [transaction("usd", "2026-01-10", 100), transaction("eur", "2026-01-10", 900, currency="EUR")]
        result = compare(rows)
        self.assertEqual(result.current_total_minor, 100)
        self.assertIn("other currencies", result.warnings[-1])
        self.assertEqual(compare(rows, currency="EUR").current_total_minor, 900)
        with self.assertRaises(ValueError):
            compare(rows, currency="")

    def test_duplicate_ids_rejected_even_when_a_row_is_excluded(self):
        row = transaction("duplicate", "2026-01-10", 100)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            compare([row, replace(row, confirmed=False)])

    def test_completed_months_and_strict_month_format(self):
        for overrides in (
            {"as_of": date(2026, 1, 31)},
            {"previous_month": "2026-01", "current_month": "2025-12"},
            {"previous_month": "2026-01"},
            {"previous_month": "2025-2"},
            {"current_month": "2026-13"},
        ):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                compare([], **overrides)

    def test_missing_coverage_and_missing_account_universe_are_unknown(self):
        row = transaction("one", "2026-01-10", 100)
        span = StatementCoverage("checking", "full", date(2025, 12, 1), date(2026, 1, 31))
        for overrides in ({}, {"account_ids": ["checking"]}, {"coverage": [span]}):
            with self.subTest(overrides=overrides):
                result = compare([row], **overrides)
                self.assertEqual([item.status for item in result.coverage], ["unknown", "unknown"])
                self.assertEqual(len(result.warnings), 2)

    def test_adjacent_and_overlapping_intervals_cover_both_months(self):
        spans = [
            StatementCoverage("checking", "first", date(2025, 12, 1), date(2025, 12, 15)),
            StatementCoverage("checking", "second", date(2025, 12, 16), date(2026, 1, 20)),
            StatementCoverage("checking", "third", date(2026, 1, 15), date(2026, 1, 31)),
        ]
        result = compare([], coverage=spans, account_ids=["checking"])
        self.assertEqual([item.status for item in result.coverage], ["complete", "complete"])
        self.assertEqual(result.coverage[0].statement_ids, ("first", "second"))
        self.assertEqual(result.warnings, ())

    def test_gap_and_missing_account_are_partial_even_without_transactions(self):
        spans = [
            StatementCoverage("checking", "first", date(2025, 12, 1), date(2026, 1, 15)),
            StatementCoverage("checking", "second", date(2026, 1, 17), date(2026, 1, 31)),
        ]
        result = compare([], coverage=spans, account_ids=["checking", "savings"])
        self.assertEqual(result.coverage[0].uncovered_account_ids, ("savings",))
        self.assertEqual(result.coverage[1].uncovered_account_ids, ("checking", "savings"))
        self.assertEqual([item.status for item in result.coverage], ["partial", "partial"])
        self.assertEqual(compare([], coverage=[], account_ids=["checking"]).coverage[0].status, "partial")

    def test_account_universe_must_include_selected_transactions(self):
        with self.assertRaisesRegex(ValueError, "omits"):
            compare([transaction("one", "2026-01-10", 100)], account_ids=["savings"])
        with self.assertRaises(ValueError):
            compare([], account_ids=[])

    def test_uncategorized_and_merchant_names_group_consistently(self):
        rows = [
            transaction("first", "2025-12-10", 100, category_id=None, merchant_name=" Cafe  Shop "),
            transaction("second", "2026-01-10", 300, category_id=None, merchant_name="cafe shop"),
        ]
        result = compare(rows)
        self.assertEqual(len(result.category_changes), 1)
        self.assertEqual(result.category_changes[0].name, "Uncategorized")
        self.assertEqual(len(result.merchant_changes), 1)
        self.assertEqual(result.merchant_changes[0].delta_minor, 200)

    def test_integer_amount_and_frozen_dtos(self):
        for amount in (0, -1, 1.5, True):
            with self.subTest(amount=amount), self.assertRaises(ValueError):
                transaction("invalid", "2026-01-10", amount)
        row = transaction("immutable", "2026-01-10", 10**20 + 1)
        self.assertEqual(compare([row]).current_total_minor, 10**20 + 1)
        with self.assertRaises(FrozenInstanceError):
            row.amount_minor = 100
        with self.assertRaises(ValueError):
            StatementCoverage("checking", "backwards", date(2026, 1, 31), date(2026, 1, 1))


if __name__ == "__main__":
    unittest.main()
