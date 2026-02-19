package com.kreasipositif.configservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Response payload for a single bank code entry.
 */
@Getter
@Builder
@Schema(description = "Represents a valid bank with its code and name")
public class BankCodeResponse {

    @Schema(description = "Short bank code used in CSV", example = "BCA")
    private final String code;

    @Schema(description = "Full bank name", example = "Bank Central Asia")
    private final String name;
}
