package com.kreasipositif.accountvalidation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request payload for validating multiple accounts in a single call.
 * Useful for batch-processor pre-validation of CSV chunks.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to validate multiple account numbers in one call")
public class BulkAccountValidationRequest {

    @Valid
    @NotEmpty(message = "accounts list must not be empty")
    @Size(max = 100, message = "Maximum 100 accounts per bulk request")
    @Schema(description = "List of account validation requests (max 100 per call)")
    private List<AccountValidationRequest> accounts;
}
