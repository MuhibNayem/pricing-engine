package com.saas.pricing.starter;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.marketplace.MarketplaceUsageRecord;
import com.saas.pricing.core.model.marketplace.MeteringResult;
import com.saas.pricing.core.spi.MarketplaceMeterClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Marketplace metering.
 *
 * <p>Two properties are the whole point: a record too old for the provider is dropped rather than
 * burned as a guaranteed rejection, and a partial batch is reported as partial.
 */
class MarketplaceMeteringServiceTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final Instant NOW = Instant.parse("2026-10-08T12:30:00Z");

    /** Stands in for a provider that accepts some records and refuses others. */
    static class FakeMeterClient implements MarketplaceMeterClient {
        List<String> statuses = List.of();
        List<MarketplaceUsageRecord> received = List.of();

        @Override
        public MeteringResult submit(List<MarketplaceUsageRecord> records) {
            received = records;
            return MeteringResult.fromProviderStatuses(NOW, records, statuses,
                List.of(), "fake provider");
        }
    }

    private static MarketplaceMeteringService service(MarketplaceMeterClient client) {
        return new MarketplaceMeteringService(client,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a current record is sendable")
    void currentRecordIsSendable() {
        var batch = service(new FakeMeterClient()).buildRecords(TENANT, "Hrs", "cust-1", "Hrs",
            new BigDecimal("2.5"), null, NOW.minus(Duration.ofMinutes(30)), NOW, "w-1");

        assertThat(batch.sendable()).hasSize(1);
        assertThat(batch.expiredCount()).isZero();
    }

    @Test
    @DisplayName("a record older than the acceptance window is dropped, not sent")
    void expiredRecordIsDropped() {
        var batch = service(new FakeMeterClient()).buildRecords(TENANT, "Hrs", "cust-1", "Hrs",
            new BigDecimal("2.5"), null, NOW.minus(Duration.ofDays(3)),
            NOW.minus(Duration.ofDays(3)).plus(Duration.ofHours(1)), "w-old");

        assertThat(batch.sendable())
            .as("the provider rejects these; sending them wastes a batch slot and masks real failures")
            .isEmpty();
        assertThat(batch.expiredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the wire form matches the provider's key/value shape")
    void wireForm() {
        var record = MarketplaceUsageRecord.of("Hrs", "cust-1", new BigDecimal("2.50"), "Hrs",
            NOW.minusSeconds(3600), NOW, NOW, "w-1");

        var wire = record.toWireForm();

        assertThat(wire).containsEntry("Dimension", "Hrs")
            .containsEntry("CustomerIdentifier", "cust-1")
            .containsEntry("Quantity", "2.5")
            .containsEntry("Unit", "Hrs");
        assertThat(wire).containsKey("Timestamp");
    }

    @Test
    @DisplayName("a partially accepted batch is not reported as success")
    void partialBatchIsNotSuccess() {
        var client = new FakeMeterClient();
        client.statuses = List.of("Success", "CustomerInput", "Success");
        var service = service(client);

        var result = service.submit(List.of(
            MarketplaceUsageRecord.of("Hrs", "c1", BigDecimal.ONE, "Hrs",
                NOW.minusSeconds(60), NOW, NOW, "k1"),
            MarketplaceUsageRecord.of("GB", "c1", BigDecimal.TEN, "GB",
                NOW.minusSeconds(60), NOW, NOW, "k2"),
            MarketplaceUsageRecord.of("Hrs", "c1", BigDecimal.TEN, "Hrs",
                NOW.minusSeconds(60), NOW, NOW, "k3")));

        assertThat(result.isFullyAccepted())
            .as("a provider that billed the customer but refused our record must not look like success")
            .isFalse();
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.summary()).contains("rejected=1");
    }

    @Test
    @DisplayName("a negative quantity is refused rather than sent")
    void negativeQuantityRefused() {
        assertThatThrownBy(() -> MarketplaceUsageRecord.of("Hrs", "c1", new BigDecimal("-1"), "Hrs",
            NOW.minusSeconds(60), NOW, NOW, "k1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("a blank idempotency key is refused")
    void blankIdempotencyKeyRefused() {
        assertThatThrownBy(() -> MarketplaceUsageRecord.of("Hrs", "c1", BigDecimal.ONE, "Hrs",
            NOW.minusSeconds(60), NOW, NOW, "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reported twice");
    }

    @Test
    @DisplayName("an expired window yields a benign result rather than an exception")
    void nothingToSubmit() {
        var result = service(new FakeMeterClient()).submit(List.of());

        assertThat(result.isFullyAccepted()).isFalse();
        assertThat(result.outcomes()).isEmpty();
    }
}