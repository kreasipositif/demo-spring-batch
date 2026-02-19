package com.kreasipositif.batchprocessor.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Splits the CSV file into {@code gridSize} equal-sized line ranges.
 *
 * <p>Each partition is represented by an {@link ExecutionContext} with two keys:
 * <ul>
 *   <li>{@code startLine} — first data line for this partition (1-based, header = line 1 is excluded)</li>
 *   <li>{@code endLine}   — last data line (inclusive)</li>
 * </ul>
 *
 * <p>The total number of data lines ({@code totalLines}) is the CSV record count excluding the header.
 */
@Slf4j
public class RangePartitioner implements Partitioner {

    private final int totalLines;

    public RangePartitioner(int totalLines) {
        this.totalLines = totalLines;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int linesPerPartition = (int) Math.ceil((double) totalLines / gridSize);
        Map<String, ExecutionContext> partitions = new HashMap<>();

        for (int i = 0; i < gridSize; i++) {
            // +1 offset because line 1 is the CSV header; data starts at line 2
            int startLine = i * linesPerPartition + 2;
            int endLine = Math.min(startLine + linesPerPartition - 1, totalLines + 1);

            if (startLine > totalLines + 1) break; // no more data

            ExecutionContext ctx = new ExecutionContext();
            ctx.putInt("startLine", startLine);
            ctx.putInt("endLine", endLine);
            ctx.putInt("partition", i);

            String partitionName = "partition-" + i;
            partitions.put(partitionName, ctx);

            log.debug("Partition '{}' — lines {}-{}", partitionName, startLine, endLine);
        }

        log.info("Created {} partitions for {} data lines (gridSize requested: {})",
                partitions.size(), totalLines, gridSize);
        return partitions;
    }
}
