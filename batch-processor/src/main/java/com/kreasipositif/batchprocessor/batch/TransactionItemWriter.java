package com.kreasipositif.batchprocessor.batch;

import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.BeanWrapperFieldExtractor;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes processed {@link TransactionRecord} items to two CSV output files:
 * <ul>
 *   <li><b>valid-{partition}-{timestamp}.csv</b>   — records that passed all validations</li>
 *   <li><b>invalid-{partition}-{timestamp}.csv</b> — records that failed one or more validations</li>
 * </ul>
 *
 * <p>This class is <b>not</b> annotated with {@code @Component} — it is created as a
 * {@code @StepScope} bean in {@link com.kreasipositif.batchprocessor.config.BatchConfig}
 * so that each partition worker gets its own independent writer instance and output files.
 */
@Slf4j
public class TransactionItemWriter implements ItemStreamWriter<TransactionRecord> {

    private static final String[] VALID_FIELDS = {
            "referenceId", "sourceAccount", "sourceAccountName", "sourceBankCode",
            "beneficiaryAccount", "beneficiaryAccountName", "beneficiaryBankCode",
            "currency", "amount", "transactionType", "note"
    };

    private static final String[] INVALID_FIELDS = {
            "referenceId", "sourceAccount", "sourceBankCode",
            "beneficiaryAccount", "beneficiaryBankCode",
            "currency", "amount", "transactionType",
            "validationErrors"
    };

    private final String outputFilePath;
    private final int partitionIndex;

    public TransactionItemWriter(String outputFilePath, int partitionIndex) {
        this.outputFilePath = outputFilePath;
        this.partitionIndex = partitionIndex;
    }

    private FlatFileItemWriter<TransactionRecord> validWriter;
    private FlatFileItemWriter<TransactionRecord> invalidWriter;

    private final AtomicLong validCount = new AtomicLong();
    private final AtomicLong invalidCount = new AtomicLong();

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        File outputDir = new File(outputFilePath).getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        long ts = System.currentTimeMillis();
        String base = outputDir != null ? outputDir.getAbsolutePath() : System.getProperty("java.io.tmpdir");
        String suffix = "p" + partitionIndex + "-" + ts;

        validWriter = buildWriter(base + "/valid-" + suffix + ".csv", VALID_FIELDS, "validWriter-" + suffix);
        invalidWriter = buildWriter(base + "/invalid-" + suffix + ".csv", INVALID_FIELDS, "invalidWriter-" + suffix);

        validWriter.open(executionContext);
        invalidWriter.open(executionContext);

        log.info("Partition {} output — valid: valid-{}.csv  |  invalid: invalid-{}.csv", partitionIndex, suffix, suffix);
    }

    @Override
    public void write(Chunk<? extends TransactionRecord> chunk) throws Exception {
        List<TransactionRecord> validItems = chunk.getItems().stream()
                .filter(TransactionRecord::isValid)
                .map(r -> (TransactionRecord) r).toList();
        List<TransactionRecord> invalidItems = chunk.getItems().stream()
                .filter(r -> !r.isValid())
                .map(r -> (TransactionRecord) r).toList();

        if (!validItems.isEmpty()) {
            validWriter.write(new Chunk<>(validItems));
            validCount.addAndGet(validItems.size());
        }
        if (!invalidItems.isEmpty()) {
            invalidWriter.write(new Chunk<>(invalidItems));
            invalidCount.addAndGet(invalidItems.size());
        }
    }

    @Override
    public void close() throws ItemStreamException {
        if (validWriter != null) validWriter.close();
        if (invalidWriter != null) invalidWriter.close();
        log.info("Job complete — {} valid records, {} invalid records", validCount.get(), invalidCount.get());
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        if (validWriter != null) validWriter.update(executionContext);
        if (invalidWriter != null) invalidWriter.update(executionContext);
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private FlatFileItemWriter<TransactionRecord> buildWriter(String path, String[] fields, String name) {
        BeanWrapperFieldExtractor<TransactionRecord> extractor = new BeanWrapperFieldExtractor<>();
        extractor.setNames(fields);

        DelimitedLineAggregator<TransactionRecord> aggregator = new DelimitedLineAggregator<>();
        aggregator.setDelimiter(",");
        aggregator.setFieldExtractor(extractor);

        return new FlatFileItemWriterBuilder<TransactionRecord>()
                .name(name)
                .resource(new FileSystemResource(path))
                .lineAggregator(aggregator)
                .headerCallback(writer -> writer.write(String.join(",", fields)))
                .append(false)
                .build();
    }
}
