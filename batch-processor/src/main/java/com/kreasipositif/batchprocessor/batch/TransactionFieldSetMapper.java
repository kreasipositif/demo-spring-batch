package com.kreasipositif.batchprocessor.batch;

import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

import java.math.BigDecimal;

/**
 * Maps a parsed CSV {@link FieldSet} to a {@link TransactionRecord}.
 *
 * <p>Column indices (0-based), matching the CSV header:
 * <pre>
 *   0  Reference ID
 *   1  Source Account
 *   2  Source Account Name
 *   3  Source Bank Code
 *   4  Beneficiary Account
 *   5  Beneficiary Account Name
 *   6  Beneficiary Bank Code
 *   7  Currency
 *   8  Amount
 *   9  Transaction Type
 *  10  Note  (optional — may be absent)
 * </pre>
 */
@Component
public class TransactionFieldSetMapper implements FieldSetMapper<TransactionRecord> {

    @Override
    public TransactionRecord mapFieldSet(FieldSet fieldSet) throws BindException {
        String rawAmount = fieldSet.readString(8).trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount);
        } catch (NumberFormatException e) {
            amount = BigDecimal.ZERO;
        }

        String note = (fieldSet.getFieldCount() > 10) ? fieldSet.readString(10).trim() : "";

        return TransactionRecord.builder()
                .referenceId(fieldSet.readString(0).trim())
                .sourceAccount(fieldSet.readString(1).trim())
                .sourceAccountName(fieldSet.readString(2).trim())
                .sourceBankCode(fieldSet.readString(3).trim())
                .beneficiaryAccount(fieldSet.readString(4).trim())
                .beneficiaryAccountName(fieldSet.readString(5).trim())
                .beneficiaryBankCode(fieldSet.readString(6).trim())
                .currency(fieldSet.readString(7).trim())
                .amount(amount)
                .transactionType(fieldSet.readString(9).trim())
                .note(note)
                .valid(true)
                .build();
    }
}
