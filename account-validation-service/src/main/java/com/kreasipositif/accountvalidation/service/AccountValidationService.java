package com.kreasipositif.accountvalidation.service;

import com.kreasipositif.accountvalidation.config.MockAccountProperties;
import com.kreasipositif.accountvalidation.config.MockAccountProperties.MockAccountEntry;
import com.kreasipositif.accountvalidation.dto.AccountValidationRequest;
import com.kreasipositif.accountvalidation.dto.AccountValidationResponse;
import com.kreasipositif.accountvalidation.dto.BulkAccountValidationRequest;
import com.kreasipositif.accountvalidation.dto.BulkAccountValidationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core validation service that mimics a downstream bank account validation API.
 *
 * <p>Every call to {@link #validateAccount} applies a configurable blocking delay
 * (default 500 ms) to simulate real-world network and processing latency.
 * When running on virtual threads (enabled via {@code spring.threads.virtual.enabled=true}),
 * this blocking sleep is cheap — the carrier thread is released while the virtual
 * thread is parked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountValidationService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_NOT_FOUND = "NOT_FOUND";

    private final MockAccountProperties mockAccountProperties;

    /**
     * Lazily-computed index of accounts keyed by {@code accountNumber + ":" + bankCode}
     * for O(1) lookup. Built once on first access.
     */
    private volatile Map<String, MockAccountEntry> accountIndex;

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Validates a single account number against the in-memory mock data.
     *
     * <p>Applies a blocking sleep of {@code mock.latency-ms} milliseconds before
     * returning to simulate downstream API latency.
     *
     * @param request the account number and bank code to validate
     * @return an {@link AccountValidationResponse} with full status details
     */
    public AccountValidationResponse validateAccount(AccountValidationRequest request) {
        applyLatency();
        return doValidate(request.getAccountNumber(), request.getBankCode());
    }

    /**
     * Validates multiple accounts in a single request.
     *
     * <p>A single latency delay is applied once for the entire batch call,
     * simulating one downstream round-trip regardless of how many accounts are checked.
     *
     * @param request bulk request containing up to 100 account entries
     * @return a {@link BulkAccountValidationResponse} with per-account results and summary counts
     */
    public BulkAccountValidationResponse validateBulk(BulkAccountValidationRequest request) {
        applyLatency();

        List<AccountValidationResponse> results = request.getAccounts().stream()
                .map(r -> doValidate(r.getAccountNumber(), r.getBankCode()))
                .toList();

        long validCount = results.stream().filter(AccountValidationResponse::isValid).count();

        return BulkAccountValidationResponse.builder()
                .totalRequested(results.size())
                .totalValid((int) validCount)
                .totalInvalid((int) (results.size() - validCount))
                .results(results)
                .build();
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    private AccountValidationResponse doValidate(String accountNumber, String bankCode) {
        String key = buildKey(accountNumber, bankCode);
        Optional<MockAccountEntry> found = Optional.ofNullable(getAccountIndex().get(key));

        if (found.isEmpty()) {
            log.debug("Account not found: accountNumber={}, bankCode={}", accountNumber, bankCode);
            return AccountValidationResponse.builder()
                    .accountNumber(accountNumber)
                    .bankCode(bankCode)
                    .valid(false)
                    .status(STATUS_NOT_FOUND)
                    .reason("Account number '%s' not found for bank '%s'.".formatted(accountNumber, bankCode))
                    .build();
        }

        MockAccountEntry entry = found.get();
        boolean isActive = STATUS_ACTIVE.equalsIgnoreCase(entry.getStatus());

        return AccountValidationResponse.builder()
                .accountNumber(entry.getAccountNumber())
                .bankCode(entry.getBankCode())
                .accountName(entry.getAccountName())
                .valid(isActive)
                .status(entry.getStatus().toUpperCase())
                .reason(isActive ? null : "Account is %s.".formatted(entry.getStatus()))
                .build();
    }

    /**
     * Applies the configured latency delay. When called from a virtual thread,
     * {@link Thread#sleep} parks the virtual thread without blocking an OS thread.
     */
    private void applyLatency() {
        long latencyMs = mockAccountProperties.getLatencyMs();
        if (latencyMs <= 0) return;
        try {
            log.trace("Applying simulated latency of {} ms", latencyMs);
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Latency simulation interrupted", e);
        }
    }

    /**
     * Returns (and lazily initialises) the account lookup index.
     * Double-checked locking ensures it is built only once even under concurrent access.
     */
    private Map<String, MockAccountEntry> getAccountIndex() {
        if (accountIndex == null) {
            synchronized (this) {
                if (accountIndex == null) {
                    accountIndex = mockAccountProperties.getAccounts().stream()
                            .collect(Collectors.toMap(
                                    e -> buildKey(e.getAccountNumber(), e.getBankCode()),
                                    Function.identity()
                            ));
                    log.info("Mock account index built with {} entries", accountIndex.size());
                }
            }
        }
        return accountIndex;
    }

    private static String buildKey(String accountNumber, String bankCode) {
        return accountNumber + ":" + bankCode.toUpperCase();
    }
}
