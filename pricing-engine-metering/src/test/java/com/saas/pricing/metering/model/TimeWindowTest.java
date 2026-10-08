package com.saas.pricing.metering.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

    @Test
    @DisplayName("Should create hourly window correctly")
    void testHourlyWindow() {
        Instant now = Instant.parse("2026-10-08T14:35:12Z");
        TimeWindow window = TimeWindow.hourly(now);

        assertThat(window.startTime()).isEqualTo(Instant.parse("2026-10-08T14:00:00Z"));
        assertThat(window.endTime()).isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));
        assertThat(window.contains(now)).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-08T14:00:00Z"))).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-08T15:00:00Z"))).isFalse(); // half-open
        assertThat(window.duration().toMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("Should create daily window correctly")
    void testDailyWindow() {
        Instant now = Instant.parse("2026-10-08T14:35:12Z");
        TimeWindow window = TimeWindow.daily(now, ZoneOffset.UTC);

        assertThat(window.startTime()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(window.endTime()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(window.contains(now)).isTrue();
        assertThat(window.duration().toHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("Should create monthly window correctly")
    void testMonthlyWindow() {
        Instant now = Instant.parse("2026-10-08T14:35:12Z");
        TimeWindow window = TimeWindow.monthly(now, ZoneOffset.UTC);

        assertThat(window.startTime()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(window.endTime()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(window.contains(now)).isTrue();
    }

    @Test
    @DisplayName("Should reject invalid window where end is before start")
    void testInvalidWindow() {
        Instant start = Instant.parse("2026-10-08T14:00:00Z");
        Instant end = Instant.parse("2026-10-08T13:00:00Z");

        assertThatThrownBy(() -> TimeWindow.of(start, end))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be before startTime");
    }
}
