"""Run the proposed signature feature over synthetic USD transactions."""

from datetime import date
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "apps" / "api"))

from ledger.services.spending_changes import (  # noqa: E402
    ReportingTransaction,
    StatementCoverage,
    compare_spending_changes,
)


def money(amount_minor: int, *, signed: bool = False) -> str:
    sign = "-" if amount_minor < 0 else ("+" if signed else "")
    dollars, cents = divmod(abs(amount_minor), 100)
    return f"{sign}${dollars:,}.{cents:02d}"


def sample_transactions() -> list[ReportingTransaction]:
    rows = []
    # These values and statement periods are synthetic, not bank export samples.
    examples = (
        ("dining", "Dining", "Local Restaurant", "chase-card", 12000, 32000),
        ("groceries", "Groceries", "Grocery Market", "capital-one-card", 26000, 35000),
        ("utilities", "Utilities", "Electric Utility", "amex-card", 10000, 16000),
        ("transport", "Transport", "Transit Pass", "chase-card", 10000, 7000),
    )
    for row_number, (category, name, merchant, account, previous, current) in enumerate(examples, 1):
        for month, amount in ((8, previous), (9, current)):
            rows.append(ReportingTransaction(
                id=f"{category}-{month}", posted_date=date(2026, month, 15),
                amount_minor=amount, currency="USD", kind="expense", confirmed=True,
                category_id=category, category_name=name, merchant_name=merchant,
                account_id=account, statement_id=f"{account}-2026-{month:02d}",
                source_row=row_number,
            ))
    rows.append(ReportingTransaction(
        id="dining-refund", posted_date=date(2026, 9, 20), amount_minor=2000,
        currency="USD", kind="refund", confirmed=True, category_id="dining",
        category_name="Dining", merchant_name="Local Restaurant", account_id="chase-card",
        statement_id="chase-card-2026-09", source_row=5,
    ))
    rows.append(ReportingTransaction(
        id="card-payment", posted_date=date(2026, 9, 25), amount_minor=50000,
        currency="USD", kind="card_payment", confirmed=True, account_id="chase-card",
        statement_id="chase-card-2026-09", source_row=6,
    ))
    return rows


def main() -> None:
    accounts = ("chase-card", "capital-one-card", "amex-card")
    spans = [
        StatementCoverage(account, f"{account}-2026-{month:02d}",
                          date(2026, month, 1), date(2026, month, last_day))
        for account in accounts for month, last_day in ((8, 31), (9, 30))
    ]
    report = compare_spending_changes(
        sample_transactions(), "2026-08", "2026-09", currency="USD",
        as_of=date(2026, 10, 13), coverage=spans, account_ids=accounts,
    )
    print("Synthetic demo: Why did my spending change?")
    print(f"August: {money(report.previous_total_minor)} | September: {money(report.current_total_minor)}")
    print(f"Change: {money(report.delta_minor, signed=True)} USD")
    for label, groups in (("Category contributions", report.category_changes),
                          ("Merchant contributions", report.merchant_changes)):
        print(f"\n{label}:")
        for group in groups:
            print(f"  {group.name}: {money(group.delta_minor, signed=True)}")
            print(f"    August sources: {', '.join(group.previous_transaction_ids) or '(none)'}")
            print(f"    September sources: {', '.join(group.current_transaction_ids) or '(none)'}")
    print("\nCoverage: " + "; ".join(f"{item.month} {item.status}" for item in report.coverage))
    for warning in report.warnings:
        print(f"Warning: {warning}")
    print("Refunds reduce spending; the $500 card payment is excluded.")


if __name__ == "__main__":
    main()
