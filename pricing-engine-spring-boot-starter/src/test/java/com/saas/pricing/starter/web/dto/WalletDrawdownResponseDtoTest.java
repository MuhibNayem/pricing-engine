package com.saas.pricing.starter.web.dto;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalletDrawdownResponseDtoTest {

    @Test
    @DisplayName("Should consistently map Money to display strings, typed BigDecimals, and currency code")
    void testConsistentMoneyTyping() {
        Money original = Money.of(new BigDecimal("10.00"), CurrencyUnit.USD);
        Money creditVal = Money.of(new BigDecimal("10.00"), CurrencyUnit.USD);
        Money remaining = Money.of(BigDecimal.ZERO, CurrencyUnit.USD);

        Wallet wallet = com.saas.pricing.core.model.wallet.Wallet.of(
            "wal-1",
            com.saas.pricing.core.model.TenantId.of("t1"),
            com.saas.pricing.core.model.CustomerId.of("c1"),
            CurrencyUnit.USD,
            List.of()
        );

        WalletDrawdownResult result = new WalletDrawdownResult(
            "wal-1",
            original,
            new BigDecimal("10.00"),
            creditVal,
            remaining,
            List.of(),
            wallet
        );

        PricingDtos.WalletDrawdownResponseDto dto = PricingDtos.WalletDrawdownResponseDto.from(result);

        assertThat(dto.walletId()).isEqualTo("wal-1");
        // Display strings (Money.toString() formats like "10 USD" due to stripTrailingZeros)
        assertThat(dto.originalInvoiceAmount()).isEqualTo("10 USD");
        assertThat(dto.totalCreditMoneyValue()).isEqualTo("10 USD");
        assertThat(dto.remainingInvoiceDue()).isEqualTo("0 USD");

        // Machine-readable numeric fields and currency
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.originalAmount()).isEqualByComparingTo("10.00");
        assertThat(dto.totalCreditsDrawn()).isEqualByComparingTo("10.00");
        assertThat(dto.creditMoneyAmount()).isEqualByComparingTo("10.00");
        assertThat(dto.remainingDueAmount()).isEqualByComparingTo("0.00");
        assertThat(dto.fullyCovered()).isTrue();
    }

    @Test
    @DisplayName("Should extract currency and parse amounts via backward-compatible 6-arg constructor")
    void testBackwardCompatibleConstructor() {
        PricingDtos.WalletDrawdownResponseDto dto = new PricingDtos.WalletDrawdownResponseDto(
            "wal-legacy",
            "150.50 EUR",
            new BigDecimal("50.0"),
            "50.00 EUR",
            "100.50 EUR",
            false
        );

        assertThat(dto.walletId()).isEqualTo("wal-legacy");
        assertThat(dto.currency()).isEqualTo("EUR");
        assertThat(dto.originalAmount()).isEqualByComparingTo("150.50");
        assertThat(dto.totalCreditsDrawn()).isEqualByComparingTo("50.0");
        assertThat(dto.creditMoneyAmount()).isEqualByComparingTo("50.00");
        assertThat(dto.remainingDueAmount()).isEqualByComparingTo("100.50");
        assertThat(dto.fullyCovered()).isFalse();
    }

    @Test
    @DisplayName("Should deserialize legacy JSON payload without modern numeric fields")
    void testLegacyJsonDeserialization() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = """
            {
                "walletId": "wal-json-legacy",
                "originalInvoiceAmount": "250.75 USD",
                "totalCreditsDrawn": 150.25,
                "totalCreditMoneyValue": "150.25 USD",
                "remainingInvoiceDue": "100.50 USD",
                "fullyCovered": false
            }
            """;

        PricingDtos.WalletDrawdownResponseDto dto = mapper.readValue(json, PricingDtos.WalletDrawdownResponseDto.class);

        assertThat(dto.walletId()).isEqualTo("wal-json-legacy");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.originalAmount()).isEqualByComparingTo("250.75");
        assertThat(dto.totalCreditsDrawn()).isEqualByComparingTo("150.25");
        assertThat(dto.creditMoneyAmount()).isEqualByComparingTo("150.25");
        assertThat(dto.remainingDueAmount()).isEqualByComparingTo("100.50");
        assertThat(dto.fullyCovered()).isFalse();
    }

    @Test
    @DisplayName("Should derive display strings when only numeric amounts and currency are provided")
    void testNumericOnlyConstructorDerivesDisplayStrings() {
        PricingDtos.WalletDrawdownResponseDto dto = new PricingDtos.WalletDrawdownResponseDto(
            "wal-numeric",
            null,
            new BigDecimal("75.00"),
            null,
            null,
            true,
            "GBP",
            new BigDecimal("75.00"),
            new BigDecimal("75.00"),
            BigDecimal.ZERO
        );

        assertThat(dto.originalInvoiceAmount()).isEqualTo("75 GBP");
        assertThat(dto.totalCreditMoneyValue()).isEqualTo("75 GBP");
        assertThat(dto.remainingInvoiceDue()).isEqualTo("0 GBP");
        assertThat(dto.currency()).isEqualTo("GBP");
        assertThat(dto.originalAmount()).isEqualByComparingTo("75.00");
    }
}
