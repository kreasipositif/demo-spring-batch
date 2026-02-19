package com.kreasipositif.configservice;

import com.kreasipositif.configservice.service.BankConfigService;
import com.kreasipositif.configservice.service.TransactionConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConfigServiceApplicationTests {

    @Autowired
    private BankConfigService bankConfigService;

    @Autowired
    private TransactionConfigService transactionConfigService;

    @Test
    void contextLoads() {
        assertThat(bankConfigService).isNotNull();
        assertThat(transactionConfigService).isNotNull();
    }

    @Test
    void validBankCodeIsRecognized() {
        var result = bankConfigService.validateBankCode("BCA");
        assertThat(result.isValid()).isTrue();
        assertThat(result.getName()).isEqualTo("Bank Central Asia");
    }

    @Test
    void unknownBankCodeIsRejected() {
        var result = bankConfigService.validateBankCode("UNKNOWN_BANK");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void allBankCodesListIsNotEmpty() {
        var codes = bankConfigService.getAllBankCodes();
        assertThat(codes).isNotEmpty();
    }

    @Test
    void transferLimitAmountWithinRangeIsValid() {
        var result = transactionConfigService.validateAmount("TRANSFER", BigDecimal.valueOf(500_000));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void transferAmountBelowMinIsInvalid() {
        var result = transactionConfigService.validateAmount("TRANSFER", BigDecimal.valueOf(5_000));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("below the minimum");
    }

    @Test
    void transferAmountAboveMaxIsInvalid() {
        var result = transactionConfigService.validateAmount("TRANSFER", BigDecimal.valueOf(2_000_000_000L));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("exceeds the maximum");
    }

    @Test
    void unknownTransactionTypeIsInvalid() {
        var result = transactionConfigService.validateAmount("UNKNOWN_TYPE", BigDecimal.valueOf(100_000));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("not configured");
    }

    @Test
    void getAllLimitsReturnsAllConfiguredTypes() {
        var limits = transactionConfigService.getAllLimits();
        assertThat(limits).hasSizeGreaterThanOrEqualTo(4);
    }
}
