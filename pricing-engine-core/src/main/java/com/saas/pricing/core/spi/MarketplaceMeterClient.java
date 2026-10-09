package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.marketplace.MarketplaceUsageRecord;
import com.saas.pricing.core.model.marketplace.MeteringResult;

import java.util.List;

/**
 * Submits metered usage to a cloud marketplace.
 *
 * <p>Implementations wrap the provider's metering API. They must:
 *
 * <ul>
 *   <li><strong>Never report a partial batch as a success.</strong> Return a {@link MeteringResult}
 *       carrying the provider's per-record status, so a caller can see which records the provider
 *       refused. A provider that has billed a customer for usage the seller was not credited for is
 *       unrecoverable once discovered late.</li>
 *   <li><strong>Treat {@code idempotencyKey} as opaque and stable.</strong> A retried batch must
 *       produce the same keys, so the provider recognises it as the same submission.</li>
 * </ul>
 *
 * <p>Outbound HTTP is deliberately <em>not</em> part of this interface's contract beyond submission;
 * the provider SDK is the host's concern. What the engine owns is the shape of the record and the
 * interpretation of the response.
 */
public interface MarketplaceMeterClient {

    /**
     * Submits a batch of usage records.
     *
     * @param records records to report; implementations may batch internally up to the provider limit
     * @return the per-record outcome; never null
     */
    MeteringResult submit(List<MarketplaceUsageRecord> records);

    /**
     * Records that are too old to submit any more, e.g. beyond the provider's late-arrival window.
     *
     * <p>Defaults to empty, which is correct for providers that never expire records.
     */
    default List<MarketplaceUsageRecord> findExpired(java.time.Instant now) {
        return List.of();
    }

    /** True when this client is configured and able to submit. */
    default boolean isEnabled() {
        return true;
    }
}