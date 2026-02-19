package com.kreasipositif.configservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds the {@code bank-config} section from application.yml.
 * Holds the list of all valid bank codes recognized by the system.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "bank-config")
public class BankConfigProperties {

    private List<BankCodeEntry> validBankCodes;

    @Getter
    @Setter
    public static class BankCodeEntry {
        /** Short code used in CSV (e.g. "BCA", "BNI"). */
        private String code;
        /** Human-readable bank name. */
        private String name;
    }
}
