package com.kreasipositif.batchprocessor.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * REST client for the config-service (port 8081).
 *
 * <p>Provides two operations used during transaction validation:
 * <ul>
 *   <li>Validate a bank code against the known list.</li>
 *   <li>Validate that a given amount meets the minimum for a transaction type.</li>
 * </ul>
 */
@Slf4j
@Component
public class ConfigServiceClient {

    private final RestClient restClient;

    public ConfigServiceClient(
            RestClient.Builder builder,
            @Value("${downstream.config-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    // ─── Bank-code validation ─────────────────────────────────────────────────

    /**
     * Calls {@code GET /api/v1/config/bank-codes/{code}/validate}.
     *
     * @return {@code true} if config-service considers the bank code valid
     */
    public boolean isBankCodeValid(String bankCode) {
        try {
            BankCodeValidationResponse response = restClient.get()
                    .uri("/api/v1/config/bank-codes/{code}/validate", bankCode)
                    .retrieve()
                    .body(BankCodeValidationResponse.class);
            return response != null && Boolean.TRUE.equals(response.valid());
        } catch (RestClientException e) {
            log.warn("Bank-code validation call failed for code='{}': {}", bankCode, e.getMessage());
            return false;
        }
    }

    // ─── Transaction-limit validation ─────────────────────────────────────────

    /**
     * Calls {@code GET /api/v1/config/transaction-limits/{type}/validate?amount=}.
     *
     * @return {@code true} if the amount satisfies the configured minimum for the type
     */
    public boolean isAmountValid(String transactionType, java.math.BigDecimal amount) {
        try {
            AmountValidationResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/config/transaction-limits/{type}/validate")
                            .queryParam("amount", amount.toPlainString())
                            .build(transactionType))
                    .retrieve()
                    .body(AmountValidationResponse.class);
            return response != null && Boolean.TRUE.equals(response.valid());
        } catch (RestClientException e) {
            log.warn("Amount validation call failed for type='{}', amount='{}': {}",
                    transactionType, amount, e.getMessage());
            return false;
        }
    }

    // ─── Response records (inline — keep client self-contained) ──────────────

    public record BankCodeValidationResponse(String code, Boolean valid, String message) {}

    public record AmountValidationResponse(String transactionType, java.math.BigDecimal amount,
                                           Boolean valid, String message) {}
}
