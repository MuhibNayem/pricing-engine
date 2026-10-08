package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;

import java.time.Instant;
import java.util.Optional;

/**
 * SPI for resolving rate cards based on tenant, plan, and timestamp.
 */
public interface RateCardRepository {

    /**
     * Finds the rate card effective for the given tenant and plan at the given timestamp.
     */
    Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime);

    /**
     * Bi-temporal lookup: finds rate card effective at effectiveTime as recorded in the system at systemTime.
     */
    default Optional<RateCard> findBiTemporalRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        return findEffectiveRateCard(tenantId, planCode, effectiveTime)
            .filter(rc -> rc.isRecordValidAt(systemTime));
    }

    /**
     * Finds global catalog fallback rate card.
     */
    default Optional<RateCard> findGlobalRateCard(PlanCode planCode, Instant effectiveTime) {
        return findEffectiveRateCard(TenantId.of("GLOBAL"), planCode, effectiveTime);
    }

    /**
     * Bi-temporal global catalog fallback lookup.
     */
    default Optional<RateCard> findBiTemporalGlobalRateCard(PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        return findGlobalRateCard(planCode, effectiveTime)
            .filter(rc -> rc.isRecordValidAt(systemTime));
    }

    /**
     * Saves or updates a rate card.
     */
    void save(RateCard rateCard);
}
