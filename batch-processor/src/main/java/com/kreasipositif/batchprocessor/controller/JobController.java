package com.kreasipositif.batchprocessor.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * REST API for triggering and monitoring Spring Batch jobs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/batch")
@RequiredArgsConstructor
@Tag(name = "Batch Jobs", description = "Trigger and monitor transaction validation batch jobs")
public class JobController {

    private final JobLauncher jobLauncher;
    private final Job transactionValidationJob;
    private final JobExplorer jobExplorer;

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private String defaultInputFile;

    // ─── POST /api/v1/batch/start ─────────────────────────────────────────────

    @PostMapping("/start")
    @Operation(
            summary = "Start a transaction validation job",
            description = "Launches the Spring Batch partitioned CSV processing job. "
                    + "Each partition runs on a virtual thread. "
                    + "Pass `inputFile` to override the default CSV path.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Job started",
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

            JobExecution execution = jobLauncher.run(transactionValidationJob, params);

            return ResponseEntity.ok(new JobStartResponse(
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

        return ResponseEntity.ok(new JobStatusResponse(
                execution.getJobId(),
                execution.getJobInstance().getJobName(),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                execution.getStartTime() != null ? execution.getStartTime().toString() : null,
                execution.getEndTime() != null ? execution.getEndTime().toString() : null,
                execution.getStepExecutions().stream()
                        .map(se -> new StepSummary(
                                se.getStepName(),
                                se.getStatus().name(),
                                se.getReadCount(),
                                se.getWriteCount(),
                                se.getSkipCount(),
                                se.getFilterCount()))
                        .collect(java.util.stream.Collectors.toList())));
    }

    // ─── Response records ─────────────────────────────────────────────────────

    public record JobStartResponse(Long jobExecutionId, String status, String inputFile, String startTime) {}

    public record JobStatusResponse(Long jobExecutionId, String jobName, String status,
                                    String exitCode, String startTime, String endTime,
                                    java.util.List<StepSummary> steps) {}

    public record StepSummary(String stepName, String status,
                              long readCount, long writeCount, long skipCount, long filterCount) {}
}
