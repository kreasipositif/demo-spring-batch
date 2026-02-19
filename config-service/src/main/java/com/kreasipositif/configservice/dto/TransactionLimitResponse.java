package com.kreasipositif.configservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Response payload for a transaction limit entry.
 */
@Getter
@Builder
@Schema(description = "Transaction limit configuration for a specific transaction type")
public class TransactionLimitResponse {

    @Schema(description = "Transaction type key", example = "TRANSFER")
    private final String transactionType;

    @Schema(description = "Minimum allowed amount (inclusive)", example = "10000")
    private final BigDecimal minAmount;

    @Schema(description = "Maximum allowed amount (inclusive)", example = "1000000000")
    private final BigDecimal maxAmount;

    @Schema(description = "Currency code", example = "IDR")
    private final String currency;
}
