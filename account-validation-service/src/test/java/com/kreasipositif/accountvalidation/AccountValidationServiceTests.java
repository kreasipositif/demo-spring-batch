package com.kreasipositif.accountvalidation;

import com.kreasipositif.accountvalidation.dto.AccountValidationRequest;
import com.kreasipositif.accountvalidation.dto.BulkAccountValidationRequest;
import com.kreasipositif.accountvalidation.service.AccountValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AccountValidationService}.
 * Latency is set to 0 ms via test properties to keep the suite fast.
 */
@SpringBootTest
@TestPropertySource(properties = "mock.latency-ms=0")
class AccountValidationServiceTests {

    @Autowired
    private AccountValidationService service;

    // ─── Single validation ────────────────────────────────────────────────────

    @Test
    void contextLoads() {
        assertThat(service).isNotNull();
    }

    @Test
    void activeAccountIsValid() {
        var req = request("1234567890", "BCA");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isTrue();
        assertThat(res.getStatus()).isEqualTo("ACTIVE");
        assertThat(res.getAccountName()).isEqualTo("Budi Santoso");
    }

    @Test
    void inactiveAccountIsInvalid() {
        var req = request("6677889900", "CIMB");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isFalse();
        assertThat(res.getStatus()).isEqualTo("INACTIVE");
        assertThat(res.getReason()).contains("INACTIVE");
    }

    @Test
    void blockedAccountIsInvalid() {
        var req = request("3344556677", "PERMATA");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isFalse();
        assertThat(res.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void unknownAccountReturnsNotFound() {
        var req = request("0000000000", "BCA");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isFalse();
        assertThat(res.getStatus()).isEqualTo("NOT_FOUND");
        assertThat(res.getReason()).contains("not found");
    }

    @Test
    void bankCodeMismatchReturnsNotFound() {
        // Account exists for BCA, but requesting for BNI
        var req = request("1234567890", "BNI");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isFalse();
        assertThat(res.getStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    void bankCodeIsCaseInsensitive() {
        var req = request("1234567890", "bca");
        var res = service.validateAccount(req);

        assertThat(res.isValid()).isTrue();
    }

    // ─── Bulk validation ──────────────────────────────────────────────────────

    @Test
    void bulkValidationReturnsSummaryCounts() {
        var bulk = new BulkAccountValidationRequest();
        bulk.setAccounts(List.of(
                request("1234567890", "BCA"),   // ACTIVE  → valid
                request("0987654321", "BNI"),   // ACTIVE  → valid
                request("6677889900", "CIMB"),  // INACTIVE → invalid
                request("0000000000", "BCA")    // NOT_FOUND → invalid
        ));

        var res = service.validateBulk(bulk);

        assertThat(res.getTotalRequested()).isEqualTo(4);
        assertThat(res.getTotalValid()).isEqualTo(2);
        assertThat(res.getTotalInvalid()).isEqualTo(2);
        assertThat(res.getResults()).hasSize(4);
    }

    @Test
    void bulkValidationAllActiveAccounts() {
        var bulk = new BulkAccountValidationRequest();
        bulk.setAccounts(List.of(
                request("1234567890", "BCA"),
                request("1122334455", "BRI"),
                request("5544332211", "MANDIRI")
        ));

        var res = service.validateBulk(bulk);
        assertThat(res.getTotalValid()).isEqualTo(3);
        assertThat(res.getTotalInvalid()).isZero();
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private static AccountValidationRequest request(String accountNumber, String bankCode) {
        var req = new AccountValidationRequest();
        req.setAccountNumber(accountNumber);
        req.setBankCode(bankCode);
        return req;
    }
}
