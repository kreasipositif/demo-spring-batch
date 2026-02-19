package com.kreasipositif.accountvalidation.controller;

import com.kreasipositif.accountvalidation.dto.AccountValidationRequest;
import com.kreasipositif.accountvalidation.dto.AccountValidationResponse;
import com.kreasipositif.accountvalidation.dto.BulkAccountValidationRequest;
import com.kreasipositif.accountvalidation.dto.BulkAccountValidationResponse;
import com.kreasipositif.accountvalidation.service.AccountValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing account validation endpoints.
 *
 * <p>All endpoints simulate a downstream bank core system and apply a configurable
 * latency (default 500 ms) per request to mimic real-world API response times.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(
        name = "Account Validation",
        description = """
                Validates whether source or beneficiary account numbers exist and are active.
                Each request incurs a simulated latency (default 500 ms) per call.
                """
)
public class AccountValidationController {

    private final AccountValidationService accountValidationService;

    @PostMapping(
            value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Validate a single account",
            description = """
                    Checks whether the given account number exists in the mock bank core system
                    and that its status is **ACTIVE**.
                    
                    A simulated latency of **500 ms** is applied before returning the result.
                    
                    Possible status values in the response:
                    - `ACTIVE` — account is valid and eligible for transactions
                    - `INACTIVE` — account exists but is suspended
                    - `BLOCKED` — account exists but is permanently blocked
                    - `NOT_FOUND` — account number does not exist for the given bank code
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Validation result returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AccountValidationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request — accountNumber or bankCode is missing",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
            )
    })
    public ResponseEntity<AccountValidationResponse> validateAccount(
            @Valid @RequestBody AccountValidationRequest request) {
        return ResponseEntity.ok(accountValidationService.validateAccount(request));
    }

    @PostMapping(
            value = "/validate/bulk",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Validate multiple accounts in bulk",
            description = """
                    Validates up to **100** account numbers in a single request.
                    
                    A single simulated latency of **500 ms** is applied once for the entire
                    bulk call (one downstream round-trip), not per item. The response includes
                    a summary of valid/invalid counts and individual results for each account.
                    
                    This endpoint is designed to be called by the **batch-processor** chunk writer.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Bulk validation results returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BulkAccountValidationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request — empty list or more than 100 accounts",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
            )
    })
    public ResponseEntity<BulkAccountValidationResponse> validateBulk(
            @Valid @RequestBody BulkAccountValidationRequest request) {
        return ResponseEntity.ok(accountValidationService.validateBulk(request));
    }
}
