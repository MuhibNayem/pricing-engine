package com.saas.pricing.starter.test;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.spi.SubscriptionRepository;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.starter.PricingEngineAutoConfiguration;
import com.saas.pricing.starter.web.TestTenantConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural test verifying that FixtureResetTestExecutionListener prevents
 * state leaking across test methods in a cached Spring test context.
 */
@SpringBootTest(classes = PricingEngineAutoConfiguration.class)
@Import(TestTenantConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FixtureResetTestExecutionListenerTest {

    private static final TenantId TENANT = TenantId.of("t_iso");
    private static final CustomerId CUSTOMER = CustomerId.of("c_iso");
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    @Order(1)
    @DisplayName("Step 1: Should start clean, insert a subscription, and verify it exists")
    void step1_insertSubscription() {
        assertThat(subscriptionRepository.findByCustomer(TENANT, CUSTOMER)).isEmpty();

        subscriptionRepository.create(Subscription.active(
            "sub-iso-1", TENANT, CUSTOMER, PlanCode.of("PRO"), T0, T1, T0
        ));

        assertThat(subscriptionRepository.findByCustomer(TENANT, CUSTOMER)).hasSize(1);
    }

    @Test
    @Order(2)
    @DisplayName("Step 2: Should observe repository reset, having 0 subscriptions instead of leaked state")
    void step2_verifySubscriptionWasReset() {
        // Without FixtureResetTestExecutionListener, this would see the 1 subscription from step 1!
        assertThat(subscriptionRepository.findByCustomer(TENANT, CUSTOMER))
            .as("Subscription repository must be completely reset between tests")
            .isEmpty();

        subscriptionRepository.create(Subscription.active(
            "sub-iso-2", TENANT, CUSTOMER, PlanCode.of("PRO"), T0, T1, T0
        ));

        assertThat(subscriptionRepository.findByCustomer(TENANT, CUSTOMER)).hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("Step 3: Should start clean for wallets, insert a wallet, and verify it exists")
    void step3_insertWallet() {
        assertThat(subscriptionRepository.findByCustomer(TENANT, CUSTOMER)).isEmpty();
        assertThat(walletRepository.findWallet(TENANT, CUSTOMER)).isEmpty();

        walletRepository.save(Wallet.of("wal-iso-1", TENANT, CUSTOMER, CurrencyUnit.USD, List.of()));

        assertThat(walletRepository.findWallet(TENANT, CUSTOMER)).isPresent();
    }

    @Test
    @Order(4)
    @DisplayName("Step 4: Should observe wallet repository reset, having 0 wallets instead of leaked state")
    void step4_verifyWalletWasReset() {
        // Without FixtureResetTestExecutionListener, this would see the wallet from step 3!
        assertThat(walletRepository.findWallet(TENANT, CUSTOMER))
            .as("Wallet repository must be completely reset between tests")
            .isEmpty();
    }
}
