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
import com.saas.pricing.core.spi.EntitlementEventRepository;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.starter.RetentionService;
import com.saas.pricing.core.spi.CollectionRepository;
import com.saas.pricing.core.spi.InvoiceRepository;
import com.saas.pricing.core.spi.SubscriptionRepository;
import com.saas.pricing.core.spi.EntitlementRepository;
import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.WalletRepository;
import com.saas.pricing.core.spi.impl.ConcurrentMapCacheProvider;
import com.saas.pricing.core.spi.impl.InMemoryAuditSink;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.impl.InMemoryContractOverrideRepository;
import java.time.Clock;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
    // 0. Tenant isolation (required whenever the REST API is exposed)
    // =========================================================================

    /**
     * Registers the tenant guard used by every REST endpoint.
     *
     * <p>Fails startup when the web API is enabled and no {@link TenantResolver} is supplied.
     * Without a resolver the only available tenant is the one in the request body, which lets any
     * caller price, meter, query entitlements against, or debit the wallet of any other tenant.
     * Refusing to start is the safe default; a deployment that deliberately trusts an
     * edge-authenticated proxy header can supply a resolver that reads it.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true",
            matchIfMissing = true)
    public com.saas.pricing.starter.tenant.TenantGuard tenantGuard(
        org.springframework.beans.factory.ObjectProvider<com.saas.pricing.starter.tenant.TenantResolver> resolvers
    ) {
        com.saas.pricing.starter.tenant.TenantResolver resolver = resolvers.getIfAvailable();
        if (resolver == null) {
            throw new IllegalStateException(
                    "pricing.engine.web-enabled is true but no TenantResolver bean is defined. "
                        + "Declare a TenantResolver that maps the authenticated caller to a tenant id "
                        + "(for example from SecurityContextHolder). Without it, tenant identity would be "
                        + "taken from untrusted request input and any caller could act as any tenant. "
                        + "To run without the REST API, set pricing.engine.web-enabled=false.");
        }
        log.info("Pricing Engine: tenant isolation enabled via {}", resolver.getClass().getName());
        return new com.saas.pricing.starter.tenant.TenantGuard(resolver);
    }

    /**
     * Registers the RFC 9457 error mapper.
     *
     * <p>The starter's package is not component-scanned (controllers are declared here too), so a
     * {@code @RestControllerAdvice} annotation alone registers nothing: every illegal-argument
     * failure surfaced as a 500 with a stack trace.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true",
            matchIfMissing = true)
    public com.saas.pricing.starter.web.PricingEngineExceptionHandler pricingEngineExceptionHandler() {
        return new com.saas.pricing.starter.web.PricingEngineExceptionHandler();
    }

    // =========================================================================
    // 1. SPI Repositories & Storage Adapters (In-Memory or JDBC / PostgreSQL)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public RateCardRepository rateCardRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            // Fail closed. Silently degrading to in-memory here means a production deployment that
            // asked for PostgreSQL quietly serves billing from RAM, losing every rate card on
            // restart with only an INFO line to say so.
            requireJdbc(jdbcTemplate, "RateCardRepository");
            log.info("Pricing Engine: Initializing enterprise JdbcRateCardRepository");
            return new JdbcRateCardRepository(jdbcTemplate);
        }
        log.info("Pricing Engine: Initializing default in-memory RateCardRepository");
        return new InMemoryRateCardRepository();
    }

    /**
     * Guards the JDBC persistence mode: if the operator asked for JDBC but no {@link JdbcTemplate}
     * is available, refuse to start rather than silently falling back to an in-memory store.
     */
    private static void requireJdbc(JdbcTemplate jdbcTemplate, String beanName) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException(
                    "pricing.engine.persistence-type=JDBC was requested but no JdbcTemplate is available "
                        + "for " + beanName + ". Add a DataSource and spring-boot-starter-jdbc, or set "
                        + "pricing.engine.persistence-type=IN_MEMORY explicitly if in-memory really is intended.");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractOverrideRepository contractOverrideRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "ContractOverrideRepository");
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
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "WalletRepository");
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
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "EntitlementRepository");
            log.info("Pricing Engine: Initializing enterprise JdbcEntitlementRepository");
            return new JdbcEntitlementRepository(jdbcTemplate);
        }
        return new InMemoryEntitlementRepository();
    }

    /**
     * Registers the append-only entitlement event store when a JDBC template is available, so the
     * derived projection survives a restart and can be reconciled against the legacy rows.
     */
    /**
     * Registers invoice persistence.
     *
     * <p>Without this the whole invoice subsystem - aggregate, credit notes and both repositories -
     * exists in the jar and is unreachable from a running application.
     */
    /**
     * Registers the collection ledger, so an OPEN invoice's retry history survives a restart.
     */
    /**
     * Registers the transactional outbox.
     *
     * <p>Events are written in the same transaction as the state change they describe, so a
     * committed change can never go unannounced.
     */
    /**
     * Registers the invoice lifecycle service, which is the only supported way to mutate an
     * invoice: it persists the state change and enqueues its event together.
     */
    /**
     * Registers the entitlement lifecycle service - the only supported way to grant or revoke
     * access, so the event stream, the legacy row and the outbox event always move together.
     */
    /**
     * Registers retention execution.
     *
     * <p>Default actions are deliberately inert: a deployment must supply its own
     * {@code RetentionActions} bound to its stores. A library that guessed which tables to delete
     * from would be far more dangerous than one that refuses to act.
     */
    /**
     * Registers marketplace metering, but only when the host has supplied a meter client.
     *
     * <p>Conditional rather than unconditional on purpose: the provider SDK is the host's concern,
     * and a metering service with no client would silently accept every record and drop it, which
     * is worse than being absent. When no client is configured the capability simply does not exist,
     * which is visible.
     */
    /**
     * Registers AI price-card syncing, so a provider price release reaches the rate card instead of
     * silently drifting away from it.
     */
    /**
     * Registers subscription lifecycle transitions, so a state change and the money it implies are
     * decided in one place.
     */
    /**
     * Registers subscription persistence.
     *
     * <p>Not optional infrastructure: a cancelled subscription that is not stored returns to life
     * when the process restarts, so the customer keeps access they stopped paying for and an
     * already-issued credit note can never be reconciled.
     */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionRepository subscriptionRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "SubscriptionRepository");
            return new com.saas.pricing.persistence.jdbc.JdbcSubscriptionRepository(jdbcTemplate);
        }
        return new com.saas.pricing.core.spi.impl.InMemorySubscriptionRepository();
    }


    /**
     * Registers subscription commands, so every state change persists AND announces itself.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.SubscriptionCommandService subscriptionCommandService(
        SubscriptionRepository subscriptionRepository,
        OutboxRepository outboxRepository
    ) {
        return new com.saas.pricing.starter.SubscriptionCommandService(
            subscriptionRepository, outboxRepository, java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.SubscriptionLifecycleService subscriptionLifecycleService() {
        return new com.saas.pricing.starter.SubscriptionLifecycleService(java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.AiPriceCardSyncService aiPriceCardSyncService() {
        return new com.saas.pricing.starter.AiPriceCardSyncService(java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(com.saas.pricing.core.spi.MarketplaceMeterClient.class)
    public com.saas.pricing.starter.MarketplaceMeteringService marketplaceMeteringService(
        com.saas.pricing.core.spi.MarketplaceMeterClient marketplaceMeterClient
    ) {
        return new com.saas.pricing.starter.MarketplaceMeteringService(
            marketplaceMeterClient, java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    public RetentionService retentionService(
        @Autowired(required = false) OutboxRepository outboxRepository,
        @Autowired(required = false) RetentionService.RetentionActions retentionActions
    ) {
        // A lambda cannot implement the two-method RetentionActions interface, so the inert
        // default is an explicit class.
        RetentionService.RetentionActions actions = retentionActions != null
            ? retentionActions
            : new RetentionService.RetentionActions() {
                @Override
                public int erase(com.saas.pricing.core.model.retention.RetentionClass.RecordClass recordClass,
                                 com.saas.pricing.core.model.TenantId tenantId,
                                 java.time.Instant createdBefore) {
                    return 0;
                }

                @Override
                public int anonymise(com.saas.pricing.core.model.retention.RetentionClass.RecordClass recordClass,
                                     com.saas.pricing.core.model.TenantId tenantId,
                                     java.time.Instant createdBefore) {
                    return 0;
                }
            };
        return new RetentionService(java.time.Clock.systemUTC(), outboxRepository, actions);
    }


    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.EntitlementLifecycleService entitlementLifecycleService(
        EntitlementRepository entitlementRepository,
        EntitlementEventRepository entitlementEventRepository,
        OutboxRepository outboxRepository
    ) {
        return new com.saas.pricing.starter.EntitlementLifecycleService(
            entitlementRepository, entitlementEventRepository, outboxRepository,
            java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.InvoiceLifecycleService invoiceLifecycleService(
        InvoiceRepository invoiceRepository,
        OutboxRepository outboxRepository,
        com.saas.pricing.core.model.invoice.InvoiceNumberService invoiceNumberService
    ) {
        return new com.saas.pricing.starter.InvoiceLifecycleService(
            invoiceRepository, outboxRepository, invoiceNumberService, java.time.Clock.systemUTC());
    }


    @Bean
    @ConditionalOnMissingBean
    public OutboxRepository outboxRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "OutboxRepository");
            return new com.saas.pricing.persistence.jdbc.JdbcOutboxRepository(jdbcTemplate);
        }
        return new com.saas.pricing.core.model.event.InMemoryOutboxRepository();
    }


    @Bean
    @ConditionalOnMissingBean
    public CollectionRepository collectionRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "CollectionRepository");
            return new com.saas.pricing.persistence.jdbc.JdbcCollectionRepository(jdbcTemplate);
        }
        return new com.saas.pricing.core.spi.impl.InMemoryCollectionRepository();
    }


    @Bean
    @ConditionalOnMissingBean
    public InvoiceRepository invoiceRepository(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "InvoiceRepository");
            return new com.saas.pricing.persistence.jdbc.JdbcInvoiceRepository(jdbcTemplate);
        }
        return new com.saas.pricing.core.spi.impl.InMemoryInvoiceRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public EntitlementEventRepository entitlementEventRepository(
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (jdbcTemplate == null) {
            return new com.saas.pricing.core.spi.impl.InMemoryEntitlementEventRepository();
        }
        return new com.saas.pricing.persistence.jdbc.JdbcEntitlementEventRepository(jdbcTemplate);
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
        if (properties.getAuditSinkType() == PricingEngineProperties.AuditSinkType.JDBC) {
            // An audit trail that silently becomes an in-memory buffer loses the evidence an
            // operator explicitly asked to keep, so refuse rather than degrade.
            requireJdbc(jdbcTemplate, "JdbcAuditSink");
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
        @Autowired(required = false) EntitlementEventRepository entitlementEventRepository,
        @Autowired(required = false) PricingEngineMetrics metrics,
        @Autowired(required = false) OutboxRepository outboxRepository
    ) {
        return new EnterprisePricingService(
            pricingEngine,
            batchPricingEngine,
            walletRepository,
            walletDrawdownEngine,
            entitlementRepository,
            entitlementVerifier,
            metrics,
            java.time.Clock.systemUTC(),
            // Passed through deliberately: calling the shorter constructor would drop it and
            // leave reconcileEntitlements() permanently unable to run.
            entitlementEventRepository,
            outboxRepository
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
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "MeterEventRepository");
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

                @Override
                public boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedTime) {
                    // Forwarded so a rolled-back ingestion releases its claim. Without this the
                    // JDBC path would inherit IdempotencyStore's no-op default and a legitimate
                    // retry would be permanently rejected as a duplicate.
                    return store.remove(tenantId, idempotencyKey, recordedTime);
                }
            };
        }
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "IdempotencyStore");
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

                @Override
                public boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedTime) {
                    return repo.remove(tenantId, idempotencyKey, recordedTime);
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
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "MeterAggregationRepository");
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
    public PricingEngineController pricingEngineController(
        EnterprisePricingService enterprisePricingService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard,
        PricingEngineProperties properties
    ) {
        return new PricingEngineController(enterprisePricingService, tenantGuard, properties);
    }

    /**
     * Registers the invoice API.
     *
     * <p>Explicitly, like the other controllers: a starter's own package is not component-scanned
     * by the host application, so a {@code @RestController} declared here would never be picked up.
     */
    /** Registers the subscription API; a starter's own package is not component-scanned. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public com.saas.pricing.starter.web.SubscriptionController subscriptionController(
        com.saas.pricing.starter.SubscriptionCommandService subscriptionCommandService,
        com.saas.pricing.starter.SubscriptionLifecycleService subscriptionLifecycleService,
        com.saas.pricing.starter.SubscriptionRenewalService subscriptionRenewalService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard,
        EnterprisePricingService enterprisePricingService
    ) {
        return new com.saas.pricing.starter.web.SubscriptionController(
            subscriptionCommandService, subscriptionLifecycleService, subscriptionRenewalService,
            tenantGuard, enterprisePricingService);
    }

    /**
     * The renewal sweep.
     *
     * <p>Registered so renewal has a production caller. The library deliberately does not own a
     * timer or a tenant list — it does not know what a platform's tenants are — so the host drives
     * this from its own scheduler and its own tenant source. See {@link SubscriptionRenewalService}
     * for the shape.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.starter.SubscriptionRenewalService subscriptionRenewalService(
        com.saas.pricing.starter.SubscriptionCommandService subscriptionCommandService
    ) {
        return new com.saas.pricing.starter.SubscriptionRenewalService(
            subscriptionCommandService, java.time.Clock.systemUTC());
    }


        /**
     * Invoice collection against a payment processor.
     *
     * <p>Only registered when the host supplies a {@code PaymentProcessor}. The engine defines the
     * contract — an idempotent charge that may honestly report PENDING rather than SETTLED — and the
     * host supplies the adapter. With no adapter the collection endpoint does not exist, rather than
     * existing and failing at the moment somebody tries to take money.
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
        com.saas.pricing.core.spi.PaymentProcessor.class)
    public com.saas.pricing.core.model.collection.InvoiceCollectionService invoiceCollectionService(
        com.saas.pricing.core.spi.PaymentProcessor paymentProcessor,
        CollectionRepository collectionRepository
    ) {
        return new com.saas.pricing.core.model.collection.InvoiceCollectionService(
            paymentProcessor, collectionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
        com.saas.pricing.core.spi.PaymentProcessor.class)
    public com.saas.pricing.core.model.collection.DunningSchedule dunningSchedule() {
        return com.saas.pricing.core.model.collection.DunningSchedule.standard();
    }

    /**
     * The collection endpoint.
     *
     * <p>Declared as a bean rather than left to component scanning: the starter's auto-configuration
     * is not a scanned package, so a {@code @RestController} would simply never exist.
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
        com.saas.pricing.core.spi.PaymentProcessor.class)
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public com.saas.pricing.starter.web.InvoiceCollectionController invoiceCollectionController(
        com.saas.pricing.core.model.collection.InvoiceCollectionService invoiceCollectionService,
        InvoiceRepository invoiceRepository,
        com.saas.pricing.core.model.collection.DunningSchedule dunningSchedule,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard
    ) {
        return new com.saas.pricing.starter.web.InvoiceCollectionController(
            invoiceCollectionService, invoiceRepository, dunningSchedule, tenantGuard,
            java.time.Clock.systemUTC());
    }

/**
     * Invoice document numbering.
     *
     * <p>JDBC-backed under JDBC so a series is shared across instances and survives a restart. An
     * in-process counter behind two application nodes hands the same document number to both, and
     * a duplicate invoice number is both an audit failure and a duplicate-money problem.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.core.spi.SequenceAllocator sequenceAllocator(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate,
        @Autowired(required = false) org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "SequenceAllocator");
            if (transactionManager == null) {
                // Fail closed. Without a transaction the row lock in the allocator is released
                // before the update runs, and two concurrent finalizations are handed the same
                // document number - so a missing transaction manager is not a degradation.
                throw new IllegalStateException(
                    "pricing.engine.persistence-type=JDBC requires a PlatformTransactionManager for "
                        + "invoice number allocation. Without a transaction the series row lock is "
                        + "released before the counter is updated, which duplicates invoice numbers.");
            }
            return new com.saas.pricing.persistence.jdbc.JdbcSequenceAllocator(jdbcTemplate, transactionManager);
        }
        return new com.saas.pricing.core.spi.impl.InMemorySequenceAllocator();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.core.model.invoice.InvoiceNumberService invoiceNumberService(
        com.saas.pricing.core.spi.SequenceAllocator sequenceAllocator,
        PricingEngineProperties properties
    ) {
        var config = properties.getInvoiceNumbering();
        var format = new com.saas.pricing.core.model.invoice.InvoiceNumberFormat(
            config.getPrefix(), "-", config.getPadding());
        var policy = new com.saas.pricing.core.model.invoice.InvoiceNumberPolicy(
            config.getScheme(), format, java.util.Map.of(), config.getStartAt());
        return new com.saas.pricing.core.model.invoice.InvoiceNumberService(sequenceAllocator, policy);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public com.saas.pricing.starter.web.InvoiceController invoiceController(
        EnterprisePricingService enterprisePricingService,
        InvoiceRepository invoiceRepository,
        com.saas.pricing.starter.InvoiceLifecycleService invoiceLifecycleService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard,
        com.saas.pricing.core.spi.IdempotencyKeyStore idempotencyKeyStore
    ) {
        return new com.saas.pricing.starter.web.InvoiceController(
            enterprisePricingService, invoiceRepository, invoiceLifecycleService, tenantGuard,
            idempotencyKeyStore);
    }

    /**
     * HTTP idempotency-key storage.
     *
     * <p>Separate from the metering {@code IdempotencyStore} on purpose. That one is boolean event
     * deduplication ("have I seen this event?"); this one implements the IETF header contract, with
     * request fingerprints, response replay and in-flight conflict. They share a simple name and
     * nothing else, and merging them would force one interface to answer "is this a duplicate?"
     * for both a replayed HTTP response and a metered event.
     *
     * <p>JDBC-backed when JDBC is the configured persistence mode, so a retried POST is deduped
     * across restarts and across instances rather than only within one JVM.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.saas.pricing.core.spi.IdempotencyKeyStore idempotencyKeyStore(
        PricingEngineProperties properties,
        @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        if (properties.getPersistenceType() == PricingEngineProperties.PersistenceType.JDBC) {
            requireJdbc(jdbcTemplate, "IdempotencyKeyStore");
            return new com.saas.pricing.persistence.jdbc.JdbcIdempotencyKeyStore(jdbcTemplate);
        }
        return new com.saas.pricing.core.spi.impl.InMemoryIdempotencyKeyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
    public MeteringController meteringController(
        UsageMeteringEngine usageMeteringEngine,
        EnterprisePricingService enterprisePricingService,
        com.saas.pricing.starter.tenant.TenantGuard tenantGuard
    ) {
        return new MeteringController(usageMeteringEngine, enterprisePricingService, tenantGuard);
    }
}
