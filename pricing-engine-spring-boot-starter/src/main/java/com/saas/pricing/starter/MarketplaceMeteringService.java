package com.saas.pricing.starter;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.marketplace.MarketplaceUsageRecord;
import com.saas.pricing.core.model.marketplace.MeteringResult;
import com.saas.pricing.core.spi.MarketplaceMeterClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bridges rated usage into cloud-marketplace metering.
 *
 * <h2>Why the seller has to do this at all</h2>
 * When a SaaS product is sold through a marketplace, the <em>provider</em> bills the customer and
 * pays the seller. Usage therefore has to be reported upstream, and the seller does not learn about
 * chargebacks or disputes through their own invoicing at all - they learn about them when the
 * provider's statement disagrees.
 *
 * <p>Two properties follow, and both are enforced here:
 *
 * <ul>
 *   <li><strong>Records outside the provider's window are never submitted.</strong> AWS accepts the
 *       current and immediately preceding hour and rejects anything older with a
 *       {@code Timestamp older than...} error. Sending them wastes a slot in every batch and, worse,
 *       produces rejections that mask a real failure among routine noise. {@link #buildRecords}
 *       drops them and reports how many.</li>
 *   <li><strong>A partial batch is a partial success.</strong> {@link #submit} returns the provider's
 *       per-record outcome and never collapses it to a boolean.</li>
 * </ul>
 */
public class MarketplaceMeteringService {

    /**
     * AWS accepts the current hour and the immediately preceding one. Anything older is rejected.
     */
    public static final Duration DEFAULT_ACCEPTANCE_WINDOW = Duration.ofHours(2);

    private final MarketplaceMeterClient client;
    private final Clock clock;
    private final Duration acceptanceWindow;

    public MarketplaceMeteringService(MarketplaceMeterClient client, Clock clock) {
        this(client, clock, DEFAULT_ACCEPTANCE_WINDOW);
    }

    public MarketplaceMeteringService(MarketplaceMeterClient client, Clock clock, Duration acceptanceWindow) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (acceptanceWindow.isNegative() || acceptanceWindow.isZero()) {
            throw new IllegalArgumentException("acceptanceWindow must be positive");
        }
        this.acceptanceWindow = acceptanceWindow;
    }

    /** Outcome of building a batch: what will be sent, and what was dropped as too old. */
    public record Batch(List<MarketplaceUsageRecord> sendable, int expiredCount, Instant now) {
        public Batch {
            sendable = sendable == null ? List.of() : List.copyOf(sendable);
        }
    }

    /**
     * Builds the records for one rated window, dropping anything the provider would reject.
     *
     * @param idempotencyKeyPrefix stable per window, so a retried submission is recognised
     */
    public Batch buildRecords(TenantId tenantId, String dimension, String marketplaceCustomerId,
                              String unit, BigDecimal quantity, String assetId,
                              Instant usageStart, Instant usageEnd, String idempotencyKeyPrefix) {
        Instant now = clock.instant();
        var candidate = new MarketplaceUsageRecord(dimension, marketplaceCustomerId, quantity, unit,
            usageStart, usageEnd, now,
            assetId == null || assetId.isBlank()
                ? java.util.Optional.empty() : java.util.Optional.of(assetId),
            idempotencyKeyPrefix);

        if (candidate.isTooOldFor(now.minus(acceptanceWindow))) {
            return new Batch(List.of(), 1, now);
        }
        return new Batch(List.of(candidate), 0, now);
    }

    /**
     * Builds records for several windows and submits them.
     *
     * @return the provider's per-record outcome, including anything that was dropped as expired
     */
    public MeteringResult submit(List<MarketplaceUsageRecord> records) {
        List<MarketplaceUsageRecord> sendable = new ArrayList<>(
            records == null ? List.of() : records);
        if (!client.isEnabled() || sendable.isEmpty()) {
            return new MeteringResult(clock.instant(), List.of(), "no records to submit");
        }
        return client.submit(sendable);
    }

    public Duration acceptanceWindow() {
        return acceptanceWindow;
    }
}