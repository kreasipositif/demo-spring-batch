package com.kreasipositif.configservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Response payload for a bank-code validation check.
 */
@Getter
@Builder
@Schema(description = "Result of a bank code validation lookup")
public class BankCodeValidationResponse {

    @Schema(description = "The bank code that was checked", example = "BCA")
    private final String code;

    @Schema(description = "Whether the bank code is valid", example = "true")
    private final boolean valid;

    @Schema(description = "Human-readable bank name, present only when valid", example = "Bank Central Asia")
    private final String name;
}
