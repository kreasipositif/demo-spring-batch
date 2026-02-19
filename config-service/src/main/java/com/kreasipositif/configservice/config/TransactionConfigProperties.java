package com.kreasipositif.configservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Binds the {@code transaction-config} section from application.yml.
 * Holds per-transaction-type amount limits.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "transaction-config")
public class TransactionConfigProperties {

    private List<TransactionLimitEntry> limits;

    @Getter
    @Setter
    public static class TransactionLimitEntry {
        /** Transaction type key, e.g. "TRANSFER", "PAYMENT". */
        private String transactionType;
        /** Minimum allowed amount (inclusive). */
        private BigDecimal minAmount;
        /** Maximum allowed amount (inclusive). */
        private BigDecimal maxAmount;
        /** Supported currency code, e.g. "IDR". */
        private String currency;
    }
}
