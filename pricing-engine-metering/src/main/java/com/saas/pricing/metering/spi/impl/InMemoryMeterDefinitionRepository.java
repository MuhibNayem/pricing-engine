package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.spi.MeterDefinitionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory repository for storing and resolving MeterDefinitions.
 */
public class InMemoryMeterDefinitionRepository implements MeterDefinitionRepository {

    private final ConcurrentMap<String, MeterDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public Optional<MeterDefinition> findDefinition(String meterCode) {
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        return Optional.ofNullable(definitions.get(meterCode.toUpperCase()));
    }

    @Override
    public void saveDefinition(MeterDefinition definition) {
        Objects.requireNonNull(definition, "definition cannot be null");
        definitions.put(definition.meterCode().toUpperCase(), definition);
    }

    @Override
    public List<MeterDefinition> findAll() {
        return new ArrayList<>(definitions.values());
    }

    public void clear() {
        definitions.clear();
    }
}
