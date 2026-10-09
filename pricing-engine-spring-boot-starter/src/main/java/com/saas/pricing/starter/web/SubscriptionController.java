package com.saas.pricing.starter.web;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.starter.EnterprisePricingService;
import com.saas.pricing.starter.SubscriptionCommandService;
import com.saas.pricing.starter.SubscriptionLifecycleService;
import com.saas.pricing.starter.SubscriptionRenewalService;
import com.saas.pricing.starter.tenant.TenantGuard;
import com.saas.pricing.starter.web.dto.InvoiceDtos;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Subscription lifecycle API.
 *
 * <p>Every mutation routes through {@link SubscriptionCommandService}, never straight to the
 * repository. That is the whole point: a controller writing the row directly would persist the
 * change and skip the announcement, leaving the customer in a state no downstream system knows about.
 */
@RestController
@RequestMapping("/api/v1/pricing/subscriptions")
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "pricing.engine", name = "web-enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionController {

    private final SubscriptionCommandService commands;
    private final SubscriptionLifecycleService lifecycle;
    private final SubscriptionRenewalService renewal;
    private final TenantGuard tenantGuard;
    private final EnterprisePricingService pricingService;

    public SubscriptionController(SubscriptionCommandService commands,
                                  SubscriptionLifecycleService lifecycle,
                                  SubscriptionRenewalService renewal,
                                  TenantGuard tenantGuard,
                                  EnterprisePricingService pricingService) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle cannot be null");
        this.renewal = Objects.requireNonNull(renewal, "renewal cannot be null");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard cannot be null");
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService cannot be null");
    }

    @GetMapping("/{subscriptionId}")
    public ResponseEntity<InvoiceDtos.SubscriptionDto> get(
        @PathVariable String subscriptionId,
        @RequestParam String tenantId
    ) {
        return ResponseEntity.ok(toDto(
            commands.require(TenantId.of(tenantGuard.verify(tenantId)), subscriptionId)));
    }

    @GetMapping
    public ResponseEntity<List<InvoiceDtos.SubscriptionDto>> list(
        @RequestParam String tenantId,
        @RequestParam String customerId
    ) {
        var tid = TenantId.of(tenantGuard.verify(tenantId));
        return ResponseEntity.ok(toDtos(commands.listForCustomer(tid, CustomerId.of(customerId))));
    }

    @PostMapping("/{subscriptionId}/pause")
    public ResponseEntity<InvoiceDtos.SubscriptionDto> pause(
        @PathVariable String subscriptionId,
        @RequestParam String tenantId
    ) {
        return ResponseEntity.ok(
            toDto(commands.pause(TenantId.of(tenantGuard.verify(tenantId)), subscriptionId)));
    }

    @PostMapping("/{subscriptionId}/resume")
    public ResponseEntity<InvoiceDtos.SubscriptionDto> resume(
        @PathVariable String subscriptionId,
        @RequestParam String tenantId
    ) {
        return ResponseEntity.ok(
            toDto(commands.resume(TenantId.of(tenantGuard.verify(tenantId)), subscriptionId)));
    }

    @PostMapping("/{subscriptionId}/cancel")
    public ResponseEntity<InvoiceDtos.SubscriptionDto> cancel(
        @PathVariable String subscriptionId,
        @Valid @RequestBody InvoiceDtos.CancelSubscriptionRequest request
    ) {
        var tid = TenantId.of(tenantGuard.verify(request.tenantId()));
        Subscription existing = commands.require(tid, subscriptionId);

        // The credit is rated from the plan in force, never taken from the request: a client-supplied
        // price on a money-moving endpoint is a way to mint an arbitrary cancellation credit.
        Money fullPeriodPrice = pricingService.evaluate(PricingRequest.builder()
            .tenantId(tid.value())
            .customerId(existing.customerId().value())
            .planCode(existing.planCode().value())
            .targetCurrency(CurrencyUnit.of(request.currency()))
            .evaluationTime(existing.currentPeriodStart())
            .item(request.itemCode(), 1)
            .build()).finalTotal();

        var outcome = commands.cancel(tid, subscriptionId, request.atPeriodEnd(),
            lifecycle, request.itemCode(), fullPeriodPrice);

        var dto = toDto(outcome.subscription());
        return ResponseEntity.ok(new InvoiceDtos.SubscriptionDto(
            dto.subscriptionId(), dto.tenantId(), dto.customerId(), dto.planCode(), dto.status(),
            dto.currentPeriodStart(), dto.currentPeriodEnd(), dto.createdAt(), dto.trialEndsAt(),
            dto.canceledAt(), outcome.producesInvoiceLines(),
            outcome.netAdjustment().map(m -> m.amount().toPlainString()).orElse(null)));
    }

    /**
     * Runs the renewal sweep for one tenant.
     *
     * <p>Operator-facing, like the reconciliation endpoint: "did everyone get billed this month" is
     * a support question and answering it should not require a deploy. It is also the endpoint that
     * gives renewal a production caller — before this existed the job was fully implemented, fully
     * tested, and never invoked, which is indistinguishable from working.
     *
     * <p>Skipped subscriptions are reported rather than hidden. A tenant whose renewals are all
     * being skipped is a silent billing outage, and this response is where that becomes visible.
     */
    @PostMapping("/renewals/run")
    public ResponseEntity<InvoiceDtos.RenewalReportDto> runRenewals(
        @RequestParam String tenantId
    ) {
        var report = renewal.run(TenantId.of(tenantGuard.verify(tenantId)));
        var outcomes = report.outcomes().stream()
            .map(outcome -> new InvoiceDtos.RenewalOutcomeDto(
                outcome.subscriptionId(),
                outcome.finalState().status().name(),
                outcome.periodsAdvanced(),
                outcome.finalState().currentPeriodEnd(),
                outcome.skipReason()))
            .toList();
        return ResponseEntity.ok(new InvoiceDtos.RenewalReportDto(
            report.tenantId().value(), report.at(),
            report.renewed().size(), report.skipped().size(),
            report.hasBacklog(), outcomes));
    }

    private static InvoiceDtos.SubscriptionDto toDto(Subscription subscription) {
        return new InvoiceDtos.SubscriptionDto(
            subscription.subscriptionId(),
            subscription.tenantId().value(),
            subscription.customerId().value(),
            subscription.planCode().value(),
            subscription.status().name(),
            subscription.currentPeriodStart(),
            subscription.currentPeriodEnd(),
            subscription.createdAt(),
            subscription.trialEndsAt(),
            subscription.canceledAt(),
            false,
            null);
    }

    private static List<InvoiceDtos.SubscriptionDto> toDtos(List<Subscription> subscriptions) {
        return subscriptions.stream().map(SubscriptionController::toDto).toList();
    }
}