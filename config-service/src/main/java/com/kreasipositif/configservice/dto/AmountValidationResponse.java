package com.kreasipositif.configservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Response payload for an amount validation check against a transaction type limit.
 */
@Getter
@Builder
@Schema(description = "Result of an amount validation against a transaction type limit")
public class AmountValidationResponse {

    @Schema(description = "Transaction type that was checked", example = "TRANSFER")
    private final String transactionType;

    @Schema(description = "Amount that was validated", example = "500000")
    private final BigDecimal amount;

    @Schema(description = "Whether the amount is within the configured limits", example = "true")
    private final boolean valid;

    @Schema(description = "Reason when validation fails", example = "Amount 500000 is below the minimum of 10000")
    private final String reason;
}
