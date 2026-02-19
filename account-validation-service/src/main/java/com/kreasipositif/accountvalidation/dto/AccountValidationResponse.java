package com.kreasipositif.accountvalidation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Response payload for a single account validation result.
 */
@Getter
@Builder
@Schema(description = "Result of a single account number validation")
public class AccountValidationResponse {

    @Schema(description = "The account number that was validated", example = "1234567890")
    private final String accountNumber;

    @Schema(description = "The bank code associated with the account", example = "BCA")
    private final String bankCode;

    @Schema(description = "Account holder name, present only when the account is found", example = "Budi Santoso")
    private final String accountName;

    @Schema(description = "Whether the account exists and is ACTIVE", example = "true")
    private final boolean valid;

    @Schema(
            description = "Account lifecycle status: ACTIVE | INACTIVE | BLOCKED | NOT_FOUND",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "INACTIVE", "BLOCKED", "NOT_FOUND"}
    )
    private final String status;

    @Schema(description = "Human-readable reason when the account is not valid", example = "Account not found")
    private final String reason;
}
