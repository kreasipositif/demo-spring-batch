#!/usr/bin/env python3
"""
generate_transactions.py
Generates a sample CSV with 100,000 transaction rows for the batch-processor demo.

Data is intentionally aligned with the mock seed data in:
  - config-service          (valid bank codes + transaction-type limits)
  - account-validation-service  (seeded accounts with ACTIVE / INACTIVE / BLOCKED status)

Invalid rows are deliberately injected at a configurable rate so the processor's
rejection path (bank-code check, account-status check, amount-minimum check) is
exercised in every run.

Usage:
    python3 generate_transactions.py
    python3 generate_transactions.py --rows 100000 --out transactions.csv
    python3 generate_transactions.py --rows 1000   --out sample.csv --invalid-rate 0.20
"""
import argparse
import csv
import random
import os
from dataclasses import dataclass
from typing import Optional


# ── config-service: valid bank codes ────────────────────────────────────────────
# Source: config-service/src/main/resources/application.yml → bank-config.valid-bank-codes
VALID_BANK_CODES = [
    "BCA",     # Bank Central Asia
    "BNI",     # Bank Negara Indonesia
    "BRI",     # Bank Rakyat Indonesia
    "MANDIRI", # Bank Mandiri
    "CIMB",    # CIMB Niaga
    "DANAMON", # Bank Danamon
    "PERMATA", # Bank Permata
    "BTN",     # Bank Tabungan Negara
    "BSI",     # Bank Syariah Indonesia
    "OCBC",    # OCBC Indonesia
]

# Bank codes NOT in config-service — used to inject invalid-bank-code rows
INVALID_BANK_CODES = ["XENDIT", "GOPAY", "OVO", "DANA", "FAKE_BANK"]

# ── config-service: transaction types + amount limits (IDR) ─────────────────────
# Source: config-service/src/main/resources/application.yml → transaction-config.limits
@dataclass
class TxLimit:
    tx_type: str
    min_amount: int
    max_amount: int

TRANSACTION_LIMITS = [
    TxLimit("TRANSFER",   10_000,  1_000_000_000),
    TxLimit("PAYMENT",     1_000,    500_000_000),
    TxLimit("TOPUP",      10_000,     50_000_000),
    TxLimit("WITHDRAWAL", 50_000,     20_000_000),
]
TX_LIMIT_MAP = {t.tx_type: t for t in TRANSACTION_LIMITS}

# ── account-validation-service: seeded accounts ─────────────────────────────────
# Source: account-validation-service/src/main/resources/application.yml → mock.accounts
# (account_number, account_name, bank_code, status)
@dataclass
class MockAccount:
    account_number: str
    account_name: str
    bank_code: str
    status: str   # ACTIVE | INACTIVE | BLOCKED

SEEDED_ACCOUNTS = [
    MockAccount("1234567890", "Budi Santoso",   "BCA",     "ACTIVE"),
    MockAccount("0987654321", "Siti Rahayu",    "BNI",     "ACTIVE"),
    MockAccount("1122334455", "Ahmad Fauzi",    "BRI",     "ACTIVE"),
    MockAccount("5544332211", "Dewi Lestari",   "MANDIRI", "ACTIVE"),
    MockAccount("6677889900", "Rudi Hermawan",  "CIMB",    "INACTIVE"),
    MockAccount("9900112233", "Rina Kusuma",    "DANAMON", "ACTIVE"),
    MockAccount("3344556677", "Hendra Gunawan", "PERMATA", "BLOCKED"),
    MockAccount("7788990011", "Yuni Astuti",    "BTN",     "ACTIVE"),
    MockAccount("2233445566", "Fajar Nugroho",  "BSI",     "ACTIVE"),
    MockAccount("4455667788", "Indah Permata",  "OCBC",    "ACTIVE"),
    MockAccount("1357924680", "Wahyu Prasetyo", "BCA",     "ACTIVE"),
    MockAccount("2468013579", "Maya Sari",      "BRI",     "ACTIVE"),
    MockAccount("1111222233", "Doni Kurniawan", "MANDIRI", "ACTIVE"),
    MockAccount("4444555566", "Lina Marlina",   "BNI",     "INACTIVE"),
    MockAccount("7777888899", "Agus Salim",     "BSI",     "ACTIVE"),
]

# Partition into active / non-active for convenient sampling
ACTIVE_ACCOUNTS   = [a for a in SEEDED_ACCOUNTS if a.status == "ACTIVE"]
INACTIVE_ACCOUNTS = [a for a in SEEDED_ACCOUNTS if a.status == "INACTIVE"]
BLOCKED_ACCOUNTS  = [a for a in SEEDED_ACCOUNTS if a.status == "BLOCKED"]

# ── notes ────────────────────────────────────────────────────────────────────────
NOTES = [
    "Monthly salary", "Invoice #4521", "Gift transfer", "Loan repayment",
    "School fees", "Business payment", "Online purchase", "Utility bill",
    "Insurance premium", "Subscription renewal", "", "", "",   # empty = no note
]


# ── helpers ──────────────────────────────────────────────────────────────────────

def pick_valid_amount(tx_type: str) -> int:
    """Return an amount that satisfies the configured min/max for the given type."""
    limit = TX_LIMIT_MAP[tx_type]
    return random.randint(limit.min_amount, limit.max_amount)


def pick_below_min_amount(tx_type: str) -> int:
    """Return an amount strictly below the configured minimum."""
    limit = TX_LIMIT_MAP[tx_type]
    if limit.min_amount <= 1:
        return 0
    return random.randint(1, limit.min_amount - 1)


def unknown_account_number() -> str:
    """Return a 10-digit number guaranteed not to be in SEEDED_ACCOUNTS."""
    seeded = {a.account_number for a in SEEDED_ACCOUNTS}
    while True:
        num = str(random.randint(5_000_000_000, 9_999_999_999))
        if num not in seeded:
            return num


# ── row builders ─────────────────────────────────────────────────────────────────

def build_valid_row(i: int) -> list:
    """Both accounts are ACTIVE and known; bank codes and amount all valid."""
    tx_type  = random.choice(TRANSACTION_LIMITS).tx_type
    src      = random.choice(ACTIVE_ACCOUNTS)
    bene     = random.choice([a for a in ACTIVE_ACCOUNTS if a != src])
    amount   = pick_valid_amount(tx_type)
    return [
        f"TRX-{i:07d}",
        src.account_number,  src.account_name,  src.bank_code,
        bene.account_number, bene.account_name, bene.bank_code,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


def build_invalid_bank_code_row(i: int) -> list:
    """One or both bank codes are unknown (not in config-service)."""
    tx_type  = random.choice(TRANSACTION_LIMITS).tx_type
    src      = random.choice(ACTIVE_ACCOUNTS)
    bene     = random.choice(ACTIVE_ACCOUNTS)
    amount   = pick_valid_amount(tx_type)
    bad_bank = random.choice(INVALID_BANK_CODES)
    # randomly corrupt source, beneficiary, or both
    src_bank  = bad_bank if random.random() < 0.5 else src.bank_code
    bene_bank = bad_bank if src_bank == src.bank_code else bene.bank_code
    return [
        f"TRX-{i:07d}",
        src.account_number,  src.account_name,  src_bank,
        bene.account_number, bene.account_name, bene_bank,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


def build_inactive_account_row(i: int) -> list:
    """Source or beneficiary account is INACTIVE."""
    tx_type  = random.choice(TRANSACTION_LIMITS).tx_type
    amount   = pick_valid_amount(tx_type)
    bad_acct = random.choice(INACTIVE_ACCOUNTS)
    good_acct = random.choice(ACTIVE_ACCOUNTS)
    if random.random() < 0.5:
        src, bene = bad_acct, good_acct
    else:
        src, bene = good_acct, bad_acct
    return [
        f"TRX-{i:07d}",
        src.account_number,  src.account_name,  src.bank_code,
        bene.account_number, bene.account_name, bene.bank_code,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


def build_blocked_account_row(i: int) -> list:
    """Source or beneficiary account is BLOCKED."""
    tx_type   = random.choice(TRANSACTION_LIMITS).tx_type
    amount    = pick_valid_amount(tx_type)
    bad_acct  = random.choice(BLOCKED_ACCOUNTS)
    good_acct = random.choice(ACTIVE_ACCOUNTS)
    if random.random() < 0.5:
        src, bene = bad_acct, good_acct
    else:
        src, bene = good_acct, bad_acct
    return [
        f"TRX-{i:07d}",
        src.account_number,  src.account_name,  src.bank_code,
        bene.account_number, bene.account_name, bene.bank_code,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


def build_unknown_account_row(i: int) -> list:
    """Source or beneficiary account number is not in account-validation-service."""
    tx_type   = random.choice(TRANSACTION_LIMITS).tx_type
    amount    = pick_valid_amount(tx_type)
    good_acct = random.choice(ACTIVE_ACCOUNTS)
    unknown_no = unknown_account_number()
    unknown_bank = random.choice(VALID_BANK_CODES)
    if random.random() < 0.5:
        src_no, src_name, src_bank = unknown_no,            "Unknown Person", unknown_bank
        bene_no, bene_name, bene_bank = good_acct.account_number, good_acct.account_name, good_acct.bank_code
    else:
        src_no, src_name, src_bank = good_acct.account_number, good_acct.account_name, good_acct.bank_code
        bene_no, bene_name, bene_bank = unknown_no,           "Unknown Person", unknown_bank
    return [
        f"TRX-{i:07d}",
        src_no,  src_name,  src_bank,
        bene_no, bene_name, bene_bank,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


def build_below_minimum_amount_row(i: int) -> list:
    """Amount is below the configured minimum for the transaction type."""
    tx_type   = random.choice(TRANSACTION_LIMITS).tx_type
    src       = random.choice(ACTIVE_ACCOUNTS)
    bene      = random.choice([a for a in ACTIVE_ACCOUNTS if a != src])
    amount    = pick_below_min_amount(tx_type)
    return [
        f"TRX-{i:07d}",
        src.account_number,  src.account_name,  src.bank_code,
        bene.account_number, bene.account_name, bene.bank_code,
        "IDR", amount, tx_type,
        random.choice(NOTES),
    ]


# ── invalid row type weights (must sum to 1.0) ───────────────────────────────────
INVALID_BUILDERS = [
    (0.30, build_invalid_bank_code_row),    # 30 % — unknown bank code
    (0.25, build_inactive_account_row),     # 25 % — INACTIVE account
    (0.15, build_blocked_account_row),      # 15 % — BLOCKED account
    (0.20, build_unknown_account_row),      # 20 % — account not found
    (0.10, build_below_minimum_amount_row), # 10 % — amount < minimum
]
_INVALID_WEIGHTS  = [w for w, _ in INVALID_BUILDERS]
_INVALID_FNS      = [fn for _, fn in INVALID_BUILDERS]


def pick_invalid_row(i: int) -> list:
    fn = random.choices(_INVALID_FNS, weights=_INVALID_WEIGHTS, k=1)[0]
    return fn(i)


# ── main ─────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Generate sample transaction CSV")
    parser.add_argument("--rows",         type=int,   default=100_000,
                        help="Total number of data rows (default: 100 000)")
    parser.add_argument("--out",          type=str,   default="transactions.csv",
                        help="Output file path")
    parser.add_argument("--invalid-rate", type=float, default=0.15,
                        help="Fraction of rows that are intentionally invalid (default: 0.15 = 15%%)")
    parser.add_argument("--seed",         type=int,   default=None,
                        help="Random seed for reproducible output")
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    out_dir = os.path.dirname(args.out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    header = [
        "Reference ID",
        "Source Account", "Source Account Name", "Source Bank Code",
        "Beneficiary Account", "Beneficiary Account Name", "Beneficiary Bank Code",
        "Currency", "Amount", "Transaction Type", "Note",
    ]

    invalid_rate = max(0.0, min(1.0, args.invalid_rate))
    expected_invalid = int(args.rows * invalid_rate)
    expected_valid   = args.rows - expected_invalid

    print(f"Generating {args.rows:,} rows → {args.out}")
    print(f"  valid rows  : ~{expected_valid:,}  ({100 - invalid_rate * 100:.0f}%)")
    print(f"  invalid rows: ~{expected_invalid:,}  ({invalid_rate * 100:.0f}%)")
    print(f"  invalid breakdown: {', '.join(f'{w*100:.0f}% {fn.__name__[6:]}' for w, fn in INVALID_BUILDERS)}")
    print()

    valid_count = invalid_count = 0

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(header)

        for i in range(1, args.rows + 1):
            if random.random() < invalid_rate:
                row = pick_invalid_row(i)
                invalid_count += 1
            else:
                row = build_valid_row(i)
                valid_count += 1

            writer.writerow(row)

            if i % 10_000 == 0:
                print(f"  {i:>7,} / {args.rows:,} rows written …")

    print()
    print(f"Done — {args.out}")
    print(f"  actual valid  : {valid_count:,}")
    print(f"  actual invalid: {invalid_count:,}")


if __name__ == "__main__":
    main()
