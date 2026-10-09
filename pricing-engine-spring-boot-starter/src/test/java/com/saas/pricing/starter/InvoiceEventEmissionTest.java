package com.saas.pricing.starter;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.DomainEventFactory;
import com.saas.pricing.core.model.event.OutboxEvent;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every invoice state change must announce itself.
 *
 * <p>The outbox was built, migrated and fully tested while nothing in the codebase called
 * {@code enqueue(...)} — a durable, correct facility that no code path used. These tests pin the
 * invariant that closed that gap: a persisted change and its event are written together, so there
 * is no path that commits a document nobody is told about.
 */
class InvoiceEventEmissionTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PricingEngineAutoConfiguration.class))
        .withBean(com.saas.pricing.starter.tenant.TenantResolver.class,
                  () -> (com.saas.pricing.starter.tenant.TenantResolver) () -> "t1")
        .withBean(RateCardRepository.class, () -> {
            var repo = new InMemoryRateCardRepository();
            var item = RatePlanItem.of("SEATS", "seats",
                PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD);
            repo.save(RateCard.of("rc", TENANT, PLAN, 1, T0, List.of(item)));
            return repo;
        });

    @Test
    @DisplayName("drafting an invoice emits invoice.drafted")
    void draftingEmitsEvent() {
        contextRunner.run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var outbox = context.getBean(OutboxRepository.class);

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());

            var draft = com.saas.pricing.core.model.invoice.InvoiceFactory.draftFrom(rated, "inv-1",
                CUSTOMER, T0, T0.plusSeconds(86400), T0, "TX_STANDARD");

            lifecycle.createDraft(draft);

            assertThat(topicsOf(outbox))
                .as("a draft is editable, so it must not be announced as finalized")
                .containsExactly(DomainEventFactory.TOPIC_INVOICE_DRAFTED);
        });
    }

    @Test
    @DisplayName("finalize, pay, void and credit each emit their own topic")
    void lifecycleEmitsEveryTopic() {
        contextRunner.run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var outbox = context.getBean(OutboxRepository.class);

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());

            lifecycle.createDraft(com.saas.pricing.core.model.invoice.InvoiceFactory.draftFrom(rated,
                "inv-2", CUSTOMER, T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
            lifecycle.finalizeInvoice(TENANT, "inv-2", "INV-0002");
            lifecycle.recordPayment(TENANT, "inv-2", "50.00");
            lifecycle.issueCreditNote(TENANT, "inv-2", "cn-1", "service cancelled", "REFUND");

            assertThat(topicsOf(outbox)).containsExactly(
                DomainEventFactory.TOPIC_INVOICE_DRAFTED,
                DomainEventFactory.TOPIC_INVOICE_FINALIZED,
                DomainEventFactory.TOPIC_INVOICE_PAID,
                DomainEventFactory.TOPIC_CREDIT_NOTE_ISSUED);
        });
    }

    @Test
    @DisplayName("voiding emits invoice.voided")
    void voidingEmitsEvent() {
        contextRunner.run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var outbox = context.getBean(OutboxRepository.class);

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());

            lifecycle.createDraft(com.saas.pricing.core.model.invoice.InvoiceFactory.draftFrom(rated,
                "inv-3", CUSTOMER, T0, T0.plusSeconds(86400), T0, "TX_STANDARD"));
            lifecycle.finalizeInvoice(TENANT, "inv-3", "INV-0003");
            lifecycle.voidInvoice(TENANT, "inv-3");

            assertThat(topicsOf(outbox))
                .contains(DomainEventFactory.TOPIC_INVOICE_VOIDED);
        });
    }

    @Test
    @DisplayName("event ids are deterministic, so a retried transaction cannot announce twice")
    void eventIdsAreDeterministic() {
        assertThat(DomainEventFactory.eventId("invoice.finalized", "inv-1", 1L))
            .isEqualTo(DomainEventFactory.eventId("invoice.finalized", "inv-1", 1L))
            .isNotEqualTo(DomainEventFactory.eventId("invoice.finalized", "inv-1", 2L));

        var outbox = new com.saas.pricing.core.model.event.InMemoryOutboxRepository();
        outbox.enqueue(DomainEventFactory.invoiceFinalized("t1", "inv-1", "INV-1", "OPEN",
            "100.00", "USD", T0));
        outbox.enqueue(DomainEventFactory.invoiceFinalized("t1", "inv-1", "INV-1", "OPEN",
            "100.00", "USD", T0));

        assertThat(outbox.findUndelivered(null, 10))
            .as("re-announcing the same change must not produce a second event")
            .hasSize(1);
    }

    private static List<String> topicsOf(OutboxRepository outbox) {
        return outbox.findByTenant(TENANT, 50).stream().map(OutboxEvent::topic).toList();
    }

    @Test
    @DisplayName("drawing down a wallet emits wallet.drawn_down")
    void walletDrawdownEmitsEvent() {
        contextRunner.run(context -> {
            var service = context.getBean(EnterprisePricingService.class);
            var wallets = context.getBean(com.saas.pricing.core.spi.WalletRepository.class);
            var outbox = context.getBean(OutboxRepository.class);

            var grant = com.saas.pricing.core.model.wallet.CreditGrant.prepaid(
                "g1", "w1", "Prepaid", new BigDecimal("100.00"), BigDecimal.ONE, T0);
            wallets.save(com.saas.pricing.core.model.wallet.Wallet.of("w1", TENANT, CUSTOMER,
                USD, java.util.List.of(grant)));

            var rated = service.evaluate(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 4).build());

            service.evaluateAndDrawdown(com.saas.pricing.core.model.PricingRequest.builder()
                .tenantId("t1").planCode("PRO").evaluationTime(T0)
                .targetCurrency(USD).customerId("c1").item("SEATS", 2).build());

            assertThat(outbox.findUndelivered(DomainEventFactory.TOPIC_WALLET_DRAWN_DOWN, 10))
                .as("credit left the wallet and the ledger recorded it; a subscriber must be told")
                .hasSize(1);
        });
    }

    @Test
    @DisplayName("a plan change puts both the credit and the debit on the invoice")
    void planChangeCarriesBothSides() {
        contextRunner.run(context -> {
            var lifecycle = context.getBean(InvoiceLifecycleService.class);
            var outbox = context.getBean(OutboxRepository.class);

            var start = Instant.parse("2026-10-01T00:00:00Z");
            var end = Instant.parse("2026-10-31T00:00:00Z");
            var mid = Instant.parse("2026-10-16T00:00:00Z");

            var draft = lifecycle.draftForPlanChange("inv-pc", TENANT, CUSTOMER, PLAN, USD,
                "SEATS", "BASIC", Money.of("10.00", USD),
                "PRO", Money.of("20.00", USD),
                start, end, mid, mid);

            assertThat(draft.lineItems()).as("collapsing to a net figure would hide the owed credit")
                .hasSize(2);
            assertThat(draft.subtotal().amount())
                .as("$5 credit plus $10 debit nets to $5 owed")
                .isEqualByComparingTo("5.00");
            assertThat(draft.lineItems().get(0).amount().amount()).isEqualByComparingTo("-5.00");
            assertThat(draft.lineItems().get(1).amount().amount()).isEqualByComparingTo("10.00");
            assertThat(outbox.findUndelivered(DomainEventFactory.TOPIC_INVOICE_DRAFTED, 10)).hasSize(1);
        });
    }

    /**
     * Every starter service must be reachable as a bean.
     *
     * <p>A `@Service`-shaped class with a constructor and tests but no bean definition is invisible:
     * the class exists, it compiles, its logic is covered, and no application can ever obtain one.
     * {@code MarketplaceMeteringService} shipped exactly that way. This walks the starter's
     * production sources for classes named {@code *Service} and fails if the name appears nowhere
     * in the auto-configuration.
     */
    @Test
    @DisplayName("every starter service is registered as a bean")
    void everyStarterServiceIsRegistered() throws Exception {
        // Surefire runs with user.dir set to the module, so walk up until the starter's main
        // sources are found rather than assuming the repository root.
        var candidate = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        java.nio.file.Path packageDir = null;
        while (candidate != null) {
            var probe = candidate.resolve("src/main/java/com/saas/pricing/starter");
            if (java.nio.file.Files.isDirectory(probe)) {
                packageDir = probe;
                break;
            }
            candidate = candidate.getParent();
        }
        assertThat(packageDir)
            .as("could not locate the starter package sources from " + System.getProperty("user.dir"))
            .isNotNull();
        var autoConfig = packageDir.resolve("PricingEngineAutoConfiguration.java");

        // Match a real bean METHOD declaration rather than the class name appearing anywhere.
        // A plain substring check also matches the return type of the very method being removed,
        // so it would pass with the registration deleted - a guard that cannot fail is worse than
        // no guard at all.
        String source = java.nio.file.Files.readString(autoConfig)
            .replaceAll("(?m)^\\s*//.*$", "")
            .replaceAll("(?m)^\\s*\\*.*$", "")
            .replaceAll("(?m)^\\s*import .*$", "");

        List<String> unregistered = new java.util.ArrayList<>();
        try (var stream = java.nio.file.Files.walk(packageDir)) {
            for (var path : stream.filter(p -> p.toString().endsWith("Service.java")).toList()) {
                String simpleName = path.getFileName().toString().replace(".java", "");
                java.util.regex.Pattern declaration = java.util.regex.Pattern.compile(
                    // Optional package qualifier: a bean method may return either the simple
                    // name or the fully-qualified type.
                    "public\\s+(?:[\\w.]+\\.)?" + java.util.regex.Pattern.quote(simpleName)
                        + "\\s+\\w+\\s*\\(");
                if (!declaration.matcher(source).find()) {
                    unregistered.add(simpleName);
                }
            }
        }

        assertThat(unregistered)
            .as("these services are never registered as beans, so nothing can obtain one")
            .isEmpty();
    }

    /**
     * Guards against the exact failure this file was written for.
     *
     * <p>Four separate times in this codebase a facility was built, migrated and fully tested while
     * nothing ever called it: the outbox, then three event builders, then the proration calculator.
     * All were green. A durable, correct, unused capability reads exactly like a working one.
     *
     * <p>Behavioural tests cannot detect this - they cannot tell you a feature has no callers - so
     * the check is structural: walk the production sources and fail if a public entry point that is
     * supposed to emit or compute something has zero non-test references.
     */
    @Test
    @DisplayName("every event builder and proration entry point is actually called")
    void noUnusedCapabilities() throws Exception {
        var projectRoot = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();

        List<String> unused = new java.util.ArrayList<>();
        for (var spec : new Object[][] {
            {DomainEventFactory.class, null},
            {com.saas.pricing.core.model.invoice.ProrationCalculator.class,
                Set.of("netChange")},
            {com.saas.pricing.core.model.ai.AiPriceCardRenderer.class, Set.of()},
            {com.saas.pricing.core.model.ai.AiPriceList.class,
                Set.of("of", "isStale", "age", "priceFor", "modelIds", "size")},
            {com.saas.pricing.core.model.ai.ModelPrice.class,
                Set.of("of", "withCachedPrice", "effectiveCachedPrice")},
            {com.saas.pricing.core.model.marketplace.MarketplaceUsageRecord.class, Set.of("of", "toWireForm")},
            {com.saas.pricing.core.model.marketplace.MeteringResult.class,
                Set.of("fromProviderStatuses", "summary", "accepted", "rejected", "errors",
                    "pending", "isFullyAccepted", "hasRejected")},
            {com.saas.pricing.core.model.invoice.ProrationAdjustment.class,
                Set.of("netOf", "signedAmount", "isCredit", "toLineItem")}}) {

            var type = (Class<?>) spec[0];
            @SuppressWarnings("unchecked")
            var exempt = spec[1] == null ? Set.<String>of() : (Set<String>) spec[1];

            for (var method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        || method.isSynthetic() || java.lang.reflect.Modifier.isStatic(method.getModifiers()) == false
                        || method.getParameterCount() == 0) {
                    continue;
                }
                String name = method.getName();
                if (name.startsWith("randomEventId") || name.equals("eventId") || exempt.contains(name)) {
                    continue;
                }
                String needle = type.getSimpleName() + "." + name + "(";
                boolean called = false;
                try (var stream = java.nio.file.Files.walk(projectRoot, 8)) {
                    called = stream.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("/test/"))
                        .anyMatch(p -> contains(p, needle));
                }
                if (!called) {
                    unused.add(type.getSimpleName() + "." + name);
                }
            }
        }

        assertThat(unused)
            .as("these capabilities have no production caller, so they are never exercised at runtime")
            .isEmpty();
    }

    private static boolean contains(java.nio.file.Path file, String needle) {
        try {
            return java.nio.file.Files.readString(file).contains(needle);
        } catch (java.io.IOException e) {
            return false;
        }
    }
}