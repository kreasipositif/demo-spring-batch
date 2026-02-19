package com.kreasipositif.batchprocessor.batch;

import com.kreasipositif.batchprocessor.domain.TransactionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Factory that creates a {@link FlatFileItemReader} for the transaction CSV file.
 *
 * <p>The reader is <em>step-scoped</em> — each partition worker gets its own reader
 * instance configured with the resource and line-range injected from {@link org.springframework.batch.core.StepExecution}
 * parameters by the {@link RangePartitioner}.
 *
 * <p>Usage: call {@link #create(Resource, int, int)} from {@link com.kreasipositif.batchprocessor.config.BatchConfig}
 * for each partition's step.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionItemReaderFactory {

    private final TransactionFieldSetMapper fieldSetMapper;

    @Value("${batch.chunk-size:100}")
    private int chunkSize;

    /**
     * Builds a reader that reads {@code resource} from line {@code startLine} to {@code endLine}
     * (1-based, inclusive; line 1 is the CSV header and is always skipped).
     *
     * @param resource  the CSV {@link Resource} to read
     * @param startLine first data line (1-based; header line = 1, first data line = 2)
     * @param endLine   last data line (1-based, inclusive)
     * @param name      unique name for the reader (used by Spring Batch for restart tracking)
     */
    public FlatFileItemReader<TransactionRecord> create(Resource resource,
                                                        int startLine,
                                                        int endLine,
                                                        String name) {
        log.debug("Creating FlatFileItemReader '{}' — lines {}-{}", name, startLine, endLine);

        return new FlatFileItemReaderBuilder<TransactionRecord>()
                .name(name)
                .resource(resource)
                .linesToSkip(startLine - 1)          // skip header + lines before this partition
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
