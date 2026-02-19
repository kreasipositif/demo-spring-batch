package com.kreasipositif.batchprocessor.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * REST client for the account-validation-service (port 8082).
 *
 * <p>Uses the bulk endpoint {@code POST /api/v1/accounts/validate/bulk} to validate
 * both source and beneficiary accounts in a single network call per chunk.
 */
@Slf4j
@Component
public class AccountValidationClient {

    private final RestClient restClient;

    public AccountValidationClient(
            RestClient.Builder builder,
            @Value("${downstream.account-validation-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Validates a batch of accounts in one HTTP call.
     *
     * @param requests list of {@link AccountValidationRequest} (max 100 per service contract)
     * @return list of {@link AccountValidationResponse} in the same order; empty list on failure
     */
    public List<AccountValidationResponse> validateBulk(List<AccountValidationRequest> requests) {
        try {
            BulkResponse response = restClient.post()
                    .uri("/api/v1/accounts/validate/bulk")
                    .body(new BulkRequest(requests))
                    .retrieve()
                    .body(BulkResponse.class);

            if (response == null || response.results() == null) {
                log.warn("Bulk account-validation returned null response");
                return List.of();
            }
            return response.results();
        } catch (RestClientException e) {
            log.warn("Bulk account-validation call failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── Request / Response records ──────────────────────────────────────────

    public record AccountValidationRequest(String accountNumber, String bankCode) {}

    public record AccountValidationResponse(
            String accountNumber,
            String bankCode,
            String accountName,
            Boolean valid,
            String status,
            String reason) {}

    public record BulkRequest(List<AccountValidationRequest> accounts) {}

    public record BulkResponse(
            int totalRequested,
            int totalValid,
            int totalInvalid,
            List<AccountValidationResponse> results) {}
}
