"""Synthetic checks for import trust boundaries and money invariants."""
from datetime import date

from django.contrib.auth import get_user_model
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction as db_transaction
from django.test import TestCase
from django.utils import timezone

from ledger.models import (
    Account, Category, CategoryRule, ImportedTransaction, ProcessingJob,
    Statement, Transaction,
)


class LedgerValidationTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.alice = get_user_model().objects.create_user(username="synthetic-alice")
        cls.bob = get_user_model().objects.create_user(username="synthetic-bob")
        cls.account = Account.objects.create(owner=cls.alice, name="Test card", issuer="chase")
        cls.second_account = Account.objects.create(owner=cls.alice, name="Second card", issuer="amex")
        cls.bob_account = Account.objects.create(owner=cls.bob, name="Other user's card", issuer="capital_one")
        cls.category = Category.objects.create(owner=cls.alice, name="Dining")
        cls.bob_category = Category.objects.create(owner=cls.bob, name="Dining")

    def statement(self, **changes):
        values = {
            "owner": self.alice, "account": self.account,
            "original_filename": "synthetic.csv", "source_format": "csv",
        }
        values.update(changes)
        return Statement(**values)

    def ledger_transaction(self, **changes):
        values = {
            "owner": self.alice, "account": self.account,
            "posted_date": date(2026, 9, 12), "amount_minor": 1250,
            "currency": "USD", "flow": "debit", "kind": "expense",
        }
        values.update(changes)
        return Transaction(**values)

    def test_statement_rejects_other_users_account(self):
        with self.assertRaises(ValidationError) as caught:
            self.statement(account=self.bob_account).full_clean()
        self.assertIn("account", caught.exception.message_dict)

    def test_transaction_and_rules_reject_other_users_relationships(self):
        for field_name, foreign_record in (
            ("account", self.bob_account), ("category", self.bob_category),
        ):
            with self.subTest(field=field_name), self.assertRaises(ValidationError) as caught:
                self.ledger_transaction(**{field_name: foreign_record}).full_clean()
            self.assertIn(field_name, caught.exception.message_dict)
        rule = CategoryRule(owner=self.alice, category=self.bob_category, match_text="Synthetic Cafe")
        with self.assertRaises(ValidationError) as caught:
            rule.full_clean()
        self.assertIn("category", caught.exception.message_dict)

    def test_staged_rows_and_jobs_reject_other_users_statements(self):
        foreign_statement = self.statement(owner=self.bob, account=self.bob_account)
        foreign_statement.save()
        records = (
            ImportedTransaction(owner=self.alice, statement=foreign_statement, row_number=1),
            ProcessingJob(owner=self.alice, statement=foreign_statement, kind="parse_statement"),
        )
        for record in records:
            with self.subTest(model=type(record).__name__), self.assertRaises(ValidationError) as caught:
                record.full_clean()
            self.assertIn("statement", caught.exception.message_dict)

    def test_statement_hash_deduplicates_only_within_owner_account(self):
        digest = "a" * 64
        self.statement(content_sha256=digest).save()
        duplicate = self.statement(content_sha256=digest)
        with self.assertRaises(ValidationError):
            duplicate.full_clean()
        with self.assertRaises(IntegrityError), db_transaction.atomic():
            duplicate.save()
        self.statement(account=self.second_account, content_sha256=digest).full_clean()
        self.statement(owner=self.bob, account=self.bob_account, content_sha256=digest).full_clean()

    def test_unhashed_statements_do_not_share_a_duplicate_identity(self):
        self.statement().save()
        second = self.statement()
        second.full_clean()
        second.save()

    def test_pending_staged_row_can_remain_incomplete_but_accepted_row_cannot(self):
        statement = self.statement()
        statement.save()
        row = ImportedTransaction(owner=self.alice, statement=statement, row_number=1)
        row.full_clean()
        row.review_status = "accepted"
        with self.assertRaises(ValidationError) as caught:
            row.full_clean()
        self.assertIn("amount_minor", caught.exception.message_dict)
        self.assertIn("posted_date", caught.exception.message_dict)

    def test_confirmation_requires_accepted_row_and_reviewed_statement(self):
        statement = self.statement(status="needs_review")
        statement.save()
        row = ImportedTransaction.objects.create(
            owner=self.alice, statement=statement, row_number=1, source_page=1,
            posted_date=date(2026, 9, 12), amount_minor=1250, currency="USD",
            flow="debit", kind="expense",
        )
        record = self.ledger_transaction(source_row=row, confirmed=True)
        with self.assertRaises(ValidationError):
            record.full_clean()
        row.review_status = "accepted"
        row.full_clean()
        row.save()
        with self.assertRaises(ValidationError):
            record.full_clean()
        statement.status = "reviewed"
        statement.reviewed_at = timezone.now()
        statement.full_clean()
        statement.save()
        record.full_clean()

    def test_source_row_must_reference_same_account_and_owner(self):
        foreign_statement = self.statement(owner=self.bob, account=self.bob_account)
        foreign_statement.save()
        foreign_row = ImportedTransaction.objects.create(owner=self.bob, statement=foreign_statement, row_number=1)
        with self.assertRaises(ValidationError) as caught:
            self.ledger_transaction(source_row=foreign_row).full_clean()
        self.assertIn("source_row", caught.exception.message_dict)
        second_statement = self.statement(account=self.second_account)
        second_statement.save()
        second_row = ImportedTransaction.objects.create(owner=self.alice, statement=second_statement, row_number=1)
        with self.assertRaises(ValidationError):
            self.ledger_transaction(source_row=second_row).full_clean()

    def test_currency_cannot_be_silently_changed(self):
        with self.assertRaises(ValidationError) as caught:
            self.ledger_transaction(currency="EUR").full_clean()
        self.assertIn("currency", caught.exception.message_dict)

    def test_database_rejects_zero_money_and_inconsistent_flow_even_without_clean(self):
        for changes in ({"amount_minor": 0}, {"flow": "credit"}, {"kind": "refund", "flow": "debit"}):
            with self.subTest(changes=changes), self.assertRaises(IntegrityError), db_transaction.atomic():
                self.ledger_transaction(**changes).save()

    def test_transfer_and_card_payment_can_have_either_flow(self):
        for kind in ("transfer", "card_payment"):
            for flow in ("debit", "credit"):
                with self.subTest(kind=kind, flow=flow):
                    self.ledger_transaction(kind=kind, flow=flow).full_clean()


class HealthEndpointTests(TestCase):
    def test_health_is_public_and_read_only(self):
        response = self.client.get("/api/v1/health/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok", "service": "expense-tracker-api"})
        self.assertEqual(self.client.post("/api/v1/health/", {}).status_code, 405)
