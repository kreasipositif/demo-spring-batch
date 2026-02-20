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
 *       dispatched through the <b>ThreadPoolBulkhead</b> as a CompletableFuture.</li>
 * </ol>
 *
 * <h3>Performance &amp; Bulkhead semantics</h3>
 * <p><b>SemaphoreBulkhead</b> is designed to be called on the <em>current</em> thread — it
 * acquires a permit, executes the call inline, then releases the permit.  Wrapping it in
 * {@code CompletableFuture.supplyAsync()} would submit hundreds of permit-acquisitions
 * simultaneously (one per chunk record × 3 calls), instantly exhausting the 20-permit
 * pool and causing {@code BulkheadFullException} under real load.
 *
 * <p>Instead, steps 1–3 execute <em>sequentially on the calling virtual-worker thread</em>
 * (each holding a semaphore permit only for the duration of its own HTTP call), while
 * step 4 is submitted to the {@code ThreadPoolBulkhead} <em>before</em> step 1 begins.
 * Because virtual threads are cheap and non-blocking, the account-validation HTTP call
 * (step 4) overlaps with the three sequential config-service calls (steps 1–3).
 * Total wall-clock cost ≈ {@code max(3 × config_latency_sequential, account_latency)},
 * e.g. with 500 ms mock latency: {@code max(3 × 500 ms, 500 ms) = 1 500 ms} — still
 * substantially better than the fully-sequential {@code 3 × 500 + 500 = 2 000 ms}.
 *
 * <p>Records that fail any validation are <em>not</em> filtered out; instead,
 * {@link TransactionRecord#isValid()} is set to {@code false} and
 * {@link TransactionRecord#getValidationErrors()} contains a comma-separated list of
 * failure reasons.  This allows the writer to separate valid/invalid output files.
 */
@Slf4j
@Component
public class TransactionItemProcessor implements ItemProcessor<TransactionRecord, TransactionRecord> {

    private final ConfigServiceClient configServiceClient;
    private final AccountValidationClient accountValidationClient;
    private final Bulkhead configServiceBulkhead;
    /** Declared for demo purposes — shows the bulkhead bean is wired; not used in the hot path. */
    @SuppressWarnings("unused")
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

        // ── Step 4 submitted FIRST: account-validation via ThreadPoolBulkhead ─
        // Dispatched immediately so it runs concurrently while this virtual-worker
        // thread performs the sequential config-service calls below.
        List<AccountValidationRequest> requests = List.of(
                new AccountValidationRequest(record.getSourceAccount(), record.getSourceBankCode()),
                new AccountValidationRequest(record.getBeneficiaryAccount(), record.getBeneficiaryBankCode())
        );

        Supplier<List<AccountValidationResponse>> accountSupplier =
                () -> accountValidationClient.validateBulk(requests);

        @SuppressWarnings("unchecked")
        CompletableFuture<List<AccountValidationResponse>> accountFuture =
                (CompletableFuture<List<AccountValidationResponse>>)
                        (CompletableFuture<?>) accountValidationThreadPoolBulkhead.executeSupplier(accountSupplier);

        // ── Steps 1–3: config-service calls via SemaphoreBulkhead ────────────
        // SemaphoreBulkhead acquires a permit, runs the call inline on this
        // (virtual) thread, then releases it — sequential, but overlapping with
        // the account-validation future already in flight above.
        try {
            // Step 1 — source bank-code
            if (!Bulkhead.decorateSupplier(configServiceBulkhead,
                    () -> configServiceClient.isBankCodeValid(record.getSourceBankCode())).get()) {
                errors.add("sourceBankCode '" + record.getSourceBankCode() + "' is not a recognised bank code");
            }
            // Step 2 — beneficiary bank-code
            if (!Bulkhead.decorateSupplier(configServiceBulkhead,
                    () -> configServiceClient.isBankCodeValid(record.getBeneficiaryBankCode())).get()) {
                errors.add("beneficiaryBankCode '" + record.getBeneficiaryBankCode() + "' is not a recognised bank code");
            }
            // Step 3 — amount minimum
            if (!Bulkhead.decorateSupplier(configServiceBulkhead,
                    () -> configServiceClient.isAmountValid(
                            record.getTransactionType(), record.getAmount())).get()) {
                errors.add("amount " + record.getAmount() + " is below the minimum for " + record.getTransactionType());
            }
            // Step 4 — join account-validation future (already running in parallel)
            collectAccountErrors(accountFuture.get(), record, errors);

        } catch (BulkheadFullException e) {
            log.warn("Bulkhead full while processing ref {}: {}", record.getReferenceId(), e.getMessage());
            errors.add("validation could not be performed (bulkhead full)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add("validation interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BulkheadFullException) {
                log.warn("Bulkhead full for ref {}: {}", record.getReferenceId(), cause.getMessage());
                errors.add("validation could not be performed (bulkhead full)");
            } else {
                log.warn("Validation future failed for ref {}: {}", record.getReferenceId(), cause.getMessage());
                errors.add("validation failed: " + cause.getMessage());
            }
        }

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

    private void collectAccountErrors(List<AccountValidationResponse> results,
                                      TransactionRecord record, List<String> errors) {
        if (results == null || results.isEmpty()) {
            errors.add("account validation service returned no results");
            return;
        }
        Map<String, AccountValidationResponse> byAccount = results.stream()
                .collect(Collectors.toMap(
                        AccountValidationResponse::accountNumber,
                        r -> r,
                        (a, b) -> a));

        AccountValidationResponse srcResult = byAccount.get(record.getSourceAccount());
        if (srcResult == null || Boolean.FALSE.equals(srcResult.valid())) {
            String reason = (srcResult != null) ? srcResult.status() : "NOT_FOUND";
            errors.add("sourceAccount '" + record.getSourceAccount() + "' is invalid (" + reason + ")");
        }

        AccountValidationResponse beneResult = byAccount.get(record.getBeneficiaryAccount());
        if (beneResult == null || Boolean.FALSE.equals(beneResult.valid())) {
            String reason = (beneResult != null) ? beneResult.status() : "NOT_FOUND";
            errors.add("beneficiaryAccount '" + record.getBeneficiaryAccount() + "' is invalid (" + reason + ")");
        }
    }
}
