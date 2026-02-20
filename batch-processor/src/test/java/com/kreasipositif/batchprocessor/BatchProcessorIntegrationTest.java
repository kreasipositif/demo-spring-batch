package com.kreasipositif.batchprocessor;

import com.kreasipositif.batchprocessor.batch.TransactionFieldSetMapper;
import com.kreasipositif.batchprocessor.batch.TransactionItemProcessor;
import com.kreasipositif.batchprocessor.client.AccountValidationClient;
import com.kreasipositif.batchprocessor.client.AccountValidationClient.AccountValidationRequest;
import com.kreasipositif.batchprocessor.client.AccountValidationClient.AccountValidationResponse;
import com.kreasipositif.batchprocessor.client.ConfigServiceClient;
import com.kreasipositif.batchprocessor.config.BatchConfig;
import com.kreasipositif.batchprocessor.config.Resilience4jConfig;
import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Spring Batch integration tests.
 *
 * <h3>Test fixture: {@code test-transactions.csv} (13 rows)</h3>
 * <p>Rows are crafted against the real seed data in config-service and
 * account-validation-service so that every failure reason is exercised:
 * <ul>
 *   <li>TRX-T001 … TRX-T005 — 5 fully valid records</li>
 *   <li>TRX-T006 — beneficiary account INACTIVE  (Rudi Hermawan / CIMB)</li>
 *   <li>TRX-T007 — source account INACTIVE  (Lina Marlina / BNI)</li>
 *   <li>TRX-T008 — beneficiary account BLOCKED  (Hendra Gunawan / PERMATA)</li>
 *   <li>TRX-T009 — source bank code unknown (XENDIT)</li>
 *   <li>TRX-T010 — beneficiary bank code unknown (GOPAY)</li>
 *   <li>TRX-T011 — amount below TRANSFER minimum (5 000 &lt; 10 000)</li>
 *   <li>TRX-T012 — source account not found (9999999999)</li>
 *   <li>TRX-T013 — beneficiary account not found (8888888888)</li>
 * </ul>
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
        "batch.chunk-size=3",
        "batch.grid-size=2",
        "batch.output-file=${java.io.tmpdir}/batch-test-output/results.csv",
        "downstream.config-service.base-url=http://localhost:8081",
        "downstream.account-validation-service.base-url=http://localhost:8082"
})
class BatchProcessorIntegrationTest {

    // ── Valid bank codes as per config-service seed data ─────────────────────
    private static final Set<String> VALID_BANK_CODES = Set.of(
            "BCA", "BNI", "BRI", "MANDIRI", "CIMB",
            "DANAMON", "PERMATA", "BTN", "BSI", "OCBC");

    // ── Seeded accounts from account-validation-service ──────────────────────
    // key: accountNumber → AccountValidationResponse
    private static final Map<String, AccountValidationResponse> SEEDED_ACCOUNTS = Map.ofEntries(
            entry("1234567890", "Budi Santoso",   "BCA",     "ACTIVE"),
            entry("0987654321", "Siti Rahayu",    "BNI",     "ACTIVE"),
            entry("1122334455", "Ahmad Fauzi",    "BRI",     "ACTIVE"),
            entry("5544332211", "Dewi Lestari",   "MANDIRI", "ACTIVE"),
            entry("6677889900", "Rudi Hermawan",  "CIMB",    "INACTIVE"),
            entry("9900112233", "Rina Kusuma",    "DANAMON", "ACTIVE"),
            entry("3344556677", "Hendra Gunawan", "PERMATA", "BLOCKED"),
            entry("7788990011", "Yuni Astuti",    "BTN",     "ACTIVE"),
            entry("2233445566", "Fajar Nugroho",  "BSI",     "ACTIVE"),
            entry("4455667788", "Indah Permata",  "OCBC",    "ACTIVE"),
            entry("1357924680", "Wahyu Prasetyo", "BCA",     "ACTIVE"),
            entry("2468013579", "Maya Sari",      "BRI",     "ACTIVE"),
            entry("1111222233", "Doni Kurniawan", "MANDIRI", "ACTIVE"),
            entry("4444555566", "Lina Marlina",   "BNI",     "INACTIVE"),
            entry("7777888899", "Agus Salim",     "BSI",     "ACTIVE")
    );

    /**
     * Override the primary (async) JobLauncher with a synchronous one for tests.
     * This ensures JobLauncherTestUtils.launchJob() blocks until the job is done
     * and we can assert BatchStatus.COMPLETED inline.
     */
    @TestConfiguration
    static class SyncJobLauncherConfig {
        @Bean
        @Primary
        public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
            TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
            launcher.setJobRepository(jobRepository);
            launcher.setTaskExecutor(new SyncTaskExecutor());
            launcher.afterPropertiesSet();
            return launcher;
        }

        /**
         * Override the partition handler to run all partitions synchronously in tests.
         */
        @Bean
        @Primary
        public PartitionHandler partitionHandler(
                org.springframework.batch.core.Step workerStep,
                @org.springframework.beans.factory.annotation.Value("${batch.grid-size:2}") int gridSize) {
            TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
            handler.setTaskExecutor(new SyncTaskExecutor());
            handler.setStep(workerStep);
            handler.setGridSize(gridSize);
            return handler;
        }
    }

    @Autowired private JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired private Job transactionValidationJob;
    @Autowired private JobExplorer jobExplorer;

    @MockitoBean private ConfigServiceClient configServiceClient;
    @MockitoBean private AccountValidationClient accountValidationClient;

    @BeforeEach
    void setUpMocks() {
        // ── config-service: bank-code validation ──────────────────────────────
        when(configServiceClient.isBankCodeValid(anyString()))
                .thenAnswer(inv -> VALID_BANK_CODES.contains((String) inv.getArgument(0)));

        // ── config-service: amount validation ─────────────────────────────────
        when(configServiceClient.isAmountValid(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> {
                    String type    = inv.getArgument(0);
                    BigDecimal amt = inv.getArgument(1);
                    long min = switch (type) {
                        case "TRANSFER"   -> 10_000L;
                        case "PAYMENT"    ->  1_000L;
                        case "TOPUP"      -> 10_000L;
                        case "WITHDRAWAL" -> 50_000L;
                        default           ->  1_000L;
                    };
                    return amt.compareTo(BigDecimal.valueOf(min)) >= 0;
                });

        // ── account-validation-service: bulk validation ───────────────────────
        when(accountValidationClient.validateBulk(anyList()))
                .thenAnswer(inv -> {
                    List<AccountValidationRequest> reqs = inv.getArgument(0);
                    return reqs.stream().map(r -> {
                        AccountValidationResponse seeded = SEEDED_ACCOUNTS.get(r.accountNumber());
                        if (seeded == null) {
                            return new AccountValidationResponse(
                                    r.accountNumber(), r.bankCode(), null,
                                    false, "NOT_FOUND", "Account not found");
                        }
                        boolean valid = "ACTIVE".equals(seeded.status());
                        String reason = switch (seeded.status()) {
                            case "INACTIVE" -> "Account is inactive";
                            case "BLOCKED"  -> "Account is blocked";
                            default         -> null;
                        };
                        return new AccountValidationResponse(
                                r.accountNumber(), seeded.bankCode(), seeded.accountName(),
                                valid, seeded.status(), reason);
                    }).toList();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration test: full job
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Job COMPLETED — processes all 13 rows (5 valid, 8 invalid) without crashing")
    void job_completesSuccessfully_withMixedValidAndInvalidRecords() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("inputFile", "classpath:data/test-transactions.csv")
                .addLong("startedAt", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // The async launcher returns immediately with STARTING; poll until the job finishes.
        long deadline = System.currentTimeMillis() + 30_000;
        while (execution.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            execution = jobExplorer.getJobExecution(execution.getJobId());
        }

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit-style processor tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VALID — Budi Santoso (BCA) → Siti Rahayu (BNI), TRANSFER 500 000 IDR")
    void processor_valid_activeSourceAndBeneficiary() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T001", "1234567890", "Budi Santoso", "BCA",
                "0987654321", "Siti Rahayu", "BNI",
                "IDR", "500000", "TRANSFER"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getValidationErrors()).isNull();
    }

    @Test
    @DisplayName("INVALID — beneficiary account INACTIVE: Rudi Hermawan (CIMB / 6677889900)")
    void processor_invalid_beneficiaryAccountInactive() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T006", "1234567890", "Budi Santoso", "BCA",
                "6677889900", "Rudi Hermawan", "CIMB",
                "IDR", "200000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("6677889900");
    }

    @Test
    @DisplayName("INVALID — source account INACTIVE: Lina Marlina (BNI / 4444555566)")
    void processor_invalid_sourceAccountInactive() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T007", "4444555566", "Lina Marlina", "BNI",
                "0987654321", "Siti Rahayu", "BNI",
                "IDR", "500000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("4444555566");
    }

    @Test
    @DisplayName("INVALID — beneficiary account BLOCKED: Hendra Gunawan (PERMATA / 3344556677)")
    void processor_invalid_beneficiaryAccountBlocked() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T008", "1234567890", "Budi Santoso", "BCA",
                "3344556677", "Hendra Gunawan", "PERMATA",
                "IDR", "300000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("3344556677");
    }

    @Test
    @DisplayName("INVALID — source bank code not recognised: XENDIT")
    void processor_invalid_unknownSourceBankCode() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T009", "1234567890", "Budi Santoso", "XENDIT",
                "0987654321", "Siti Rahayu", "BNI",
                "IDR", "500000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("XENDIT");
    }

    @Test
    @DisplayName("INVALID — beneficiary bank code not recognised: GOPAY")
    void processor_invalid_unknownBeneficiaryBankCode() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T010", "1234567890", "Budi Santoso", "BCA",
                "0987654321", "Siti Rahayu", "GOPAY",
                "IDR", "500000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("GOPAY");
    }

    @Test
    @DisplayName("INVALID — amount 5 000 below TRANSFER minimum of 10 000")
    void processor_invalid_amountBelowMinimum() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T011", "1234567890", "Budi Santoso", "BCA",
                "0987654321", "Siti Rahayu", "BNI",
                "IDR", "5000", "TRANSFER"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("5000");
    }

    @Test
    @DisplayName("INVALID — source account not found: 9999999999")
    void processor_invalid_sourceAccountNotFound() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T012", "9999999999", "Unknown Person", "BRI",
                "1122334455", "Ahmad Fauzi", "BRI",
                "IDR", "100000", "PAYMENT"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("9999999999");
    }

    @Test
    @DisplayName("INVALID — beneficiary account not found: 8888888888")
    void processor_invalid_beneficiaryAccountNotFound() {
        TransactionRecord result = buildProcessor().process(record(
                "TRX-T013", "1357924680", "Wahyu Prasetyo", "BCA",
                "8888888888", "Ghost Account", "MANDIRI",
                "IDR", "50000", "PAYMENT"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getValidationErrors()).containsIgnoringCase("8888888888");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionItemProcessor buildProcessor() {
        return new TransactionItemProcessor(
                configServiceClient,
                accountValidationClient,
                Bulkhead.ofDefaults("test-semaphore-src"),
                Bulkhead.ofDefaults("test-semaphore-acct"),
                ThreadPoolBulkhead.ofDefaults("test-tp"));
    }

    private TransactionRecord record(String refId,
                                     String srcAcct, String srcName, String srcBank,
                                     String beneAcct, String beneName, String beneBank,
                                     String currency, String amount, String txType) {
        return TransactionRecord.builder()
                .referenceId(refId)
                .sourceAccount(srcAcct)
                .sourceAccountName(srcName)
                .sourceBankCode(srcBank)
                .beneficiaryAccount(beneAcct)
                .beneficiaryAccountName(beneName)
                .beneficiaryBankCode(beneBank)
                .currency(currency)
                .amount(new BigDecimal(amount))
                .transactionType(txType)
                .build();
    }

    /** Builds a map entry for SEEDED_ACCOUNTS. */
    private static Map.Entry<String, AccountValidationResponse> entry(
            String accountNumber, String accountName, String bankCode, String status) {
        return Map.entry(accountNumber,
                new AccountValidationResponse(accountNumber, bankCode, accountName,
                        "ACTIVE".equals(status), status, null));
    }
}
