"""Deterministic spending comparisons over reviewed, source-linked transactions."""

from calendar import monthrange
from dataclasses import dataclass
from datetime import date, timedelta
import re
from typing import Iterable


@dataclass(frozen=True)
class ReportingTransaction:
    id: str
    posted_date: date
    amount_minor: int
    currency: str
    kind: str
    confirmed: bool
    category_id: str | None = None
    category_name: str = "Uncategorized"
    merchant_name: str = "Unknown merchant"
    account_id: str = ""
    statement_id: str = ""
    source_row: int | None = None
    source_page: int | None = None

    def __post_init__(self):
        if not self.id:
            raise ValueError("Transaction IDs must be nonempty.")
        if type(self.amount_minor) is not int or self.amount_minor <= 0:
            raise ValueError("amount_minor must be a positive integer.")
        if not re.fullmatch(r"[A-Z]{3}", self.currency):
            raise ValueError("Currency must contain three uppercase letters.")


@dataclass(frozen=True)
class StatementCoverage:
    account_id: str
    statement_id: str
    starts_on: date
    ends_on: date

    def __post_init__(self):
        if not self.account_id or not self.statement_id:
            raise ValueError("Coverage needs an account and statement ID.")
        if self.starts_on > self.ends_on:
            raise ValueError("Coverage start must precede its end.")


@dataclass(frozen=True)
class GroupChange:
    key: str
    name: str
    previous_total_minor: int
    current_total_minor: int
    previous_transaction_ids: tuple[str, ...]
    current_transaction_ids: tuple[str, ...]

    @property
    def delta_minor(self) -> int:
        return self.current_total_minor - self.previous_total_minor


@dataclass(frozen=True)
class MonthCoverage:
    month: str
    status: str
    account_ids: tuple[str, ...]
    uncovered_account_ids: tuple[str, ...]
    statement_ids: tuple[str, ...]


@dataclass(frozen=True)
class SpendingChangeReport:
    previous_month: str
    current_month: str
    currency: str
    previous_total_minor: int
    current_total_minor: int
    category_changes: tuple[GroupChange, ...]
    merchant_changes: tuple[GroupChange, ...]
    coverage: tuple[MonthCoverage, ...]
    warnings: tuple[str, ...]

    @property
    def delta_minor(self) -> int:
        return self.current_total_minor - self.previous_total_minor


@dataclass(frozen=True)
class _Month:
    label: str
    start: date
    end: date


def _month(value: str, as_of: date) -> _Month:
    if not re.fullmatch(r"\d{4}-\d{2}", value):
        raise ValueError("Months must use YYYY-MM.")
    year, month = map(int, value.split("-"))
    start = date(year, month, 1)
    end = date(year, month, monthrange(year, month)[1])
    if end >= as_of:
        raise ValueError(f"{value} is not a completed month as of {as_of}.")
    return _Month(value, start, end)


def _groups(transactions, periods, category: bool) -> tuple[GroupChange, ...]:
    groups: dict[str, tuple[set[str], list[int], list[list[str]]]] = {}
    for tx in transactions:
        if category:
            key = f"category:{tx.category_id}" if tx.category_id is not None else "uncategorized"
            name = tx.category_name if tx.category_id is not None else "Uncategorized"
        else:
            name = " ".join(tx.merchant_name.split()) or "Unknown merchant"
            key = name.casefold()
        names, totals, ids = groups.setdefault(key, (set(), [0, 0], [[], []]))
        period = 0 if periods[0].start <= tx.posted_date <= periods[0].end else 1
        names.add(name)
        totals[period] += tx.amount_minor * (-1 if tx.kind == "refund" else 1)
        ids[period].append(tx.id)
    changes = [
        GroupChange(key, min(names), totals[0], totals[1], tuple(sorted(ids[0])), tuple(sorted(ids[1])))
        for key, (names, totals, ids) in groups.items()
    ]
    return tuple(sorted(changes, key=lambda group: (-abs(group.delta_minor), group.key)))


def _covers_month(intervals, month: _Month) -> bool:
    cursor = month.start
    for span in sorted(intervals, key=lambda span: (span.starts_on, span.ends_on)):
        if span.ends_on < cursor:
            continue
        if span.starts_on > cursor:
            return False
        if span.ends_on >= month.end:
            return True
        cursor = span.ends_on + timedelta(days=1)
    return False


def _coverage(month, spans, expected_accounts, known_accounts) -> MonthCoverage:
    accounts = expected_accounts if expected_accounts is not None else known_accounts
    relevant = tuple(
        span for span in (spans or ())
        if span.account_id in accounts and span.starts_on <= month.end and span.ends_on >= month.start
    )
    uncovered = tuple(
        account for account in accounts
        if not _covers_month((span for span in relevant if span.account_id == account), month)
    )
    status = "unknown" if spans is None or expected_accounts is None else ("partial" if uncovered else "complete")
    return MonthCoverage(month.label, status, accounts, uncovered, tuple(sorted({span.statement_id for span in relevant})))


def compare_spending_changes(
    transactions: Iterable[ReportingTransaction],
    previous_month: str,
    current_month: str,
    *,
    currency: str,
    as_of: date,
    coverage: Iterable[StatementCoverage] | None = None,
    account_ids: Iterable[str] | None = None,
) -> SpendingChangeReport:
    """Compare net expenses; coverage requires intervals and the full account universe.

    Expense/refund dates use the caller's posted-date convention. Refunds reduce
    spending in their posted month. Other kinds and unconfirmed rows are excluded.
    All supplied transaction IDs must be unique, even for excluded rows.
    """
    if not re.fullmatch(r"[A-Z]{3}", currency):
        raise ValueError("An explicit three-letter uppercase currency is required.")
    periods = (_month(previous_month, as_of), _month(current_month, as_of))
    if periods[0].start >= periods[1].start:
        raise ValueError("The previous month must precede the current month.")
    rows = tuple(transactions)
    if len({tx.id for tx in rows}) != len(rows):
        raise ValueError("Duplicate transaction IDs are not allowed.")
    eligible = tuple(
        tx for tx in rows if tx.confirmed and tx.kind in {"expense", "refund"}
        and any(month.start <= tx.posted_date <= month.end for month in periods)
    )
    selected = tuple(tx for tx in eligible if tx.currency == currency)
    spans = None if coverage is None else tuple(coverage)
    expected = None if account_ids is None else tuple(sorted(set(account_ids)))
    if expected is not None and (not expected or any(not account for account in expected)):
        raise ValueError("The expected account list must be nonempty and contain valid IDs.")
    if expected is not None and any(tx.account_id not in expected for tx in selected):
        raise ValueError("The expected account list omits a selected transaction's account.")
    known = tuple(sorted({tx.account_id for tx in selected if tx.account_id} | {span.account_id for span in (spans or ())}))
    month_coverage = tuple(_coverage(month, spans, expected, known) for month in periods)
    warnings = [
        f"Coverage is {item.status} for {item.month}."
        for item in month_coverage if item.status != "complete"
    ]
    if any(tx.currency != currency for tx in eligible):
        warnings.append(f"Transactions in other currencies were excluded from this {currency} comparison.")
    categories = _groups(selected, periods, category=True)
    merchants = _groups(selected, periods, category=False)
    return SpendingChangeReport(
        previous_month, current_month, currency,
        sum(group.previous_total_minor for group in categories),
        sum(group.current_total_minor for group in categories),
        categories, merchants, month_coverage, tuple(warnings),
    )
