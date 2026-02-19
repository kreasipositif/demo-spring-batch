package com.kreasipositif.accountvalidation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds the {@code mock} section from application.yml.
 * <p>
 * Contains the simulated latency and the in-memory list of seeded mock accounts
 * that represent valid accounts in a fictional bank core system.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mock")
public class MockAccountProperties {

    /**
     * Artificial delay in milliseconds applied to every validation call.
     * Simulates downstream bank API latency. Default: 500 ms.
     */
    private long latencyMs = 500;

    /** Seeded list of mock accounts loaded from application.yml. */
    private List<MockAccountEntry> accounts;

    @Getter
    @Setter
    public static class MockAccountEntry {
        /** Unique account number (alphanumeric). */
        private String accountNumber;
        /** Account holder's full name. */
        private String accountName;
        /** Bank code this account belongs to (e.g. "BCA"). */
        private String bankCode;
        /**
         * Account lifecycle status.
         * Allowed values: {@code ACTIVE}, {@code INACTIVE}, {@code BLOCKED}.
         */
        private String status;
    }
}
