package com.saas.pricing.starter;

import com.saas.pricing.core.spi.ContractOverrideRepository;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.spi.IdempotencyStore;
import com.saas.pricing.metering.spi.MeterAggregationRepository;
import com.saas.pricing.metering.spi.MeterEventRepository;
import com.saas.pricing.metering.stream.AsyncRatingTriggerService;
import com.saas.pricing.metering.stream.DefaultMeterEventDispatcher;
import com.saas.pricing.persistence.jdbc.JdbcContractOverrideRepository;
import com.saas.pricing.persistence.jdbc.JdbcEntitlementRepository;
import com.saas.pricing.persistence.jdbc.JdbcMeterAggregationRepository;
import com.saas.pricing.persistence.jdbc.JdbcMeterEventRepository;
import com.saas.pricing.persistence.jdbc.JdbcRateCardRepository;
import com.saas.pricing.persistence.jdbc.JdbcWalletRepository;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;
import com.saas.pricing.starter.streaming.SpringMeterEventListener;
import com.saas.pricing.starter.streaming.SpringMeterEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineStarterMeteringAndPersistenceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        // The engine refuses to start without a TenantResolver; supply a trivial one so these
        // tests can exercise the beans rather than the fail-fast path.
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class, () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "tenant_test");

    @Test
    @DisplayName("Should auto-configure metering and streaming components by default")
    void testDefaultMeteringAndStreamingBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(UsageMeteringEngine.class);
            assertThat(context).hasSingleBean(MeterEventRepository.class);
            assertThat(context).hasSingleBean(MeterAggregationRepository.class);
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context).hasSingleBean(DefaultMeterEventDispatcher.class);
            assertThat(context).hasSingleBean(AsyncRatingTriggerService.class);
            assertThat(context).hasSingleBean(SpringMeterEventPublisher.class);
            assertThat(context).hasSingleBean(SpringMeterEventListener.class);

            // Default persistence should be in-memory
            assertThat(context.getBean(RateCardRepository.class)).isInstanceOf(InMemoryRateCardRepository.class);
        });
    }

    @Test
    @DisplayName("Should auto-configure JDBC repositories when persistenceType is JDBC and JdbcTemplate is available")
    void testJdbcPersistenceAutoConfiguration() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:starter_jdbc_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        // Spring Boot auto-configures this from spring-boot-starter-jdbc in a real application; a
        // bare ApplicationContextRunner does not, so it is registered here to mirror production.
        PlatformTransactionManager txManager = new DataSourceTransactionManager(ds);

        contextRunner
            .withBean(JdbcTemplate.class, () -> jdbcTemplate)
            .withBean(PlatformTransactionManager.class, () -> txManager)
            .withPropertyValues("pricing.engine.persistence-type=JDBC")
            .run(context -> {
                assertThat(context).hasSingleBean(RateCardRepository.class);
                assertThat(context.getBean(RateCardRepository.class)).isInstanceOf(JdbcRateCardRepository.class);
                assertThat(context.getBean(ContractOverrideRepository.class)).isInstanceOf(JdbcContractOverrideRepository.class);
                assertThat(context.getBean(WalletRepository.class)).isInstanceOf(JdbcWalletRepository.class);
                assertThat(context.getBean(EntitlementRepository.class)).isInstanceOf(JdbcEntitlementRepository.class);
                assertThat(context.getBean(MeterEventRepository.class)).isInstanceOf(JdbcMeterEventRepository.class);
                assertThat(context.getBean(MeterAggregationRepository.class)).isInstanceOf(JdbcMeterAggregationRepository.class);
                assertThat(context.getBean(com.saas.pricing.core.spi.SequenceAllocator.class))
                    .as("a JDBC series must be database-backed, or two nodes issue the same invoice number")
                    .isInstanceOf(com.saas.pricing.persistence.jdbc.JdbcSequenceAllocator.class);
            });
    }

    /**
     * A missing transaction manager is a startup failure, not a degraded mode.
     *
     * <p>Without a transaction the allocator's row lock is released before the counter is updated,
     * so two concurrent finalizations are handed the same document number. Degrading quietly there
     * would duplicate invoice numbers in production while every test passed.
     */
    @Test
    @DisplayName("Should refuse JDBC persistence when no transaction manager is available")
    void testJdbcWithoutTransactionManagerFailsClosed() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:starter_no_tx_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");

        contextRunner
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ds))
            .withPropertyValues("pricing.engine.persistence-type=JDBC")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("PlatformTransactionManager"));
    }
}
