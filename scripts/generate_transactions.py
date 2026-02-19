#!/usr/bin/env python3
"""
generate_transactions.py
Generates a sample CSV with 100,000 transaction rows for the batch-processor demo.

Usage:
    python3 generate_transactions.py
    python3 generate_transactions.py --rows 100000 --out transactions.csv
"""
import argparse
import csv
import random
import os

BANK_CODES = ["BCA", "MANDIRI", "BNI", "BRI", "CIMB", "PERMATA",
              "DANAMON", "OCBC", "PANIN", "MAYBANK"]
# A handful will be invalid to exercise the rejection path
INVALID_BANK_CODES = ["INVALID_01", "FAKE_BANK"]

TRANSACTION_TYPES = ["DOMESTIC_TRANSFER", "INTERNATIONAL_TRANSFER",
                     "BILL_PAYMENT", "MERCHANT_PAYMENT"]

CURRENCIES = ["IDR", "USD", "SGD"]

FIRST_NAMES = ["Alice", "Bob", "Carol", "David", "Eve", "Frank",
               "Grace", "Hank", "Iris", "Jack", "Karen", "Leo",
               "Mia", "Nate", "Olivia", "Peter", "Quinn", "Rose"]
LAST_NAMES = ["Tan", "Lee", "Kim", "Wong", "Santos", "Liu",
              "Garcia", "Müller", "Nguyen", "Patel", "Okafor"]

NOTES = ["Monthly rent", "Invoice #1234", "Gift transfer", "Loan repayment",
         "School fees", "Business payment", "", "", ""]  # empty = no note


def random_account() -> str:
    return str(random.randint(1_000_000_000, 9_999_999_999))


def random_name() -> str:
    return f"{random.choice(FIRST_NAMES)} {random.choice(LAST_NAMES)}"


def random_bank(invalid_rate: float = 0.03) -> str:
    """Returns an invalid bank code ~invalid_rate of the time."""
    if random.random() < invalid_rate:
        return random.choice(INVALID_BANK_CODES)
    return random.choice(BANK_CODES)


def random_amount(tx_type: str) -> str:
    if tx_type == "INTERNATIONAL_TRANSFER":
        return str(random.randint(100, 50_000))          # USD/SGD range
    return str(random.randint(10_000, 100_000_000))      # IDR range


def main():
    parser = argparse.ArgumentParser(description="Generate sample transaction CSV")
    parser.add_argument("--rows", type=int, default=100_000, help="Number of data rows")
    parser.add_argument("--out", type=str, default="transactions.csv", help="Output file path")
    args = parser.parse_args()

    out_dir = os.path.dirname(args.out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    header = [
        "Reference ID", "Source Account", "Source Account Name", "Source Bank Code",
        "Beneficiary Account", "Beneficiary Account Name", "Beneficiary Bank Code",
        "Currency", "Amount", "Transaction Type", "Note"
    ]

    print(f"Generating {args.rows:,} rows → {args.out} …")

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(header)

        for i in range(1, args.rows + 1):
            tx_type = random.choice(TRANSACTION_TYPES)
            currency = "IDR" if tx_type in ("DOMESTIC_TRANSFER", "BILL_PAYMENT", "MERCHANT_PAYMENT") \
                else random.choice(["USD", "SGD"])

            writer.writerow([
                f"TRX-{i:07d}",
                random_account(),
                random_name(),
                random_bank(),
                random_account(),
                random_name(),
                random_bank(),
                currency,
                random_amount(tx_type),
                tx_type,
                random.choice(NOTES),
            ])

            if i % 10_000 == 0:
                print(f"  {i:>7,} / {args.rows:,} rows written …")

    print(f"Done — {args.out}")


if __name__ == "__main__":
    main()
