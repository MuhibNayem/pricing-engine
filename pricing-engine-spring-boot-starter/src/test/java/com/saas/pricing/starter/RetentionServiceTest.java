package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.DomainEventFactory;
import com.saas.pricing.core.model.event.InMemoryOutboxRepository;
import com.saas.pricing.core.model.retention.ErasureDecision;
import com.saas.pricing.core.model.retention.RetentionClass;
import com.saas.pricing.core.model.retention.RetentionReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention execution.
 *
 * <p>The property that matters most: a statutory record is <em>never</em> deleted, no matter what
 * the policy says. A blanket delete would destroy the evidence a tax authority requires, which is
 * the failure mode the whole selective-erasure design exists to prevent.
 */
class RetentionServiceTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    /** Records what was asked of it, so a test can prove nothing financial was deleted. */
    static class RecordingActions implements RetentionService.RetentionActions {
        final List<String> erased = new ArrayList<>();
        final List<String> anonymised = new ArrayList<>();

        @Override
        public int erase(RetentionClass.RecordClass recordClass, TenantId tenantId, Instant createdBefore) {
            erased.add(recordClass.name());
            return 3;
        }

        @Override
        public int anonymise(RetentionClass.RecordClass recordClass, TenantId tenantId, Instant createdBefore) {
            anonymised.add(recordClass.name());
            return 5;
        }
    }

    private static RetentionService service(RecordingActions actions, InMemoryOutboxRepository outbox) {
        return new RetentionService(Clock.fixed(NOW, ZoneOffset.UTC), outbox, actions);
    }

    @Test
    @DisplayName("an expired erasure request deletes operational data and keeps the financial ledger")
    void operationalErasedLedgerRetained() {
        var actions = new RecordingActions();
        var outbox = new InMemoryOutboxRepository();
        var service = service(actions, outbox);

        var plan = ErasureDecision.evaluate(ErasureDecision.defaultPolicy(), Map.of(
            RetentionClass.RecordClass.DIAGNOSTIC, Instant.parse("2020-01-01T00:00:00Z"),
            RetentionClass.RecordClass.USAGE_TELEMETRY, Instant.parse("2025-12-01T00:00:00Z"),
            RetentionClass.RecordClass.FINANCIAL_LEDGER, Instant.parse("2018-01-01T00:00:00Z")), NOW);

        RetentionReport report = service.execute(plan, TENANT, CUSTOMER);

        assertThat(actions.erased)
            .as("expired operational data with no statutory hold is deleted")
            .containsExactly(RetentionClass.RecordClass.DIAGNOSTIC.name());

        assertThat(actions.anonymised)
            .as("usage telemetry is still inside its retention window, so nothing is touched")
            .isEmpty();

        assertThat(report.retained()).contains(RetentionClass.RecordClass.FINANCIAL_LEDGER);
        assertThat(report.erased()).containsExactly(RetentionClass.RecordClass.DIAGNOSTIC);
    }

    @Test
    @DisplayName("the financial ledger is never deleted, even if a policy asks for it")
    void ledgerIsNeverDeleted() {
        var actions = new RecordingActions();
        var service = service(actions, new InMemoryOutboxRepository());

        // A policy that (wrongly) says the ledger carries no statutory basis.
        var policy = Map.of(
            RetentionClass.RecordClass.FINANCIAL_LEDGER,
            new RetentionClass(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                java.time.Duration.ofDays(30),
                RetentionClass.LegalBasis.OPERATIONAL_ONLY, java.util.Optional.empty()));

        var plan = ErasureDecision.evaluate(policy,
            Map.of(RetentionClass.RecordClass.FINANCIAL_LEDGER, Instant.parse("2020-01-01T00:00:00Z")), NOW);

        var report = service.execute(plan, TENANT, CUSTOMER);

        assertThat(actions.erased)
            .as("the ledger table rejects DELETE; attempting it would be a no-op that looks like success")
            .isEmpty();
        assertThat(actions.anonymised).containsExactly(RetentionClass.RecordClass.FINANCIAL_LEDGER.name());
        assertThat(report.downgraded())
            .as("a downgrade is a reportable condition, not a detail to swallow")
            .containsExactly(RetentionClass.RecordClass.FINANCIAL_LEDGER);
        assertThat(report.isFullySatisfied()).isFalse();
    }

    @Test
    @DisplayName("execution is announced, and a downgrade uses its own topic")
    void executionIsAnnounced() {
        var actions = new RecordingActions();
        var outbox = new InMemoryOutboxRepository();
        var service = service(actions, outbox);

        var policy = Map.of(
            RetentionClass.RecordClass.FINANCIAL_LEDGER,
            new RetentionClass(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                java.time.Duration.ofDays(30),
                RetentionClass.LegalBasis.OPERATIONAL_ONLY, java.util.Optional.empty()));
        var plan = ErasureDecision.evaluate(policy,
            Map.of(RetentionClass.RecordClass.FINANCIAL_LEDGER, Instant.parse("2020-01-01T00:00:00Z")), NOW);

        service.execute(plan, TENANT, CUSTOMER);

        assertThat(outbox.findUndelivered(RetentionService.TOPIC_RETENTION_DOWNGRADED, 10))
            .as("an erasure request that could only be partly satisfied must be visible")
            .hasSize(1);
        assertThat(outbox.findUndelivered(RetentionService.TOPIC_RETENTION_EXECUTED, 10)).isEmpty();
    }

    @Test
    @DisplayName("a request that is satisfied outright is announced as executed, not downgraded")
    void cleanExecutionAnnounced() {
        var actions = new RecordingActions();
        var outbox = new InMemoryOutboxRepository();
        var service = service(actions, outbox);

        var plan = ErasureDecision.evaluate(ErasureDecision.defaultPolicy(),
            Map.of(RetentionClass.RecordClass.DIAGNOSTIC, Instant.parse("2020-01-01T00:00:00Z")), NOW);

        var report = service.execute(plan, TENANT, CUSTOMER);

        assertThat(report.isFullySatisfied()).isTrue();
        assertThat(outbox.findUndelivered(RetentionService.TOPIC_RETENTION_EXECUTED, 10)).hasSize(1);
    }
}