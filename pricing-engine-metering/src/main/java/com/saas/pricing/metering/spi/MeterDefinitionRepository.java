package com.saas.pricing.metering.spi;

import com.saas.pricing.metering.model.MeterDefinition;

import java.util.List;
import java.util.Optional;

/**
 * SPI for managing meter definitions and aggregation rules.
 */
public interface MeterDefinitionRepository {

    Optional<MeterDefinition> findDefinition(String meterCode);

    void saveDefinition(MeterDefinition definition);

    List<MeterDefinition> findAll();
}
