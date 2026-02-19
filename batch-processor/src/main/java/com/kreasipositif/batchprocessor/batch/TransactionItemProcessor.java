package com.kreasipositif.batchprocessor.batch;

import com.kreasipositif.batchprocessor.client.AccountValidationClient;
import com.kreasipositif.batchprocessor.client.AccountValidationClient.AccountValidationRequest;
import com.kreasipositif.batchprocessor.client.AccountValidationClient.AccountValidationResponse;
import com.kreasipositif.batchprocessor.client.ConfigServiceClient;
import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Validates each {@link TransactionRecord} against downstream services.
 *
 * <h3>Validation steps</h3>
 * <ol>
 *   <li>Source bank-code — via {@code ConfigServiceClient} wrapped in <b>SemaphoreBulkhead</b>.</li>
 *   <li>Beneficiary bank-code — same bulkhead (separate permit).</li>
 *   <li>Transaction amount ≥ configured minimum for the transaction type — same bulkhead.</li>
 *   <li>Source + beneficiary account validity — via {@code AccountValidationClient} bulk endpoint,
 *       dispatched through the <b>FixedThreadPoolBulkhead</b> as a CompletableFuture.</li>
 * </ol>
 *
 * <p>Records that fail any validation are <em>not</em> filtered out; instead, {@link TransactionRecord#isValid()}
 * is set to {@code false} and {@link TransactionRecord#getValidationErrors()} contains a
 * comma-separated list of failure reasons.  This allows the writer to separate valid/invalid
 * output files.
 */
@Slf4j
@Component
public class TransactionItemProcessor implements ItemProcessor<TransactionRecord, TransactionRecord> {

    private final ConfigServiceClient configServiceClient;
    private final AccountValidationClient accountValidationClient;
    private final Bulkhead configServiceBulkhead;
    private final Bulkhead accountValidationBulkhead;
    private final ThreadPoolBulkhead accountValidationThreadPoolBulkhead;

    public TransactionItemProcessor(
            ConfigServiceClient configServiceClient,
            AccountValidationClient accountValidationClient,
            @Qualifier("configServiceBulkhead") Bulkhead configServiceBulkhead,
            @Qualifier("accountValidationBulkhead") Bulkhead accountValidationBulkhead,
            @Qualifier("accountValidationThreadPoolBulkhead") ThreadPoolBulkhead accountValidationThreadPoolBulkhead) {
        this.configServiceClient = configServiceClient;
        this.accountValidationClient = accountValidationClient;
        this.configServiceBulkhead = configServiceBulkhead;
        this.accountValidationBulkhead = accountValidationBulkhead;
        this.accountValidationThreadPoolBulkhead = accountValidationThreadPoolBulkhead;
    }

    @Override
    public TransactionRecord process(TransactionRecord record) {
        List<String> errors = new ArrayList<>();

        // ── Step 1: Source bank-code validation (SemaphoreBulkhead) ──────────
        validateBankCode(record.getSourceBankCode(), "sourceBankCode", errors);

        // ── Step 2: Beneficiary bank-code validation (SemaphoreBulkhead) ─────
        validateBankCode(record.getBeneficiaryBankCode(), "beneficiaryBankCode", errors);

        // ── Step 3: Amount validation (SemaphoreBulkhead) ────────────────────
        validateAmount(record, errors);

        // ── Step 4: Account validation via FixedThreadPoolBulkhead (async) ───
        validateAccounts(record, errors);

        if (!errors.isEmpty()) {
            record.setValid(false);
            record.setValidationErrors(String.join("; ", errors));
            log.debug("Record {} INVALID — {}", record.getReferenceId(), record.getValidationErrors());
        } else {
            log.debug("Record {} VALID", record.getReferenceId());
        }

        return record;
    }

    // ─── private helpers ─────────────────────────────────────────────────────

    private void validateBankCode(String bankCode, String fieldName, List<String> errors) {
        try {
            boolean valid = Bulkhead.decorateSupplier(configServiceBulkhead,
                    () -> configServiceClient.isBankCodeValid(bankCode)).get();
            if (!valid) {
                errors.add(fieldName + " '" + bankCode + "' is not a recognised bank code");
            }
        } catch (BulkheadFullException e) {
            log.warn("ConfigService bulkhead full while validating {}: {}", fieldName, e.getMessage());
            errors.add(fieldName + " could not be validated (bulkhead full)");
        }
    }

    private void validateAmount(TransactionRecord record, List<String> errors) {
        try {
            boolean valid = Bulkhead.decorateSupplier(configServiceBulkhead,
                    () -> configServiceClient.isAmountValid(
                            record.getTransactionType(), record.getAmount())).get();
            if (!valid) {
                errors.add("amount " + record.getAmount() + " is below the minimum for "
                        + record.getTransactionType());
            }
        } catch (BulkheadFullException e) {
            log.warn("ConfigService bulkhead full while validating amount: {}", e.getMessage());
            errors.add("amount could not be validated (bulkhead full)");
        }
    }

    private void validateAccounts(TransactionRecord record, List<String> errors) {
        List<AccountValidationRequest> requests = List.of(
                new AccountValidationRequest(record.getSourceAccount(), record.getSourceBankCode()),
                new AccountValidationRequest(record.getBeneficiaryAccount(), record.getBeneficiaryBankCode())
        );

        Supplier<List<AccountValidationResponse>> supplier =
                () -> accountValidationClient.validateBulk(requests);

        try {
            // Dispatch through FixedThreadPoolBulkhead → returns CompletionStage, cast to CompletableFuture
            @SuppressWarnings("unchecked")
            CompletableFuture<List<AccountValidationResponse>> future =
                    (CompletableFuture<List<AccountValidationResponse>>)
                            (CompletableFuture<?>) accountValidationThreadPoolBulkhead.executeSupplier(supplier);

            List<AccountValidationResponse> results = future.get(); // virtual thread: non-blocking wait

            // Build a quick lookup by accountNumber
            Map<String, AccountValidationResponse> resultMap = results.stream()
                    .collect(Collectors.toMap(
                            AccountValidationResponse::accountNumber,
                            r -> r,
                            (a, b) -> a));

            // Source account
            AccountValidationResponse srcResult = resultMap.get(record.getSourceAccount());
            if (srcResult == null || Boolean.FALSE.equals(srcResult.valid())) {
                String reason = (srcResult != null) ? srcResult.status() : "NOT_FOUND";
                errors.add("sourceAccount '" + record.getSourceAccount() + "' is invalid (" + reason + ")");
            }

            // Beneficiary account
            AccountValidationResponse beneResult = resultMap.get(record.getBeneficiaryAccount());
            if (beneResult == null || Boolean.FALSE.equals(beneResult.valid())) {
                String reason = (beneResult != null) ? beneResult.status() : "NOT_FOUND";
                errors.add("beneficiaryAccount '" + record.getBeneficiaryAccount() + "' is invalid (" + reason + ")");
            }

        } catch (BulkheadFullException e) {
            log.warn("AccountValidation ThreadPoolBulkhead full for ref {}: {}",
                    record.getReferenceId(), e.getMessage());
            errors.add("account validation could not be performed (thread-pool bulkhead full)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add("account validation interrupted");
        } catch (ExecutionException e) {
            log.warn("Account validation future failed for ref {}: {}",
                    record.getReferenceId(), e.getCause().getMessage());
            errors.add("account validation failed: " + e.getCause().getMessage());
        }
    }
}
