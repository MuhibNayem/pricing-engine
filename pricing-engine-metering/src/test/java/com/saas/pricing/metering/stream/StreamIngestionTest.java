package com.saas.pricing.metering.stream;

import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;
import com.saas.pricing.metering.engine.DefaultUsageMeteringEngine;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StreamIngestionTest {

    private DefaultUsageMeteringEngine meteringEngine;
    private DefaultMeterEventDispatcher dispatcher;
    private DefaultPricingEngine pricingEngine;
    private AsyncRatingTriggerService asyncRatingTriggerService;

    private final TenantId tenantId = TenantId.of("tenant_stream");
    private final CustomerId customerId = CustomerId.of("cust_stream");
    private final PlanCode planCode = PlanCode.of("STREAM_PLAN");
    private final Instant baseTime = Instant.parse("2026-10-08T12:00:00Z");
    private final TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

    private InMemoryWalletRepository walletRepository;
    private WalletDrawdownEngine walletDrawdownEngine;

    @BeforeEach
    void setUp() {
        var idempotencyStore = new InMemoryIdempotencyStore();
        var eventRepository = new InMemoryMeterEventRepository();
        var aggregationRepository = new InMemoryMeterAggregationRepository();
        var definitionRepository = new InMemoryMeterDefinitionRepository();

        definitionRepository.saveDefinition(MeterDefinition.sum("BYTES_SENT", "Bytes sent"));

        meteringEngine = new DefaultUsageMeteringEngine(
            idempotencyStore,
            eventRepository,
            aggregationRepository,
            definitionRepository,
            Optional.empty()
        );

        dispatcher = new DefaultMeterEventDispatcher(meteringEngine);

        // Rate card
        var rateCardRepo = new InMemoryRateCardRepository();
        rateCardRepo.save(RateCard.of(
            "rc_stream",
            tenantId,
            planCode,
            1,
            Instant.EPOCH,
            List.of(
                RatePlanItem.of("BYTES_SENT", "Bytes", PricingModel.PerUnitModel.of(new BigDecimal("0.01")), CurrencyUnit.USD)
            )
        ));

        pricingEngine = new DefaultPricingEngine(
            rateCardRepo,
            new InMemoryCurrencyExchangeProvider(),
            new RuleBasedTaxProvider(),
            AuditSink.noOp(),
            null
        );

        walletRepository = new InMemoryWalletRepository();
        walletDrawdownEngine = new WalletDrawdownEngine();

        // Seed wallet with 100 credits ($1.00 per credit)
        CreditGrant grant = CreditGrant.prepaid(
            "grant_stream_1", "w_stream_1", "Stream Credits", new BigDecimal("100.00"), BigDecimal.ONE, Instant.EPOCH
        );
        Wallet wallet = Wallet.of("w_stream_1", tenantId, customerId, CurrencyUnit.USD, List.of(grant));
        walletRepository.save(wallet);

        asyncRatingTriggerService = new AsyncRatingTriggerService(
            meteringEngine, pricingEngine, walletRepository, walletDrawdownEngine
        );
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        asyncRatingTriggerService.close();
    }

    @Test
    @DisplayName("Should dispatch events asynchronously to listeners via virtual threads")
    void testEventDispatcherWithListeners() throws Exception {
        AtomicInteger listenerCallCount = new AtomicInteger(0);
        List<String> eventIdsSeen = new CopyOnWriteArrayList<>();

        dispatcher.addListener((event, result) -> {
            listenerCallCount.incrementAndGet();
            if (result.isAccepted()) {
                eventIdsSeen.add(event.eventId());
            }
        });

        MeterEvent event = MeterEvent.builder()
            .eventId("evt_1")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("100"))
            .timestamp(baseTime.plusSeconds(10))
            .build();

        CompletableFuture<IngestionResult> future = dispatcher.publish(event);
        IngestionResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.isAccepted()).isTrue();

        // Give listener virtual thread a moment to finish
        Thread.sleep(100);
        assertThat(listenerCallCount.get()).isEqualTo(1);
        assertThat(eventIdsSeen).contains("evt_1");
    }

    @Test
    @DisplayName("Should trigger asynchronous window rating after event stream consumption")
    void testAsyncRatingTrigger() throws Exception {
        // Ingest streaming events
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("250"))
            .timestamp(baseTime.plusSeconds(30))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("150"))
            .timestamp(baseTime.plusSeconds(60))
            .build());

        // Trigger rating calculation asynchronously
        CompletableFuture<PricingResult> ratingFuture = asyncRatingTriggerService.aggregateAndRateAsync(
            tenantId,
            Optional.of(customerId),
            planCode,
            window,
            CurrencyUnit.USD
        );

        PricingResult result = ratingFuture.get(5, TimeUnit.SECONDS);

        // 250 + 150 = 400 bytes * 0.01 = $4.00
        assertThat(result.finalTotal().amount()).isEqualByComparingTo("4.00");
        assertThat(result.tenantId()).isEqualTo(tenantId);
        assertThat(result.planCode()).isEqualTo(planCode);
    }

    @Test
    @DisplayName("Should ingest and immediately trigger rating on event threshold")
    void testIngestAndTriggerRating() throws Exception {
        MeterEvent event = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("500"))
            .timestamp(baseTime.plusSeconds(45))
            .build();

        CompletableFuture<Optional<PricingResult>> future = asyncRatingTriggerService.ingestAndTriggerRatingAsync(
            event,
            planCode,
            window,
            CurrencyUnit.USD
        );

        Optional<PricingResult> optResult = future.get(5, TimeUnit.SECONDS);
        assertThat(optResult).isPresent();
        assertThat(optResult.get().finalTotal().amount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Should aggregate usage, rate, and perform wallet drawdown asynchronously on virtual threads")
    void testAggregateRateAndDrawdownAsync() throws Exception {
        // Ingest 300 bytes of usage ($3.00 total)
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("300"))
            .timestamp(baseTime.plusSeconds(30))
            .build());

        CompletableFuture<WalletDrawdownResult> drawdownFuture = asyncRatingTriggerService.aggregateRateAndDrawdownAsync(
            tenantId,
            customerId,
            planCode,
            window,
            CurrencyUnit.USD
        );

        WalletDrawdownResult result = drawdownFuture.get(5, TimeUnit.SECONDS);

        assertThat(result.walletId()).isEqualTo("w_stream_1");
        assertThat(result.originalInvoiceAmount().amount()).isEqualByComparingTo("3.00");
        assertThat(result.totalCreditsDrawn()).isEqualByComparingTo("3.00");
        assertThat(result.remainingInvoiceDue().amount()).isEqualByComparingTo("0.00");
        assertThat(result.isFullyCoveredByCredits()).isTrue();
        assertThat(result.transactions()).hasSize(1);

        // Verify updated wallet in repository has 97 credits remaining
        Wallet updatedWallet = walletRepository.findWallet(tenantId, customerId).orElseThrow();
        assertThat(updatedWallet.grants().getFirst().remainingCredits()).isEqualByComparingTo("97.00");

        // Verify transaction ledger written
        assertThat(walletRepository.findTransactions("w_stream_1")).hasSize(1);
    }

    @Test
    @DisplayName("StreamMessageConverter should serialize and deserialize stream messages across JSON, Map, and bytes")
    void testStreamMessageConverter() {
        StreamMessageConverter converter = new StreamMessageConverter();

        MeterEvent original = MeterEvent.builder()
            .eventId("stream_evt_100")
            .idempotencyKey("idem_100")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES_SENT")
            .value(new BigDecimal("42.5"))
            .timestamp(baseTime)
            .property("source", "kafka-topic")
            .property("region", "us-east-1")
            .build();

        // 1. JSON Roundtrip
        String json = converter.toJson(original);
        MeterEvent fromJson = converter.fromJson(json);
        assertThat(fromJson.eventId()).isEqualTo("stream_evt_100");
        assertThat(fromJson.idempotencyKey()).isEqualTo("idem_100");
        assertThat(fromJson.tenantId()).isEqualTo(tenantId);
        assertThat(fromJson.customerId()).contains(customerId);
        assertThat(fromJson.meterCode()).isEqualTo("BYTES_SENT");
        assertThat(fromJson.value()).isEqualByComparingTo("42.5");
        assertThat(fromJson.timestamp()).isEqualTo(baseTime);
        assertThat(fromJson.properties()).containsEntry("source", "kafka-topic");

        // 2. Map Roundtrip
        var map = converter.toMap(original);
        MeterEvent fromMap = converter.fromMap(map);
        assertThat(fromMap.eventId()).isEqualTo("stream_evt_100");

        // 3. Byte array Roundtrip
        byte[] bytes = converter.toBytes(original);
        MeterEvent fromBytes = converter.fromBytes(bytes);
        assertThat(fromBytes.eventId()).isEqualTo("stream_evt_100");
    }

    @Test
    @DisplayName("MeterEventConsumer should consume JSON and Map messages via StreamMessageConverter")
    void testMeterEventConsumerWithConverter() throws Exception {
        StreamMessageConverter converter = new StreamMessageConverter();
        CountDownLatch latch = new CountDownLatch(1);
        dispatcher.addListener((event, result) -> latch.countDown());

        String json = """
            {
                "eventId": "evt_consumer_1",
                "idempotencyKey": "idem_consumer_1",
                "tenantId": "tenant_stream",
                "customerId": "cust_stream",
                "meterCode": "BYTES_SENT",
                "value": 150,
                "timestamp": "2026-10-08T12:15:00Z",
                "properties": {"region": "eu-central-1"}
            }
            """;

        dispatcher.consumeJson(json, converter);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify aggregation includes consumed event
        MeterAggregation agg = meteringEngine.aggregate(tenantId, Optional.of(customerId), "BYTES_SENT", window);
        assertThat(agg.aggregatedValue()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("a message without a value is rejected, never silently billed as one unit")
    void missingValueIsRejected() {
        StreamMessageConverter converter = new StreamMessageConverter();
        var map = new java.util.HashMap<String, Object>();
        map.put("tenantId", "tenant_stream");
        map.put("meterCode", "BYTES_SENT");
        map.put("timestamp", "2026-10-08T12:15:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.fromMap(map))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no 'value'");
    }

    @Test
    @DisplayName("a message without a timestamp is rejected, never assigned to the current window")
    void missingTimestampIsRejected() {
        StreamMessageConverter converter = new StreamMessageConverter();
        var map = new java.util.HashMap<String, Object>();
        map.put("tenantId", "tenant_stream");
        map.put("meterCode", "BYTES_SENT");
        map.put("value", 10);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.fromMap(map))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no 'timestamp'");
    }
}
