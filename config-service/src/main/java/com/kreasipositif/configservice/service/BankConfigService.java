package com.kreasipositif.configservice.service;

import com.kreasipositif.configservice.config.BankConfigProperties;
import com.kreasipositif.configservice.config.BankConfigProperties.BankCodeEntry;
import com.kreasipositif.configservice.dto.BankCodeResponse;
import com.kreasipositif.configservice.dto.BankCodeValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service layer for bank-code related operations.
 */
@Service
@RequiredArgsConstructor
public class BankConfigService {

    private final BankConfigProperties bankConfigProperties;

    /**
     * Returns all configured valid bank codes.
     */
    public List<BankCodeResponse> getAllBankCodes() {
        return bankConfigProperties.getValidBankCodes().stream()
                .map(entry -> BankCodeResponse.builder()
                        .code(entry.getCode())
                        .name(entry.getName())
                        .build())
                .toList();
    }

    /**
     * Validates whether the given {@code bankCode} is in the configured list.
     *
     * @param bankCode the bank code to validate (case-insensitive)
     * @return a {@link BankCodeValidationResponse} with the result
     */
    public BankCodeValidationResponse validateBankCode(String bankCode) {
        Optional<BankCodeEntry> found = bankConfigProperties.getValidBankCodes().stream()
                .filter(e -> e.getCode().equalsIgnoreCase(bankCode))
                .findFirst();

        return BankCodeValidationResponse.builder()
                .code(found.map(BankCodeEntry::getCode).orElse(bankCode))
                .valid(found.isPresent())
                .name(found.map(BankCodeEntry::getName).orElse(null))
                .build();
    }
}
