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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineStarterMeteringAndPersistenceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class));

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

        contextRunner
            .withBean(JdbcTemplate.class, () -> jdbcTemplate)
            .withPropertyValues("pricing.engine.persistence-type=JDBC")
            .run(context -> {
                assertThat(context).hasSingleBean(RateCardRepository.class);
                assertThat(context.getBean(RateCardRepository.class)).isInstanceOf(JdbcRateCardRepository.class);
                assertThat(context.getBean(ContractOverrideRepository.class)).isInstanceOf(JdbcContractOverrideRepository.class);
                assertThat(context.getBean(WalletRepository.class)).isInstanceOf(JdbcWalletRepository.class);
                assertThat(context.getBean(EntitlementRepository.class)).isInstanceOf(JdbcEntitlementRepository.class);
                assertThat(context.getBean(MeterEventRepository.class)).isInstanceOf(JdbcMeterEventRepository.class);
                assertThat(context.getBean(MeterAggregationRepository.class)).isInstanceOf(JdbcMeterAggregationRepository.class);
            });
    }
}
