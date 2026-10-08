package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalletDrawdownEngineTest {

    private final WalletDrawdownEngine drawdownEngine = new WalletDrawdownEngine();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Should burn promotional credits before paid credits (priority ordering)")
    void testPriorityCreditBurn() {
        // Promotional grant (priority 1): 50 credits @ $1.00/credit = $50.00, expires in 7 days
        var promoGrant = CreditGrant.promotional(
            "grant_promo", "wallet_1", "Welcome Bonus",
            BigDecimal.valueOf(50), BigDecimal.ONE, now, now.plus(7, ChronoUnit.DAYS)
        );

        // Paid grant (priority 10): 200 credits @ $1.00/credit = $200.00
        var paidGrant = CreditGrant.prepaid(
            "grant_paid", "wallet_1", "Prepaid Pack",
            BigDecimal.valueOf(200), BigDecimal.ONE, now
        );

        Wallet wallet = new Wallet(
            "wallet_1", TenantId.of("tenant_acme"), CustomerId.of("cust_1"),
            CurrencyUnit.USD, List.of(promoGrant, paidGrant)
        );

        // Invoice of $80.00
        // Should exhaust 50 promo credits ($50) and take 30 paid credits ($30), remaining cash due = $0.00
        WalletDrawdownResult result = drawdownEngine.applyDrawdown(
            wallet, "calc_123", Money.of("80.00", CurrencyUnit.USD), now
        );

        assertThat(result.totalCreditsDrawn()).isEqualByComparingTo("80.00");
        assertThat(result.totalCreditMoneyValue().amount()).isEqualByComparingTo("80.00");
        assertThat(result.remainingInvoiceDue().amount()).isEqualByComparingTo("0.00");
        assertThat(result.transactions()).hasSize(2);

        // Check updated wallet balances
        Wallet updated = result.updatedWallet();
        assertThat(updated.totalRemainingCredits(now)).isEqualByComparingTo("170.00");
    }

    @Test
    @DisplayName("Should handle partial credit drawdown with remaining cash balance")
    void testPartialCreditDrawdown() {
        var grant = CreditGrant.prepaid(
            "grant_paid", "wallet_2", "Pack",
            BigDecimal.valueOf(30), BigDecimal.ONE, now
        );

        Wallet wallet = new Wallet(
            "wallet_2", TenantId.of("tenant_acme"), CustomerId.of("cust_2"),
            CurrencyUnit.USD, List.of(grant)
        );

        // Invoice of $100.00
        // Only $30 credits available -> remaining cash due = $70.00
        WalletDrawdownResult result = drawdownEngine.applyDrawdown(
            wallet, "calc_456", Money.of("100.00", CurrencyUnit.USD), now
        );

        assertThat(result.totalCreditsDrawn()).isEqualByComparingTo("30.00");
        assertThat(result.remainingInvoiceDue().amount()).isEqualByComparingTo("70.00");
        assertThat(result.updatedWallet().totalRemainingCredits(now)).isEqualByComparingTo("0.00");
    }
}
