package com.kreasipositif.configservice.controller;

import com.kreasipositif.configservice.dto.AmountValidationResponse;
import com.kreasipositif.configservice.dto.TransactionLimitResponse;
import com.kreasipositif.configservice.service.TransactionConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST controller exposing transaction limit configuration endpoints.
 */
@RestController
@RequestMapping("/api/v1/config/transaction-limits")
@RequiredArgsConstructor
@Tag(name = "Transaction Limit Config", description = "Endpoints for retrieving and validating transaction amount limits per type")
public class TransactionLimitController {

    private final TransactionConfigService transactionConfigService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all transaction type limits",
            description = "Returns the full list of configured transaction types and their minimum/maximum amount limits."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved all transaction limits",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = TransactionLimitResponse.class))
                    )
            )
    })
    public ResponseEntity<List<TransactionLimitResponse>> getAllLimits() {
        return ResponseEntity.ok(transactionConfigService.getAllLimits());
    }

    @GetMapping(value = "/{transactionType}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get limit for a specific transaction type",
            description = "Returns the minimum and maximum amount limits for the given transaction type (case-insensitive)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Limit configuration found for the given transaction type",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionLimitResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transaction type is not configured",
                    content = @Content
            )
    })
    public ResponseEntity<TransactionLimitResponse> getLimitByType(
            @Parameter(name = "transactionType", description = "Transaction type key (case-insensitive)", example = "TRANSFER", required = true)
            @PathVariable("transactionType") String transactionType) {
        return transactionConfigService.getLimitByType(transactionType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{transactionType}/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Validate an amount for a transaction type",
            description = "Checks whether the given amount falls within the configured min/max limits for the specified transaction type."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Validation result returned (valid or invalid with reason)",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AmountValidationResponse.class)
                    )
            )
    })
    public ResponseEntity<AmountValidationResponse> validateAmount(
            @Parameter(name = "transactionType", description = "Transaction type key (case-insensitive)", example = "TRANSFER", required = true)
            @PathVariable("transactionType") String transactionType,
            @Parameter(name = "amount", description = "Amount to validate against the configured limits", example = "500000", required = true)
            @RequestParam("amount") BigDecimal amount) {
        return ResponseEntity.ok(transactionConfigService.validateAmount(transactionType, amount));
    }
}
