package com.kreasipositif.configservice.service;

import com.kreasipositif.configservice.config.TransactionConfigProperties;
import com.kreasipositif.configservice.config.TransactionConfigProperties.TransactionLimitEntry;
import com.kreasipositif.configservice.dto.AmountValidationResponse;
import com.kreasipositif.configservice.dto.TransactionLimitResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for transaction-limit related operations.
 */
@Service
@RequiredArgsConstructor
public class TransactionConfigService {

    private final TransactionConfigProperties transactionConfigProperties;

    /**
     * Returns all configured transaction limits.
     */
    public List<TransactionLimitResponse> getAllLimits() {
        return transactionConfigProperties.getLimits().stream()
                .map(entry -> TransactionLimitResponse.builder()
                        .transactionType(entry.getTransactionType())
                        .minAmount(entry.getMinAmount())
                        .maxAmount(entry.getMaxAmount())
                        .currency(entry.getCurrency())
                        .build())
                .toList();
    }

    /**
     * Returns the limit configuration for a specific transaction type.
     *
     * @param transactionType the transaction type (case-insensitive)
     * @return {@link Optional} containing the limit, or empty if not found
     */
    public Optional<TransactionLimitResponse> getLimitByType(String transactionType) {
        return transactionConfigProperties.getLimits().stream()
                .filter(e -> e.getTransactionType().equalsIgnoreCase(transactionType))
                .map(entry -> TransactionLimitResponse.builder()
                        .transactionType(entry.getTransactionType())
                        .minAmount(entry.getMinAmount())
                        .maxAmount(entry.getMaxAmount())
                        .currency(entry.getCurrency())
                        .build())
                .findFirst();
    }

    /**
     * Validates that {@code amount} is within the limits for the given {@code transactionType}.
     *
     * @param transactionType the transaction type key
     * @param amount          the amount to validate
     * @return an {@link AmountValidationResponse} with the result
     */
    public AmountValidationResponse validateAmount(String transactionType, BigDecimal amount) {
        Optional<TransactionLimitEntry> found = transactionConfigProperties.getLimits().stream()
                .filter(e -> e.getTransactionType().equalsIgnoreCase(transactionType))
                .findFirst();

        if (found.isEmpty()) {
            return AmountValidationResponse.builder()
                    .transactionType(transactionType)
                    .amount(amount)
                    .valid(false)
                    .reason("Transaction type '%s' is not configured.".formatted(transactionType))
                    .build();
        }

        TransactionLimitEntry limit = found.get();

        if (amount.compareTo(limit.getMinAmount()) < 0) {
            return AmountValidationResponse.builder()
                    .transactionType(transactionType)
                    .amount(amount)
                    .valid(false)
                    .reason("Amount %s is below the minimum of %s for type '%s'."
                            .formatted(amount, limit.getMinAmount(), transactionType))
                    .build();
        }

        if (amount.compareTo(limit.getMaxAmount()) > 0) {
            return AmountValidationResponse.builder()
                    .transactionType(transactionType)
                    .amount(amount)
                    .valid(false)
                    .reason("Amount %s exceeds the maximum of %s for type '%s'."
                            .formatted(amount, limit.getMaxAmount(), transactionType))
                    .build();
        }

        return AmountValidationResponse.builder()
                .transactionType(transactionType)
                .amount(amount)
                .valid(true)
                .reason(null)
                .build();
    }
}
