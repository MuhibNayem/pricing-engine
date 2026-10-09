package com.saas.pricing.core.model.marketplace;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One billable unit of usage, in the shape a cloud marketplace metering API expects.
 *
 * <h2>Why this is not the same as an invoice line</h2>
 * A marketplace does not bill the customer directly. Usage is reported to the provider (AWS, Azure,
 * GCP), who then bills and pays the seller. That changes three things versus ordinary invoicing:
 *
 * <ul>
 *   <li><strong>Time is bucketed by the provider's window, not the invoice period.</strong> AWS
 *       rejects a record whose timestamp falls outside the current or immediately preceding hour,
 *       and backdates it after that.</li>
 *   <li><strong>The customer identity is the provider's customer identifier</strong> (an AWS
 *       customer identifier or an Azure subscription), not the merchant's own customer id.</li>
 *   <li><strong>Rejected records must be reported back.</strong> A batched submit returns per-item
 *       errors; treating a partial failure as success is how a seller silently loses revenue.</li>
 * </ul>
 *
 * @param dimension       provider dimension key; must match the listing configuration exactly
 * @param customerId      the provider's identifier for the marketplace customer
 * @param quantity        the metered quantity; providers are strict about sign and scale
 * @param unit            the unit the dimension was configured with (e.g. "Hrs", "GB", "Requests")
 * @param usageStart      inclusive start of the metered span
 * @param usageEnd        exclusive end of the metered span
 * @param recordedAt      when this engine produced the record
 * @param customerAssetId optional subscription or asset identifier
 * @param idempotencyKey  stable key so a retried submission is recognised, not double-reported
 */
public record MarketplaceUsageRecord(
    String dimension,
    String customerId,
    BigDecimal quantity,
    String unit,
    Instant usageStart,
    Instant usageEnd,
    Instant recordedAt,
    Optional<String> customerAssetId,
    String idempotencyKey
) implements Serializable {

    public MarketplaceUsageRecord {
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(unit, "unit cannot be null");
        Objects.requireNonNull(usageStart, "usageStart cannot be null");
        Objects.requireNonNull(usageEnd, "usageEnd cannot be null");
        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        Objects.requireNonNull(customerAssetId, "customerAssetId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");

        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension cannot be blank");
        }
        if (customerId.isBlank()) {
            throw new IllegalArgumentException(
                "customerId cannot be blank: a marketplace meters against the provider's customer identifier");
        }
        if (quantity.signum() < 0) {
            // Providers reject negative quantities outright; a negative metered value is a refund or
            // a correction, which needs its own mechanism rather than sneaking through here.
            throw new IllegalArgumentException("A marketplace usage record cannot be negative: " + quantity);
        }
        if (!usageEnd.isAfter(usageStart)) {
            throw new IllegalArgumentException("usageEnd must be after usageStart");
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                "idempotencyKey cannot be blank: a retried submission would otherwise be reported twice");
        }
    }

    public static MarketplaceUsageRecord of(String dimension, String customerId, BigDecimal quantity,
                                            String unit, Instant usageStart, Instant usageEnd,
                                            Instant recordedAt, String idempotencyKey) {
        return new MarketplaceUsageRecord(dimension, customerId, quantity, unit, usageStart, usageEnd,
            recordedAt, Optional.empty(), idempotencyKey);
    }

    /** True when this record falls outside the window a provider will still accept. */
    public boolean isTooOldFor(Instant earliestAccepted) {
        return usageEnd.isBefore(earliestAccepted);
    }

    /** The provider-facing wire form: key/value pairs, exactly as the Metering API expects. */
    public java.util.Map<String, String> toWireForm() {
        java.util.Map<String, String> wire = new java.util.LinkedHashMap<>();
        wire.put("Dimension", dimension);
        wire.put("CustomerIdentifier", customerId);
        wire.put("Quantity", quantity.stripTrailingZeros().toPlainString());
        wire.put("Unit", unit);
        wire.put("Timestamp", usageEnd.toString());
        return customerAssetId.map(asset -> {
            wire.put("CustomerAssetId", asset);
            return wire;
        }).orElse(wire);
    }
}