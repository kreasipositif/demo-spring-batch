package com.kreasipositif.batchprocessor.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for triggering and monitoring Spring Batch jobs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/batch")
@Tag(name = "Batch Jobs", description = "Trigger and monitor transaction validation batch jobs")
public class JobController {

    private final JobLauncher asyncJobLauncher;
    private final Job transactionValidationJob;
    private final JobExplorer jobExplorer;

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private String defaultInputFile;

    public JobController(@Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher,
                         Job transactionValidationJob,
                         JobExplorer jobExplorer) {
        this.asyncJobLauncher = asyncJobLauncher;
        this.transactionValidationJob = transactionValidationJob;
        this.jobExplorer = jobExplorer;
    }

    // ─── POST /api/v1/batch/start ─────────────────────────────────────────────

    @PostMapping("/start")
    @Operation(
            summary = "Start a transaction validation job",
            description = "Launches the Spring Batch partitioned CSV processing job **asynchronously**. "
                    + "The HTTP response is returned immediately with `STARTING` status and a `jobExecutionId`. "
                    + "Poll `GET /api/v1/batch/status/{jobExecutionId}` to track progress. "
                    + "Each partition runs on a virtual thread. "
                    + "Pass `inputFile` to override the default CSV path.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Job accepted and started in the background",
                            content = @Content(schema = @Schema(implementation = JobStartResponse.class))),
                    @ApiResponse(responseCode = "500", description = "Failed to launch job",
                            content = @Content(schema = @Schema(implementation = Map.class)))
            })
    public ResponseEntity<?> startJob(
            @Parameter(description = "Absolute path or Spring resource path to the input CSV file. "
                    + "Leave blank to use the default configured in application.yml.",
                    example = "classpath:data/transactions.csv")
            @RequestParam(value = "inputFile", required = false) String inputFile) {

        String resolvedFile = (inputFile != null && !inputFile.isBlank()) ? inputFile : defaultInputFile;
        log.info("Starting transactionValidationJob with inputFile='{}'", resolvedFile);

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("inputFile", resolvedFile)
                    .addLong("startedAt", Instant.now().toEpochMilli())   // ensures unique run
                    .toJobParameters();

            // asyncJobLauncher returns immediately — the job runs in the background
            JobExecution execution = asyncJobLauncher.run(transactionValidationJob, params);

            return ResponseEntity.accepted().body(new JobStartResponse(
                    execution.getJobId(),
                    execution.getStatus().name(),
                    resolvedFile,
                    execution.getStartTime() != null ? execution.getStartTime().toString() : null));

        } catch (Exception e) {
            log.error("Failed to start job: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start job: " + e.getMessage()));
        }
    }

    // ─── GET /api/v1/batch/status/{jobExecutionId} ───────────────────────────

    @GetMapping("/status/{jobExecutionId}")
    @Operation(
            summary = "Get job execution status",
            description = "Returns the current status of a Spring Batch job execution by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Job execution found",
                            content = @Content(schema = @Schema(implementation = JobStatusResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Job execution not found")
            })
    public ResponseEntity<?> getStatus(
            @Parameter(name = "jobExecutionId", description = "The job execution ID returned by /start", required = true)
            @PathVariable("jobExecutionId") Long jobExecutionId) {

        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            String jobName = execution.getJobInstance() != null
                    ? execution.getJobInstance().getJobName()
                    : "transactionValidationJob";
            String exitCode = execution.getExitStatus() != null
                    ? execution.getExitStatus().getExitCode() : null;
            LocalDateTime startTime = execution.getStartTime();
            LocalDateTime endTime   = execution.getEndTime();

            // Elapsed time
            String elapsedSeconds = null;
            if (startTime != null) {
                LocalDateTime until = (endTime != null) ? endTime : LocalDateTime.now();
                elapsedSeconds = String.valueOf(Duration.between(startTime, until).toSeconds()) + "s";
            }

            // Separate manager step from worker (partition) steps
            List<StepExecution> workerSteps = execution.getStepExecutions().stream()
                    .filter(se -> se.getStepName().contains("partition"))
                    .collect(Collectors.toList());

            // Aggregate totals across all partitions
            long totalRead    = workerSteps.stream().mapToLong(StepExecution::getReadCount).sum();
            long totalWrite   = workerSteps.stream().mapToLong(StepExecution::getWriteCount).sum();
            long totalSkip    = workerSteps.stream().mapToLong(StepExecution::getSkipCount).sum();
            long totalFilter  = workerSteps.stream().mapToLong(StepExecution::getFilterCount).sum();

            // Partition progress counts
            long partitionsCompleted = workerSteps.stream()
                    .filter(se -> "COMPLETED".equals(se.getStatus().name())).count();
            long partitionsRunning = workerSteps.stream()
                    .filter(se -> "STARTED".equals(se.getStatus().name())).count();
            long partitionsFailed = workerSteps.stream()
                    .filter(se -> "FAILED".equals(se.getStatus().name())).count();
            long totalPartitions = workerSteps.size();

            // Per-partition detail (sorted by name)
            List<PartitionDetail> partitions = workerSteps.stream()
                    .sorted(java.util.Comparator.comparing(StepExecution::getStepName))
                    .map(se -> new PartitionDetail(
                            se.getStepName(),
                            se.getStatus().name(),
                            se.getReadCount(),
                            se.getWriteCount(),
                            se.getSkipCount(),
                            se.getFilterCount(),
                            se.getStartTime() != null ? se.getStartTime().toString() : null,
                            se.getEndTime()   != null ? se.getEndTime().toString()   : null))
                    .collect(Collectors.toList());

            Progress progress = new Progress(
                    totalPartitions, partitionsCompleted, partitionsRunning, partitionsFailed,
                    totalRead, totalWrite, totalSkip, totalFilter);

            return ResponseEntity.ok(new JobStatusResponse(
                    jobExecutionId,
                    jobName,
                    execution.getStatus().name(),
                    exitCode,
                    startTime != null ? startTime.toString() : null,
                    endTime   != null ? endTime.toString()   : null,
                    elapsedSeconds,
                    progress,
                    partitions));

        } catch (Exception e) {
            log.error("Error building status response for jobExecutionId={}: {}", jobExecutionId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve job status: " + e.getMessage()));
        }
    }

    // ─── Response records ─────────────────────────────────────────────────────

    public record JobStartResponse(Long jobExecutionId, String status, String inputFile, String startTime) {}

    /**
     * @param progress   Aggregated counts across all partition workers
     * @param partitions Per-partition breakdown, sorted by name
     */
    public record JobStatusResponse(
            Long jobExecutionId,
            String jobName,
            String status,
            String exitCode,
            String startTime,
            String endTime,
            String elapsed,
            Progress progress,
            List<PartitionDetail> partitions) {}

    /**
     * Aggregated progress across all partition workers.
     *
     * @param totalPartitions     Number of partitions the CSV was split into
     * @param partitionsCompleted Partitions that have finished successfully
     * @param partitionsRunning   Partitions currently processing
     * @param partitionsFailed    Partitions that failed
     * @param totalRecordsRead    Records read so far across all partitions
     * @param totalRecordsWritten Records written (valid + invalid) so far
     * @param totalSkipped        Records skipped due to errors
     * @param totalFiltered       Records filtered out by the processor
     */
    public record Progress(
            long totalPartitions,
            long partitionsCompleted,
            long partitionsRunning,
            long partitionsFailed,
            long totalRecordsRead,
            long totalRecordsWritten,
            long totalSkipped,
            long totalFiltered) {}

    /**
     * Per-partition step detail.
     */
    public record PartitionDetail(
            String partition,
            String status,
            long readCount,
            long writeCount,
            long skipCount,
            long filterCount,
            String startTime,
            String endTime) {}
}
