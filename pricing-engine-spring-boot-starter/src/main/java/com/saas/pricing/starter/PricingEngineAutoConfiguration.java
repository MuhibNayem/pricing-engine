package com.saas.pricing.starter;

import com.saas.pricing.core.engine.BatchPricingEngine;
import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.engine.EntitlementVerifier;
import com.saas.pricing.core.engine.HierarchicalRateCardResolver;
import com.saas.pricing.core.engine.PricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.CacheProvider;
import com.saas.pricing.core.spi.ContractOverrideRepository;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.core.spi.impl.ConcurrentMapCacheProvider;
import com.saas.pricing.core.spi.impl.InMemoryAuditSink;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.impl.InMemoryContractOverrideRepository;
import java.time.Instant;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.InMemoryEntitlementRepository;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;
import com.saas.pricing.core.spi.impl.VirtualThreadAuditSink;
import com.saas.pricing.evaluator.SpelFormulaExpressionEvaluator;
import com.saas.pricing.metering.engine.DefaultUsageMeteringEngine;
import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.spi.IdempotencyStore;
import com.saas.pricing.metering.spi.MeterAggregationRepository;
import com.saas.pricing.metering.spi.MeterDefinitionRepository;
import com.saas.pricing.metering.spi.MeterEventRepository;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;
import com.saas.pricing.metering.stream.AsyncRatingTriggerService;
import com.saas.pricing.metering.stream.DefaultMeterEventDispatcher;
import com.saas.pricing.metering.stream.MeterEventConsumer;
import com.saas.pricing.persistence.jdbc.JdbcAuditSink;
import com.saas.pricing.persistence.jdbc.JdbcContractOverrideRepository;
import com.saas.pricing.persistence.jdbc.JdbcEntitlementRepository;
import com.saas.pricing.persistence.jdbc.JdbcMeterAggregationRepository;
import com.saas.pricing.persistence.jdbc.JdbcMeterEventRepository;
import com.saas.pricing.persistence.jdbc.JdbcRateCardRepository;
import com.saas.pricing.persistence.jdbc.JdbcWalletRepository;
import com.saas.pricing.starter.metrics.PricingEngineMetrics;
import com.saas.pricing.starter.repository.InMemoryRateCardRepository;
import com.saas.pricing.starter.streaming.SpringMeterEventListener;
import com.saas.pricing.starter.streaming.SpringMeterEventPublisher;
import com.saas.pricing.starter.web.MeteringController;
import com.saas.pricing.starter.web.PricingEngineController;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Spring Boot 4 Auto-Configuration for enterprise plug-and-play SaaS Pricing Engine,
 * usage metering pipeline, event stream ingestion, and persistent storage adapters.
 */
@AutoConfiguration
@EnableConfigurationProperties(PricingEngineProperties.class)
@ConditionalOnProperty(prefix = "pricing.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PricingEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PricingEngineAutoConfiguration.class);

    // =========================================================================
    // 1. SPI Repositories & Storage Adapters (In-Memory or JDBC / PostgreSQL)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public RateCardRepository rateCardRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            log.info("Pricing Engine: Initializing enterprise JdbcRateCardRepository");
            return new JdbcRateCardRepository(jdbcTemplate);
        }
        log.info("Pricing Engine: Initializing default in-memory RateCardRepository");
        return new InMemoryRateCardRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractOverrideRepository contractOverrideRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            log.info("Pricing Engine: Initializing enterprise JdbcContractOverrideRepository");
            return new JdbcContractOverrideRepository(jdbcTemplate);
        }
        log.info("Pricing Engine: Initializing default in-memory ContractOverrideRepository");
        return new InMemoryContractOverrideRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public WalletRepository walletRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            log.info("Pricing Engine: Initializing enterprise JdbcWalletRepository");
            return new JdbcWalletRepository(jdbcTemplate);
        }
        return new InMemoryWalletRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public EntitlementRepository entitlementRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            log.info("Pricing Engine: Initializing enterprise JdbcEntitlementRepository");
            return new JdbcEntitlementRepository(jdbcTemplate);
        }
        return new InMemoryEntitlementRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditSink auditSink(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (!properties.isEnableAudit() || properties.getAuditSinkType() == PricingEngineProperties.AuditSinkType.NO_OP) {
            return AuditSink.noOp();
        }
        if (properties.getAuditSinkType() == PricingEngineProperties.AuditSinkType.JDBC && jdbcTemplate != null) {
            log.info("Pricing Engine: Initializing enterprise JdbcAuditSink");
            return new JdbcAuditSink(jdbcTemplate);
        }
        if (properties.getAuditSinkType() == PricingEngineProperties.AuditSinkType.VIRTUAL_THREAD) {
            return new VirtualThreadAuditSink(new InMemoryAuditSink());
        }
        if (properties.getAuditSinkType() == PricingEngineProperties.AuditSinkType.LOGGING) {
            return result -> log.debug(
                "Pricing evaluation completed: id={}, tenant={}, plan={}, total={}",
                result.calculationId(), result.tenantId().value(), result.planCode().value(), result.finalTotal()
            );
        }
        return new InMemoryAuditSink();
    }

    // =========================================================================
    // 2. Core Rating Engine Services
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public HierarchicalRateCardResolver hierarchicalRateCardResolver(
        RateCardRepository rateCardRepository,
        ContractOverrideRepository contractOverrideRepository
    ) {
        return new HierarchicalRateCardResolver(rateCardRepository, contractOverrideRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrencyExchangeProvider currencyExchangeProvider() {
        log.info("Pricing Engine: Initializing default InMemoryCurrencyExchangeProvider");
        return new InMemoryCurrencyExchangeProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaxProvider taxProvider() {
        return new RuleBasedTaxProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public FormulaExpressionEvaluator formulaExpressionEvaluator() {
        return new SpelFormulaExpressionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheProvider<?, ?> cacheProvider() {
        return new ConcurrentMapCacheProvider<>();
    }

    @Bean
    @ConditionalOnMissingBean
    public WalletDrawdownEngine walletDrawdownEngine() {
        return new WalletDrawdownEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public EntitlementVerifier entitlementVerifier() {
        return new EntitlementVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingEngine pricingEngine(
        RateCardRepository rateCardRepository,
        HierarchicalRateCardResolver hierarchicalRateCardResolver,
        CurrencyExchangeProvider currencyExchangeProvider,
        TaxProvider taxProvider,
        AuditSink auditSink,
        FormulaExpressionEvaluator formulaEvaluator
    ) {
        log.info("Pricing Engine: Initializing enterprise DefaultPricingEngine");
        return new DefaultPricingEngine(
            rateCardRepository,
            hierarchicalRateCardResolver,
            currencyExchangeProvider,
            taxProvider,
            auditSink,
            formulaEvaluator
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchPricingEngine batchPricingEngine(PricingEngine pricingEngine) {
        return new BatchPricingEngine(pricingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    public PricingEngineMetrics pricingEngineMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        return new PricingEngineMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EnterprisePricingService enterprisePricingService(
        PricingEngine pricingEngine,
        BatchPricingEngine batchPricingEngine,
        WalletRepository walletRepository,
        WalletDrawdownEngine walletDrawdownEngine,
        EntitlementRepository entitlementRepository,
        EntitlementVerifier entitlementVerifier,
        @Autowired(required = false) PricingEngineMetrics metrics
    ) {
        return new EnterprisePricingService(
            pricingEngine,
            batchPricingEngine,
            walletRepository,
            walletDrawdownEngine,
            entitlementRepository,
            entitlementVerifier,
            metrics
        );
    }

    // =========================================================================
    // 3. Usage Metering & Aggregation Engine
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public MeterEventRepository meterEventRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            return new JdbcMeterEventRepository(jdbcTemplate);
        }
        return new InMemoryMeterEventRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(
        PricingEngineProperties properties,
        MeterEventRepository meterEventRepository,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (meterEventRepository instanceof IdempotencyStore store) {
            return new IdempotencyStore() {
                @Override
                public boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime) {
                    return store.checkAndRecord(tenantId, idempotencyKey, eventTime);
                }

                @Override
                public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
                    return store.isDuplicate(tenantId, idempotencyKey);
                }
            };
        }
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            JdbcMeterEventRepository repo = new JdbcMeterEventRepository(jdbcTemplate);
            return new IdempotencyStore() {
                @Override
                public boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime) {
                    return repo.checkAndRecord(tenantId, idempotencyKey, eventTime);
                }

                @Override
                public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
                    return repo.isDuplicate(tenantId, idempotencyKey);
                }
            };
        }
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public MeterAggregationRepository meterAggregationRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC && jdbcTemplate != null) {
            return new JdbcMeterAggregationRepository(jdbcTemplate);
        }
        return new InMemoryMeterAggregationRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public MeterDefinitionRepository meterDefinitionRepository() {
        return new InMemoryMeterDefinitionRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine.metering", name = "enabled", havingValue = "true", matchIfMissing = true)
    public UsageMeteringEngine usageMeteringEngine(
        PricingEngineProperties properties,
        IdempotencyStore idempotencyStore,
        MeterEventRepository meterEventRepository,
        MeterAggregationRepository meterAggregationRepository,
        MeterDefinitionRepository meterDefinitionRepository
    ) {
        Optional<Duration> lateness = Optional.ofNullable(properties.getMetering().getAllowedLatenessSeconds())
            .map(Duration::ofSeconds);

        return new DefaultUsageMeteringEngine(
            idempotencyStore,
            meterEventRepository,
            meterAggregationRepository,
            meterDefinitionRepository,
            lateness
        );
    }

    // =========================================================================
    // 4. Streaming & Asynchronous Ingestion
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine.streaming", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMeterEventDispatcher meterEventDispatcher(UsageMeteringEngine usageMeteringEngine) {
        return new DefaultMeterEventDispatcher(usageMeteringEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine.streaming", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AsyncRatingTriggerService asyncRatingTriggerService(
        UsageMeteringEngine usageMeteringEngine,
        PricingEngine pricingEngine,
        @Autowired(required = false) WalletRepository walletRepository,
        @Autowired(required = false) WalletDrawdownEngine walletDrawdownEngine
    ) {
        return new AsyncRatingTriggerService(usageMeteringEngine, pricingEngine, walletRepository, walletDrawdownEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.metering.stream.StreamMessageConverter streamMessageConverter() {
        return new com.saas.pricing.metering.stream.StreamMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine.streaming", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpringMeterEventPublisher springMeterEventPublisher(
        UsageMeteringEngine usageMeteringEngine,
        ApplicationEventPublisher eventPublisher
    ) {
        return new SpringMeterEventPublisher(usageMeteringEngine, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine.streaming", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpringMeterEventListener springMeterEventListener(AsyncRatingTriggerService asyncRatingTriggerService) {
        return new SpringMeterEventListener(asyncRatingTriggerService);
    }

    // =========================================================================
    // 5. Web REST Controllers
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public PricingEngineController pricingEngineController(EnterprisePricingService enterprisePricingService) {
        return new PricingEngineController(enterprisePricingService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public MeteringController meteringController(
        UsageMeteringEngine usageMeteringEngine,
        EnterprisePricingService enterprisePricingService
    ) {
        return new MeteringController(usageMeteringEngine, enterprisePricingService);
    }
}
