package com.kreasipositif.batchprocessor;

import com.kreasipositif.batchprocessor.batch.TransactionFieldSetMapper;
import com.kreasipositif.batchprocessor.batch.TransactionItemProcessor;
import com.kreasipositif.batchprocessor.client.AccountValidationClient;
import com.kreasipositif.batchprocessor.client.ConfigServiceClient;
import com.kreasipositif.batchprocessor.config.BatchConfig;
import com.kreasipositif.batchprocessor.config.Resilience4jConfig;
import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Spring Batch integration tests.
 *
 * <p>Uses a small 5-record CSV test fixture with:
 * <ul>
 *   <li>3 valid records</li>
 *   <li>1 record with an invalid bank code</li>
 *   <li>1 record with an invalid account</li>
 * </ul>
 *
 * <p>Config-service and account-validation-service calls are mocked so the tests run
 * without requiring running downstream services.
 */
@SpringBatchTest
@SpringBootTest(classes = {
        BatchProcessorApplication.class,
        BatchConfig.class,
        Resilience4jConfig.class,
        TransactionFieldSetMapper.class,
        TransactionItemProcessor.class
})
@TestPropertySource(properties = {
        "batch.input-file=classpath:data/test-transactions.csv",
        "batch.chunk-size=2",
        "batch.grid-size=2",
        "batch.output-file=${java.io.tmpdir}/batch-test-output/results.csv",
        "downstream.config-service.base-url=http://localhost:8081",
        "downstream.account-validation-service.base-url=http://localhost:8082"
})
class BatchProcessorIntegrationTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private Job transactionValidationJob;

    @MockitoBean
    private ConfigServiceClient configServiceClient;

    @MockitoBean
    private AccountValidationClient accountValidationClient;

    @Test
    void job_completesSuccessfully_withMixedValidAndInvalidRecords() throws Exception {
        // ── Mock config-service ──────────────────────────────────────────────
        // BCA, MANDIRI, BNI are valid; INVALID_BANK is not
        when(configServiceClient.isBankCodeValid(eq("BCA"))).thenReturn(true);
        when(configServiceClient.isBankCodeValid(eq("MANDIRI"))).thenReturn(true);
        when(configServiceClient.isBankCodeValid(eq("BNI"))).thenReturn(true);
        when(configServiceClient.isBankCodeValid(eq("INVALID_BANK"))).thenReturn(false);
        when(configServiceClient.isBankCodeValid(eq("CIMB"))).thenReturn(true);

        when(configServiceClient.isAmountValid(anyString(), any(BigDecimal.class))).thenReturn(true);

        // ── Mock account-validation-service ──────────────────────────────────
        // Returns valid for all known accounts; UNKNOWN_ACC is not found
        when(accountValidationClient.validateBulk(anyList())).thenAnswer(inv -> {
            List<AccountValidationClient.AccountValidationRequest> reqs = inv.getArgument(0);
            return reqs.stream().map(r -> {
                boolean valid = !r.accountNumber().contains("UNKNOWN");
                String status = valid ? "ACTIVE" : "NOT_FOUND";
                return new AccountValidationClient.AccountValidationResponse(
                        r.accountNumber(), r.bankCode(), "Test Account", valid, status, null);
            }).toList();
        });

        // ── Run ──────────────────────────────────────────────────────────────
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "classpath:data/test-transactions.csv")
                .addLong("startedAt", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void processor_marksRecord_invalid_whenBankCodeFails() {
        // Bank codes both invalid → record should be marked invalid
        when(configServiceClient.isBankCodeValid(anyString())).thenReturn(false);
        when(configServiceClient.isAmountValid(anyString(), any())).thenReturn(true);
        when(accountValidationClient.validateBulk(anyList())).thenReturn(List.of(
                new AccountValidationClient.AccountValidationResponse(
                        "1234567890", "INVALID_BANK", "Test", false, "NOT_FOUND", null),
                new AccountValidationClient.AccountValidationResponse(
                        "0987654321", "INVALID_BANK", "Test", false, "NOT_FOUND", null)));

        TransactionItemProcessor processor = new TransactionItemProcessor(
                configServiceClient, accountValidationClient,
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test-semaphore"),
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test-semaphore-2"),
                io.github.resilience4j.bulkhead.ThreadPoolBulkhead.ofDefaults("test-tp"));

        TransactionRecord record = TransactionRecord.builder()
                .referenceId("TRX-001")
                .sourceAccount("1234567890")
                .sourceBankCode("INVALID_BANK")
                .beneficiaryAccount("0987654321")
                .beneficiaryBankCode("INVALID_BANK")
                .currency("IDR")
                .amount(new BigDecimal("100000"))
                .transactionType("DOMESTIC_TRANSFER")
                .build();

        TransactionRecord result = processor.process(record);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).isNotBlank();
    }

    @Test
    void processor_marksRecord_valid_whenAllValidationsPass() {
        when(configServiceClient.isBankCodeValid(anyString())).thenReturn(true);
        when(configServiceClient.isAmountValid(anyString(), any())).thenReturn(true);
        when(accountValidationClient.validateBulk(anyList())).thenReturn(List.of(
                new AccountValidationClient.AccountValidationResponse(
                        "1234567890", "BCA", "Alice", true, "ACTIVE", null),
                new AccountValidationClient.AccountValidationResponse(
                        "0987654321", "MANDIRI", "Bob", true, "ACTIVE", null)));

        TransactionItemProcessor processor = new TransactionItemProcessor(
                configServiceClient, accountValidationClient,
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("valid-semaphore"),
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("valid-semaphore-2"),
                io.github.resilience4j.bulkhead.ThreadPoolBulkhead.ofDefaults("valid-tp"));

        TransactionRecord record = TransactionRecord.builder()
                .referenceId("TRX-002")
                .sourceAccount("1234567890")
                .sourceBankCode("BCA")
                .beneficiaryAccount("0987654321")
                .beneficiaryBankCode("MANDIRI")
                .currency("IDR")
                .amount(new BigDecimal("500000"))
                .transactionType("DOMESTIC_TRANSFER")
                .build();

        TransactionRecord result = processor.process(record);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getValidationErrors()).isNull();
    }
}
