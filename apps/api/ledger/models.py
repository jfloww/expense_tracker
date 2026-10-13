"""Statement staging and a confirmed ledger, with explicit source provenance.

Call full_clean() before saving through a future application service. Model.clean
checks related owners, but save()/bulk operations do not call it automatically;
this is not database-enforced tenant isolation.
"""
from django.conf import settings
from django.core.exceptions import ObjectDoesNotExist, ValidationError
from django.core.validators import MinValueValidator, RegexValidator
from django.db import models


currency_validator = RegexValidator(r"^[A-Z]{3}$", "Use an uppercase three-letter currency code.")
sha256_validator = RegexValidator(r"^[a-f0-9]{64}$", "Use a lowercase SHA-256 digest.")


class Flow(models.TextChoices):
    DEBIT = "debit", "Money out"
    CREDIT = "credit", "Money in"


class TransactionKind(models.TextChoices):
    EXPENSE = "expense", "Expense"
    REFUND = "refund", "Refund"
    INCOME = "income", "Income"
    TRANSFER = "transfer", "Transfer"
    CARD_PAYMENT = "card_payment", "Card payment"
    ADJUSTMENT = "adjustment", "Adjustment"


def related(instance, field_name):
    """Leave missing FK targets to Django's field validation."""
    if not getattr(instance, f"{field_name}_id", None):
        return None
    try:
        return getattr(instance, field_name)
    except ObjectDoesNotExist:
        return None


def ownership_errors(instance, *field_names):
    errors = {}
    for field_name in field_names:
        target = related(instance, field_name)
        if target is not None and instance.owner_id != target.owner_id:
            errors[field_name] = "The related record must belong to the same owner."
    return errors


def flow_errors(kind, flow):
    required = {
        TransactionKind.EXPENSE: Flow.DEBIT,
        TransactionKind.REFUND: Flow.CREDIT,
        TransactionKind.INCOME: Flow.CREDIT,
    }.get(kind)
    if required and flow and flow != required:
        return {"flow": f"{kind} requires {required} flow."}
    return {}


class OwnedModel(models.Model):
    owner = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="%(app_label)s_%(class)s_records",
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        abstract = True


class Account(OwnedModel):
    class Issuer(models.TextChoices):
        CAPITAL_ONE = "capital_one", "Capital One"
        CHASE = "chase", "Chase"
        AMEX = "amex", "American Express"
        OTHER = "other", "Other"

    class Kind(models.TextChoices):
        CREDIT_CARD = "credit_card", "Credit card"
        BANK_ACCOUNT = "bank_account", "Bank account"

    name = models.CharField(max_length=120)
    issuer = models.CharField(max_length=20, choices=Issuer.choices)
    kind = models.CharField(max_length=20, choices=Kind.choices, default=Kind.CREDIT_CARD)
    last_four = models.CharField(
        max_length=4, blank=True,
        validators=[RegexValidator(r"^[0-9]{4}$", "Use four digits.")],
    )
    currency = models.CharField(max_length=3, default="USD", validators=[currency_validator])
    is_active = models.BooleanField(default=True)

    def __str__(self):
        return self.name


class Category(OwnedModel):
    name = models.CharField(max_length=80)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["owner", "name"], name="category_owner_name_unique")
        ]

    def __str__(self):
        return self.name


class Statement(OwnedModel):
    class SourceFormat(models.TextChoices):
        CSV = "csv", "CSV"
        PDF = "pdf", "PDF"

    class Status(models.TextChoices):
        UPLOADED = "uploaded", "Uploaded"
        PARSING = "parsing", "Parsing"
        NEEDS_REVIEW = "needs_review", "Needs review"
        REVIEWED = "reviewed", "Reviewed"
        IMPORTED = "imported", "Imported"
        FAILED = "failed", "Failed"

    account = models.ForeignKey(Account, on_delete=models.PROTECT, related_name="statements")
    original_filename = models.CharField(max_length=255)
    source_format = models.CharField(max_length=3, choices=SourceFormat.choices)
    # An object-store key only; this scaffold does not upload or serve files.
    storage_key = models.CharField(max_length=500, blank=True)
    content_sha256 = models.CharField(max_length=64, blank=True, validators=[sha256_validator])
    period_start = models.DateField(null=True, blank=True)
    period_end = models.DateField(null=True, blank=True)
    status = models.CharField(max_length=20, choices=Status.choices, default=Status.UPLOADED)
    parser_version = models.CharField(max_length=80, blank=True)
    failure_reason = models.TextField(blank=True)
    reviewed_at = models.DateTimeField(null=True, blank=True)
    imported_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["owner", "account", "content_sha256"],
                condition=~models.Q(content_sha256=""),
                name="statement_owner_account_hash_unique",
            )
        ]

    def clean(self):
        errors = ownership_errors(self, "account")
        if self.period_start and self.period_end and self.period_start > self.period_end:
            errors["period_end"] = "The statement period must end on or after its start."
        if self.status in {self.Status.REVIEWED, self.Status.IMPORTED} and not self.reviewed_at:
            errors["reviewed_at"] = "Reviewed statements require a review timestamp."
        if self.status == self.Status.IMPORTED and not self.imported_at:
            errors["imported_at"] = "Imported statements require an import timestamp."
        if errors:
            raise ValidationError(errors)


class ImportedTransaction(OwnedModel):
    """Untrusted staged rows remain separate from confirmed reporting data."""

    class ReviewStatus(models.TextChoices):
        PENDING = "pending", "Pending"
        ACCEPTED = "accepted", "Accepted"
        EXCLUDED = "excluded", "Excluded"

    statement = models.ForeignKey(Statement, on_delete=models.PROTECT, related_name="imported_rows")
    row_number = models.PositiveIntegerField(validators=[MinValueValidator(1)])
    source_page = models.PositiveIntegerField(null=True, blank=True, validators=[MinValueValidator(1)])
    raw_data = models.JSONField(default=dict, blank=True)
    source_description = models.TextField(blank=True)
    posted_date = models.DateField(null=True, blank=True)
    amount_minor = models.PositiveBigIntegerField(null=True, blank=True, validators=[MinValueValidator(1)])
    currency = models.CharField(max_length=3, blank=True, validators=[currency_validator])
    flow = models.CharField(max_length=6, blank=True, choices=Flow.choices)
    kind = models.CharField(max_length=20, blank=True, choices=TransactionKind.choices)
    merchant_name = models.CharField(max_length=200, blank=True)
    category = models.ForeignKey(Category, null=True, blank=True, on_delete=models.PROTECT, related_name="imported_rows")
    # Overlapping statements may legitimately stage the same row fingerprint.
    source_fingerprint = models.CharField(max_length=64, blank=True, validators=[sha256_validator])
    normalization_errors = models.JSONField(default=list, blank=True)
    review_status = models.CharField(max_length=10, choices=ReviewStatus.choices, default=ReviewStatus.PENDING)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["statement", "row_number"], name="imported_statement_row_unique"),
            models.CheckConstraint(
                condition=models.Q(amount_minor__isnull=True) | models.Q(amount_minor__gt=0),
                name="imported_amount_positive_if_set",
            ),
            models.CheckConstraint(condition=models.Q(row_number__gt=0), name="imported_row_number_positive"),
        ]

    def clean(self):
        errors = ownership_errors(self, "statement", "category")
        errors.update(flow_errors(self.kind, self.flow))
        statement = related(self, "statement")
        account = related(statement, "account") if statement else None
        if account and self.currency and self.currency != account.currency:
            errors["currency"] = "The row currency must match its source account."
        if self.review_status == self.ReviewStatus.ACCEPTED:
            for field_name in ("posted_date", "amount_minor", "currency", "flow", "kind"):
                if not getattr(self, field_name):
                    errors[field_name] = "Accepted rows require this normalized field."
            if self.normalization_errors:
                errors["normalization_errors"] = "Resolve normalization errors before accepting a row."
        if errors:
            raise ValidationError(errors)


class Transaction(OwnedModel):
    """Positive minor units plus flow; expense/refund determine net spending."""

    Flow = Flow
    Kind = TransactionKind

    account = models.ForeignKey(Account, on_delete=models.PROTECT, related_name="transactions")
    source_row = models.OneToOneField(
        ImportedTransaction, null=True, blank=True, on_delete=models.PROTECT,
        related_name="ledger_transaction",
    )
    posted_date = models.DateField()
    amount_minor = models.PositiveBigIntegerField(validators=[MinValueValidator(1)])
    currency = models.CharField(max_length=3, default="USD", validators=[currency_validator])
    flow = models.CharField(max_length=6, choices=Flow.choices)
    kind = models.CharField(max_length=20, choices=TransactionKind.choices)
    description = models.TextField(blank=True)
    merchant_name = models.CharField(max_length=200, blank=True)
    category = models.ForeignKey(Category, null=True, blank=True, on_delete=models.PROTECT, related_name="transactions")
    confirmed = models.BooleanField(default=False)

    class Meta:
        indexes = [models.Index(fields=["owner", "posted_date"], name="txn_owner_posted_date_idx")]
        constraints = [
            models.CheckConstraint(condition=models.Q(amount_minor__gt=0), name="transaction_amount_positive"),
            models.CheckConstraint(
                condition=~models.Q(kind="expense") | models.Q(flow="debit"),
                name="transaction_expense_debit",
            ),
            models.CheckConstraint(
                condition=~models.Q(kind__in=["refund", "income"]) | models.Q(flow="credit"),
                name="transaction_refund_income_credit",
            ),
        ]

    def clean(self):
        errors = ownership_errors(self, "account", "category", "source_row")
        errors.update(flow_errors(self.kind, self.flow))
        account = related(self, "account")
        if account and self.currency != account.currency:
            errors["currency"] = "The transaction currency must match its account."
        source = related(self, "source_row")
        if source:
            statement = related(source, "statement")
            if statement and statement.account_id != self.account_id:
                errors["source_row"] = "The source row must come from the same account."
            if self.confirmed:
                if source.review_status != ImportedTransaction.ReviewStatus.ACCEPTED:
                    errors["source_row"] = "Confirm only accepted source rows."
                if statement and statement.status not in {Statement.Status.REVIEWED, Statement.Status.IMPORTED}:
                    errors["source_row"] = "Review the source statement before confirming transactions."
        if errors:
            raise ValidationError(errors)


class CategoryRule(OwnedModel):
    class MatchType(models.TextChoices):
        CONTAINS = "contains", "Contains"
        EXACT = "exact", "Exact"

    class Target(models.TextChoices):
        MERCHANT = "merchant", "Merchant"
        DESCRIPTION = "description", "Description"

    account = models.ForeignKey(Account, null=True, blank=True, on_delete=models.PROTECT, related_name="category_rules")
    category = models.ForeignKey(Category, on_delete=models.PROTECT, related_name="rules")
    match_text = models.CharField(max_length=200)
    match_type = models.CharField(max_length=10, choices=MatchType.choices, default=MatchType.CONTAINS)
    target = models.CharField(max_length=20, choices=Target.choices, default=Target.MERCHANT)
    priority = models.PositiveIntegerField(default=100)
    is_active = models.BooleanField(default=True)

    def clean(self):
        errors = ownership_errors(self, "account", "category")
        if errors:
            raise ValidationError(errors)


class ProcessingJob(OwnedModel):
    class Kind(models.TextChoices):
        PARSE_STATEMENT = "parse_statement", "Parse statement"
        IMPORT_STATEMENT = "import_statement", "Import statement"

    class Status(models.TextChoices):
        QUEUED = "queued", "Queued"
        RUNNING = "running", "Running"
        SUCCEEDED = "succeeded", "Succeeded"
        FAILED = "failed", "Failed"

    statement = models.ForeignKey(Statement, on_delete=models.PROTECT, related_name="jobs")
    kind = models.CharField(max_length=20, choices=Kind.choices)
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.QUEUED)
    attempts = models.PositiveIntegerField(default=0)
    started_at = models.DateTimeField(null=True, blank=True)
    finished_at = models.DateTimeField(null=True, blank=True)
    error_message = models.TextField(blank=True)

    def clean(self):
        errors = ownership_errors(self, "statement")
        if self.started_at and self.finished_at and self.finished_at < self.started_at:
            errors["finished_at"] = "The finish time must follow the start time."
        if errors:
            raise ValidationError(errors)
