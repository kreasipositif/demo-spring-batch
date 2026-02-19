package com.kreasipositif.accountvalidation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request payload for validating a single account number against a specific bank.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to validate a single account number")
public class AccountValidationRequest {

    @NotBlank(message = "accountNumber must not be blank")
    @Schema(description = "Account number to validate", example = "1234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private String accountNumber;

    @NotBlank(message = "bankCode must not be blank")
    @Schema(description = "Bank code the account belongs to (must match configured bank codes)", example = "BCA", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bankCode;
}
