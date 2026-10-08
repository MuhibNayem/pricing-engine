package com.saas.pricing.starter.repository;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.RateCardRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe default in-memory repository for out-of-the-box plug-and-play use.
 * Delegates to the enterprise core implementation.
 */
public class InMemoryRateCardRepository extends com.saas.pricing.core.spi.impl.InMemoryRateCardRepository {
}
