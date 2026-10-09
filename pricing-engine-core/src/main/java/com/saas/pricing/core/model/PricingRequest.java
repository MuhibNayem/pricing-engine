package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Universal evaluation request supplied to the PricingEngine.
 */
public record PricingRequest(
    TenantId tenantId,
    Optional<CustomerId> customerId,
    PlanCode planCode,
    Optional<Instant> evaluationTime,
    CurrencyUnit targetCurrency,
    List<BillableItemRequest> items,
    List<Discount> discounts,
    Optional<ProrationWindow> prorationWindow,
    Map<String, Object> globalAttributes,
    /**
     * System-time ("as of") instant for bi-temporal resolution.
     *
     * <p>Distinct from {@link #evaluationTime}, which is <em>valid</em> time - when the usage
     * occurred. When empty, the rate card is resolved as known now. When set, the engine resolves
     * the pricing that was recorded at that instant, which is what makes a historical invoice
     * reproducible: a customer disputing a charge can be shown the rate that was in effect when
     * the charge was issued, even after the card has since been superseded.
     */
    Optional<Instant> systemTime,
    /**
     * The cadence of the period being charged, when the caller knows it.
     *
     * <p>Supplies the context that makes {@link FlatFeeModel#cadence()} meaningful. Without it an
     * annual fee and a monthly fee price identically, because nothing tells the engine which period
     * a flat amount is supposed to cover.
     */
    Optional<BillingCadence> billingCadence,
    /**
     * Renewal boundary for {@link #billingCadence}.
     *
     * <p>Together these enable anniversary versus calendar billing, short-month and leap-year
     * clamping, and mid-cycle proration. Both are optional so a caller doing a one-off rating does
     * not have to supply a cycle.
     */
    Optional<BillingCycleAnchor> billingCycleAnchor
) implements Serializable {

    /** Backward-compatible constructor for callers that do not need as-of resolution. */
    public PricingRequest(
        TenantId tenantId,
        Optional<CustomerId> customerId,
        PlanCode planCode,
        Optional<Instant> evaluationTime,
        CurrencyUnit targetCurrency,
        List<BillableItemRequest> items,
        List<Discount> discounts,
        Optional<ProrationWindow> prorationWindow,
        Map<String, Object> globalAttributes
    ) {
        this(tenantId, customerId, planCode, evaluationTime, targetCurrency, items, discounts,
            prorationWindow, globalAttributes, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public PricingRequest {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime cannot be null");
        Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(discounts, "discounts cannot be null");
        Objects.requireNonNull(prorationWindow, "prorationWindow cannot be null");
        Objects.requireNonNull(globalAttributes, "globalAttributes cannot be null");

        items = List.copyOf(items);
        discounts = List.copyOf(discounts);
        globalAttributes = Map.copyOf(globalAttributes);
    }

    public PricingRequest(
        TenantId tenantId,
        PlanCode planCode,
        Optional<Instant> evaluationTime,
        CurrencyUnit targetCurrency,
        List<BillableItemRequest> items,
        List<Discount> discounts,
        Optional<ProrationWindow> prorationWindow,
        Map<String, Object> globalAttributes
    ) {
        this(tenantId, Optional.empty(), planCode, evaluationTime, targetCurrency, items, discounts,
            prorationWindow, globalAttributes, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private TenantId tenantId;
        private CustomerId customerId;
        private PlanCode planCode;
        private Instant evaluationTime;
        private CurrencyUnit targetCurrency = CurrencyUnit.USD;
        private final List<BillableItemRequest> items = new ArrayList<>();
        private final List<Discount> discounts = new ArrayList<>();
        private ProrationWindow prorationWindow;
        private Instant systemTime;
        private BillingCadence billingCadence;
        private BillingCycleAnchor billingCycleAnchor;
        private final Map<String, Object> globalAttributes = new HashMap<>();

        public Builder tenantId(TenantId tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = TenantId.of(tenantId);
            return this;
        }

        public Builder customerId(CustomerId customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = CustomerId.of(customerId);
            return this;
        }

        public Builder planCode(PlanCode planCode) {
            this.planCode = planCode;
            return this;
        }

        public Builder planCode(String planCode) {
            this.planCode = PlanCode.of(planCode);
            return this;
        }

        public Builder evaluationTime(Instant evaluationTime) {
            this.evaluationTime = evaluationTime;
            return this;
        }

        public Builder targetCurrency(CurrencyUnit currency) {
            this.targetCurrency = currency;
            return this;
        }

        public Builder item(String itemCode, BigDecimal quantity) {
            this.items.add(BillableItemRequest.of(itemCode, quantity));
            return this;
        }

        public Builder item(String itemCode, long quantity) {
            this.items.add(BillableItemRequest.of(itemCode, quantity));
            return this;
        }

        public Builder item(String itemCode, BigDecimal quantity, Map<String, Object> attributes) {
            this.items.add(BillableItemRequest.of(itemCode, quantity, attributes));
            return this;
        }

        public Builder item(BillableItemRequest item) {
            this.items.add(item);
            return this;
        }

        public Builder discount(Discount discount) {
            this.discounts.add(discount);
            return this;
        }

        public Builder prorationWindow(ProrationWindow window) {
            this.prorationWindow = window;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.globalAttributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.globalAttributes.putAll(attributes);
            return this;
        }

        /**
         * Resolves pricing as known at {@code instant} (system time) rather than as known now.
         * Required to reproduce a historical rating after a rate card has been superseded.
         */
        public Builder systemTime(Instant instant) {
            this.systemTime = instant;
            return this;
        }

        /** Declares the cadence of the period being charged, so flat-fee cadence can be checked. */
        public Builder billingCadence(BillingCadence cadence) {
            this.billingCadence = cadence;
            return this;
        }

        /** Declares the renewal boundary for {@link #billingCadence(BillingCadence)}. */
        public Builder billingCycleAnchor(BillingCycleAnchor anchor) {
            this.billingCycleAnchor = anchor;
            return this;
        }

        /** Convenience: cadence plus its renewal boundary in one call. */
        public Builder billingCycle(BillingCadence cadence, BillingCycleAnchor anchor) {
            this.billingCadence = cadence;
            this.billingCycleAnchor = anchor;
            return this;
        }

        public PricingRequest build() {
            return new PricingRequest(
                Objects.requireNonNull(tenantId, "tenantId is required"),
                Optional.ofNullable(customerId),
                Objects.requireNonNull(planCode, "planCode is required"),
                Optional.ofNullable(evaluationTime),
                targetCurrency,
                items,
                discounts,
                Optional.ofNullable(prorationWindow),
                globalAttributes,
                Optional.ofNullable(systemTime),
                Optional.ofNullable(billingCadence),
                Optional.ofNullable(billingCycleAnchor)
            );
        }
    }
}
