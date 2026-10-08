package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.RateCardRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise template and base class for persistent rate card repositories (SQL, NoSQL, ORM).
 * Provides standard bi-temporal query resolution, version sorting, and immutability guarantees.
 */
public abstract class BasePersistentRateCardRepository implements RateCardRepository {

    /**
     * Subclasses query raw rate card records matching tenantId and planCode from underlying storage.
     */
    protected abstract List<RateCard> loadRawCards(TenantId tenantId, PlanCode planCode);

    /**
     * Subclasses persist the immutable rate card entity to storage.
     */
    protected abstract void persistCard(RateCard rateCard);

    @Override
    public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        List<RateCard> candidates = loadRawCards(tenantId, planCode);
        return candidates.stream()
            .filter(rc -> rc.isEffectiveAt(effectiveTime))
            .max(Comparator.comparingInt(RateCard::version));
    }

    @Override
    public Optional<RateCard> findBiTemporalRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        List<RateCard> candidates = loadRawCards(tenantId, planCode);
        return candidates.stream()
            .filter(rc -> rc.isBiTemporallyValidAt(effectiveTime, systemTime))
            .max(Comparator.comparingInt(RateCard::version));
    }

    @Override
    public void save(RateCard rateCard) {
        Objects.requireNonNull(rateCard, "rateCard cannot be null");
        validateNewVersion(rateCard);
        persistCard(rateCard);
    }

    /**
     * Enforces that newly submitted rate cards have strictly ascending versions
     * to prevent silent overwrites or state corruption.
     */
    protected void validateNewVersion(RateCard newCard) {
        List<RateCard> existing = loadRawCards(newCard.tenantId(), newCard.planCode());
        for (RateCard card : existing) {
            if (card.version() == newCard.version() && card.rateCardId().equals(newCard.rateCardId())) {
                // If it's superseded, it's an update
                if (newCard.supersededAt().isEmpty() && card.supersededAt().isEmpty()) {
                    throw new IllegalStateException(
                        "Rate card version %d already exists for plan '%s' and is immutable"
                            .formatted(newCard.version(), newCard.planCode().value())
                    );
                }
            }
        }
    }
}
