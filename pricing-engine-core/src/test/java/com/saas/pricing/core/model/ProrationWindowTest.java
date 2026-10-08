package com.saas.pricing.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProrationWindowTest {

    @Test
    @DisplayName("Should compute 0.50 factor when active for half the billing cycle")
    void testHalfPeriodProration() {
        Instant periodStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant periodEnd = periodStart.plus(30, ChronoUnit.DAYS);

        Instant effectiveStart = periodStart.plus(15, ChronoUnit.DAYS);
        Instant effectiveEnd = periodEnd;

        ProrationWindow window = ProrationWindow.of(periodStart, periodEnd, effectiveStart, effectiveEnd);
        BigDecimal factor = window.calculateFactor();

        assertThat(factor).isEqualByComparingTo("0.5000000000");
    }

    @Test
    @DisplayName("Should compute 1.0 factor when active throughout entire period")
    void testFullPeriodProration() {
        Instant periodStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant periodEnd = periodStart.plus(30, ChronoUnit.DAYS);

        ProrationWindow window = ProrationWindow.of(periodStart, periodEnd, periodStart, periodEnd);
        BigDecimal factor = window.calculateFactor();

        assertThat(factor).isEqualByComparingTo(BigDecimal.ONE);
    }
}
