package com.kreasipositif.configservice.controller;

import com.kreasipositif.configservice.dto.BankCodeResponse;
import com.kreasipositif.configservice.dto.BankCodeValidationResponse;
import com.kreasipositif.configservice.service.BankConfigService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing bank code configuration endpoints.
 */
@RestController
@RequestMapping("/api/v1/config/bank-codes")
@RequiredArgsConstructor
@Tag(name = "Bank Code Config", description = "Endpoints for retrieving and validating configured bank codes")
public class BankCodeController {

    private final BankConfigService bankConfigService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all valid bank codes",
            description = "Returns the full list of bank codes that are configured as valid in the system."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved the list of bank codes",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = BankCodeResponse.class))
                    )
            )
    })
    public ResponseEntity<List<BankCodeResponse>> getAllBankCodes() {
        return ResponseEntity.ok(bankConfigService.getAllBankCodes());
    }

    @GetMapping(value = "/{code}/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Validate a bank code",
            description = "Checks whether the given bank code exists in the configured list. The check is case-insensitive."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Validation result returned (valid or invalid)",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BankCodeValidationResponse.class)
                    )
            )
    })
    public ResponseEntity<BankCodeValidationResponse> validateBankCode(
            @Parameter(name = "code", description = "Bank code to validate (case-insensitive)", example = "BCA", required = true)
            @PathVariable("code") String code) {
        return ResponseEntity.ok(bankConfigService.validateBankCode(code));
    }
}
