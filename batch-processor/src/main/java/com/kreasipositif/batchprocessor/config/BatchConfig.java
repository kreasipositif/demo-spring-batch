package com.kreasipositif.batchprocessor.config;

import com.kreasipositif.batchprocessor.batch.RangePartitioner;
import com.kreasipositif.batchprocessor.batch.TransactionFieldSetMapper;
import com.kreasipositif.batchprocessor.batch.TransactionItemProcessor;
import com.kreasipositif.batchprocessor.batch.TransactionItemWriter;
import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Central Spring Batch configuration.
 *
 * <h3>Architecture</h3>
 * <pre>
 *  Job ─► managerStep (partitioned)
 *              │
 *              ├── RangePartitioner  (splits total CSV lines → N execution contexts)
 *              │
 *              └── PartitionHandler  (TaskExecutorPartitionHandler)
 *                      │
 *                      └── workerStep (chunk-oriented, runs on virtual threads)
 *                               │
 *                               ├── FlatFileItemReader   (reads assigned line range)
 *                               ├── TransactionItemProcessor (validates via bulkheads)
 *                               └── TransactionItemWriter    (valid / invalid CSV output)
 * </pre>
 *
 * <h3>Virtual threads</h3>
 * Each partition worker is dispatched on a {@link VirtualThreadTaskExecutor}, so all
 * {@code gridSize} workers run concurrently as lightweight virtual threads.  Blocking calls
 * inside {@link com.kreasipositif.batchprocessor.batch.TransactionItemProcessor} (e.g.
 * {@link Thread#sleep} in the downstream mock) are non-blocking at the OS level.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionFieldSetMapper fieldSetMapper;
    private final TransactionItemProcessor itemProcessor;
    private final ResourceLoader resourceLoader;

    @Value("${batch.chunk-size:100}")
    private int chunkSize;

    @Value("${batch.grid-size:10}")
    private int gridSize;

    @Value("${batch.output-file:${java.io.tmpdir}/batch-output/validation-results.csv}")
    private String outputFilePath;

    // ─── Job ─────────────────────────────────────────────────────────────────

    @Bean
    public Job transactionValidationJob() {
        return new JobBuilder("transactionValidationJob", jobRepository)
                .start(managerStep())
                .build();
    }

    // ─── Async JobLauncher ────────────────────────────────────────────────────

    /**
     * An async {@link JobLauncher} backed by a virtual-thread executor.
     *
     * <p>Using this launcher means {@code jobLauncher.run(...)} returns immediately
     * with {@code BatchStatus.STARTING} instead of blocking until the job finishes.
     * Callers can then poll {@code GET /api/v1/batch/status/{id}} to track progress.
     */
    @Bean("asyncJobLauncher")
    @Primary
    public JobLauncher asyncJobLauncher() throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new VirtualThreadTaskExecutor("job-launcher-"));
        launcher.afterPropertiesSet();
        return launcher;
    }

    // ─── Manager Step (partitioned) ──────────────────────────────────────────

    @Bean
    public Step managerStep() {
        return new StepBuilder("managerStep", jobRepository)
                .partitioner("workerStep", partitioner(null))   // partitioner(null) — resolved at runtime
                .partitionHandler(partitionHandler())
                .build();
    }

    /**
     * Creates a {@link RangePartitioner} sized to the actual CSV line count.
     *
     * <p>The {@code inputFile} job parameter is resolved here; at context-load time it falls back
     * to the {@code batch.input-file} property.  The actual value comes from the REST trigger.
     */
    @Bean
    @StepScope
    public RangePartitioner partitioner(
            @Value("#{jobParameters['inputFile'] ?: '${batch.input-file}'}") String inputFile) {
        try {
            Resource resource = resourceLoader.getResource(inputFile);
            long lineCount;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream()))) {
                // subtract 1 for the header line
                lineCount = reader.lines().collect(Collectors.counting()) - 1;
            }
            log.info("Input file '{}' has {} data lines — using gridSize={}", inputFile, lineCount, gridSize);
            return new RangePartitioner((int) lineCount);
        } catch (Exception e) {
            log.error("Cannot read input file '{}': {}", inputFile, e.getMessage());
            return new RangePartitioner(0);
        }
    }

    // ─── Partition Handler ───────────────────────────────────────────────────

    @Bean
    public PartitionHandler partitionHandler() {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(virtualThreadTaskExecutor());
        handler.setStep(workerStep());
        handler.setGridSize(gridSize);
        return handler;
    }

    /**
     * Virtual-thread executor: each partition worker runs on its own virtual thread.
     * No OS thread is blocked even during the 500 ms downstream latency.
     */
    @Bean
    public TaskExecutor virtualThreadTaskExecutor() {
        return new VirtualThreadTaskExecutor("batch-worker-");
    }

    // ─── Worker Step (chunk-oriented) ────────────────────────────────────────

    @Bean
    public Step workerStep() {
        return new StepBuilder("workerStep", jobRepository)
                .<TransactionRecord, TransactionRecord>chunk(chunkSize, transactionManager)
                .reader(workerItemReader(null, 0, 0))   // placeholders — overridden by @StepScope
                .processor(itemProcessor)
                .writer(workerItemWriter(0))             // step-scoped writer
                .build();
    }

    /**
     * Step-scoped writer: each partition worker gets its own pair of output CSV files
     * (valid + invalid) so there is no file contention between concurrent virtual threads.
     */
    @Bean
    @StepScope
    public TransactionItemWriter workerItemWriter(
            @Value("#{stepExecutionContext['partition'] ?: 0}") int partition) {
        return new TransactionItemWriter(outputFilePath, partition);
    }

    /**
     * Step-scoped reader: each partition worker gets its own reader configured with the
     * line range from the partition {@link org.springframework.batch.item.ExecutionContext}.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<TransactionRecord> workerItemReader(
            @Value("#{jobParameters['inputFile'] ?: '${batch.input-file}'}") String inputFile,
            @Value("#{stepExecutionContext['startLine'] ?: 2}") int startLine,
            @Value("#{stepExecutionContext['endLine'] ?: 101}") int endLine) {

        Resource resource = resourceLoader.getResource(inputFile != null ? inputFile : "");
        int partitionIdx = endLine; // used only for naming

        log.debug("workerItemReader — file={}, startLine={}, endLine={}", inputFile, startLine, endLine);

        return new FlatFileItemReaderBuilder<TransactionRecord>()
                .name("transactionReader-" + startLine + "-" + partitionIdx)
                .resource(resource)
                .linesToSkip(startLine - 1)                        // skip header + preceding lines
                .maxItemCount(endLine - startLine + 1)
                .delimited()
                .delimiter(",")
                .names("referenceId", "sourceAccount", "sourceAccountName", "sourceBankCode",
                        "beneficiaryAccount", "beneficiaryAccountName", "beneficiaryBankCode",
                        "currency", "amount", "transactionType", "note")
                .fieldSetMapper(fieldSetMapper)
                .build();
    }
}
