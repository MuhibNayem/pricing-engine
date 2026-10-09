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
            .max(versionOrder());
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
            .max(versionOrder());
    }

    /**
     * Newest version wins; ties are broken deterministically by system time and then id.
     *
     * <p>A bare version comparison returns whichever equal-version card the storage happened to
     * iterate first, so the same query could price differently between runs.
     */
    protected static Comparator<RateCard> versionOrder() {
        return Comparator.comparingInt(RateCard::version)
            .thenComparing(RateCard::recordedAt)
            .thenComparing(RateCard::rateCardId);
    }

    @Override
    public void save(RateCard rateCard) {
        Objects.requireNonNull(rateCard, "rateCard cannot be null");
        validateNewVersion(rateCard);
        persistCard(rateCard);
    }

    /**
     * Enforces the versioning contract: versions ascend within a tenant+plan, and an existing
     * version can only be touched by the supersede transition of the same card.
     *
     * <p>The previous check only rejected an exact (version, id) collision when neither card had a
     * supersede timestamp, so a second card could reuse a version under a different id and an
     * already-superseded card could be overwritten - both leave the effective lookup choosing
     * between ambiguous rows.
     */
    protected void validateNewVersion(RateCard newCard) {
        List<RateCard> existing = loadRawCards(newCard.tenantId(), newCard.planCode());
        for (RateCard card : existing) {
            if (card.version() > newCard.version()) {
                throw new IllegalStateException(
                    ("Rate card version %d cannot be stored for plan '%s' after version %d exists; "
                        + "versions must ascend so history cannot be rewritten")
                        .formatted(newCard.version(), newCard.planCode().value(), card.version()));
            }
            if (card.version() == newCard.version()) {
                boolean supersedeTransition = card.rateCardId().equals(newCard.rateCardId())
                    && newCard.supersededAt().isPresent();
                if (!supersedeTransition) {
                    throw new IllegalStateException(
                        ("Rate card version %d already exists for plan '%s' (card '%s'); a version is "
                            + "immutable and may only receive a supersede timestamp")
                            .formatted(newCard.version(), newCard.planCode().value(), card.rateCardId()));
                }
            }
        }
    }
}
