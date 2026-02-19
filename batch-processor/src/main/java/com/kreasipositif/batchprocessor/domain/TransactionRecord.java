package com.kreasipositif.batchprocessor.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Domain model representing a single row from the input CSV file.
 *
 * <p>CSV column order:
 * <pre>
 *   Reference ID, Source Account, Source Account Name, Source Bank Code,
 *   Beneficiary Account, Beneficiary Account Name, Beneficiary Bank Code,
 *   Currency, Amount, Transaction Type, Note
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {

    /** Unique reference identifier for the transaction (e.g. TRX-000001). */
    private String referenceId;

    /** Debit / originating account number. */
    private String sourceAccount;

    /** Full name registered on the source account. */
    private String sourceAccountName;

    /** Bank code of the source account (validated against config-service). */
    private String sourceBankCode;

    /** Credit / destination account number. */
    private String beneficiaryAccount;

    /** Full name registered on the beneficiary account. */
    private String beneficiaryAccountName;

    /** Bank code of the beneficiary account (validated against config-service). */
    private String beneficiaryBankCode;

    /** ISO 4217 currency code, e.g. IDR, USD. */
    private String currency;

    /** Transaction amount – must exceed the configured minimum for the transaction type. */
    private BigDecimal amount;

    /** Transaction type (e.g. DOMESTIC_TRANSFER, INTERNATIONAL_TRANSFER). */
    private String transactionType;

    /** Optional free-text note; may be null or empty. */
    private String note;

    // ── Validation result fields (populated by TransactionItemProcessor) ─────

    /** {@code true} when all validations pass. */
    @Builder.Default
    private boolean valid = true;

    /** Human-readable list of validation failures, if any. */
    private String validationErrors;
}
