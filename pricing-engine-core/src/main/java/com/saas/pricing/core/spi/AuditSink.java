package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.PricingResult;

/**
 * SPI for exporting or persisting calculation traces and results for audit / compliance.
 */
public interface AuditSink {

    void record(PricingResult result);

    static AuditSink noOp() {
        return result -> {};
    }
}
