package com.kreasipositif.accountvalidation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Response payload for a bulk account validation call.
 */
@Getter
@Builder
@Schema(description = "Results of a bulk account validation request")
public class BulkAccountValidationResponse {

    @Schema(description = "Total number of accounts submitted in the request", example = "5")
    private final int totalRequested;

    @Schema(description = "Number of accounts that passed validation (ACTIVE)", example = "3")
    private final int totalValid;

    @Schema(description = "Number of accounts that failed validation", example = "2")
    private final int totalInvalid;

    @Schema(description = "Individual validation results, one per submitted account")
    private final List<AccountValidationResponse> results;
}
