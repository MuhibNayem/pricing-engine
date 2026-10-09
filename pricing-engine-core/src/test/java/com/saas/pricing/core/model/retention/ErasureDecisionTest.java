package com.saas.pricing.core.model.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention and GDPR erasure decisions.
 *
 * <p>The property under test is that an erasure request is answered <em>per record class</em> against
 * a specific statutory basis. A blanket delete would satisfy none of these tests, and for a billing
 * ledger it would destroy the evidence a tax authority requires.
 */
class ErasureDecisionTest {

    // Eight years before NOW, so the seven-year financial window has genuinely expired here.
    private static final Instant T0 = Instant.parse("2018-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Nested
    @DisplayName("RetentionClass")
    class Classes {

        @Test
        @DisplayName("a record cannot be anonymised before it must be kept")
        void erasableAfterMustNotPrecedeRetention() {
            assertThatThrownBy(() -> new RetentionClass(
                RetentionClass.RecordClass.FINANCIAL_LEDGER, Duration.ofDays(365 * 7),
                RetentionClass.LegalBasis.LEGAL_OBLIGATION, Optional.of(Duration.ofDays(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be anonymised before it must be kept");
        }

        @Test
        @DisplayName("a zero or negative retention period is refused")
        void retentionMustBePositive() {
            assertThatThrownBy(() -> new RetentionClass(
                RetentionClass.RecordClass.DIAGNOSTIC, Duration.ZERO,
                RetentionClass.LegalBasis.OPERATIONAL_ONLY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("statutory classes report themselves as retained")
        void statutoryDetection() {
            var obligation = new RetentionClass(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                Duration.ofDays(2555), RetentionClass.LegalBasis.LEGAL_OBLIGATION, Optional.empty());
            var operational = new RetentionClass(RetentionClass.RecordClass.DIAGNOSTIC,
                Duration.ofDays(30), RetentionClass.LegalBasis.OPERATIONAL_ONLY, Optional.empty());

            assertThat(obligation.isStatutorilyRetained()).isTrue();
            assertThat(operational.isStatutorilyRetained()).isFalse();
        }
    }

    @Nested
    @DisplayName("Erasure request")
    class Erasure {

        private Map<RetentionClass.RecordClass, RetentionClass> policy() {
            return ErasureDecision.defaultPolicy();
        }

        @Test
        @DisplayName("a blanket delete is refused: the ledger is kept and only anonymised")
        void ledgerIsNotErased() {
            var plan = ErasureDecision.evaluate(policy(),
                Map.of(RetentionClass.RecordClass.FINANCIAL_LEDGER, T0), NOW);

            var ledger = plan.forClass(RetentionClass.RecordClass.FINANCIAL_LEDGER).orElseThrow();

            assertThat(ledger.outcome())
                .as("destroying invoice and ledger records would breach the statutory duty to keep "
                    + "them; once the anonymisation point is reached the personal data is stripped, "
                    + "not the financial record")
                .isEqualTo(ErasureDecision.Outcome.ANONYMISE);
            assertThat(ledger.retention().legalBasis())
                .isEqualTo(RetentionClass.LegalBasis.LEGAL_OBLIGATION);
            assertThat(ledger.reason()).contains("Art. 17(3)");
            assertThat(ErasureDecision.shouldAnonymise(ledger.retention(), T0, NOW))
                .as("evaluate and shouldAnonymise must give the same answer for the same record")
                .isTrue();
            assertThat(plan.erased()).isEmpty();
            assertThat(plan.anonymised()).containsExactly(RetentionClass.RecordClass.FINANCIAL_LEDGER);
        }

        @Test
        @DisplayName("expired operational data IS erased")
        void expiredOperationalDataIsErased() {
            // Diagnostics older than 30 days have no statutory basis and must go.
            var plan = ErasureDecision.evaluate(policy(),
                Map.of(RetentionClass.RecordClass.DIAGNOSTIC,
                    Instant.parse("2025-01-01T00:00:00Z")), NOW);

            var diagnostics = plan.forClass(RetentionClass.RecordClass.DIAGNOSTIC).orElseThrow();
            assertThat(diagnostics.outcome()).isEqualTo(ErasureDecision.Outcome.ERASE);
            assertThat(plan.erased()).containsExactly(RetentionClass.RecordClass.DIAGNOSTIC);
        }

        @Test
        @DisplayName("operational data still inside its retention window is kept, not erased")
        void unexpiredOperationalDataKept() {
            var plan = ErasureDecision.evaluate(policy(),
                Map.of(RetentionClass.RecordClass.USAGE_TELEMETRY,
                    Instant.parse("2025-12-01T00:00:00Z")), NOW);

            var telemetry = plan.forClass(RetentionClass.RecordClass.USAGE_TELEMETRY).orElseThrow();
            assertThat(telemetry.outcome()).isEqualTo(ErasureDecision.Outcome.RETAIN);
            assertThat(telemetry.reason()).contains("has not yet expired");
        }

        @Test
        @DisplayName("a mixed request erases the erasable and retains the statutory")
        void mixedOutcomes() {
            var plan = ErasureDecision.evaluate(policy(), Map.of(
                RetentionClass.RecordClass.FINANCIAL_LEDGER, T0,
                RetentionClass.RecordClass.DIAGNOSTIC, Instant.parse("2025-01-01T00:00:00Z"),
                RetentionClass.RecordClass.USAGE_TELEMETRY, Instant.parse("2025-12-01T00:00:00Z")), NOW);

            assertThat(plan.erased()).containsExactly(RetentionClass.RecordClass.DIAGNOSTIC);
            assertThat(plan.anonymised()).containsExactly(RetentionClass.RecordClass.FINANCIAL_LEDGER);
            assertThat(plan.retained())
                .containsExactly(RetentionClass.RecordClass.USAGE_TELEMETRY);
            assertThat(plan.isFullyRetained()).isFalse();
        }

        @Test
        @DisplayName("a class with no records is absent from the plan, not treated as erasable")
        void absentClassIsOmitted() {
            var plan = ErasureDecision.evaluate(policy(),
                Map.of(RetentionClass.RecordClass.FINANCIAL_LEDGER, T0), NOW);

            assertThat(plan.forClass(RetentionClass.RecordClass.DIAGNOSTIC)).isEmpty();
            assertThat(plan.erased())
                .as("nothing to erase is not the same as 'erased'")
                .isEmpty();
        }

        @Test
        @DisplayName("the plan renders a data-subject response naming each basis")
        void rendersResponse() {
            var plan = ErasureDecision.evaluate(policy(), Map.of(
                RetentionClass.RecordClass.FINANCIAL_LEDGER, T0,
                RetentionClass.RecordClass.DIAGNOSTIC, Instant.parse("2025-01-01T00:00:00Z")), NOW);

            var response = plan.asResponse();
            assertThat(response).containsKeys(
                RetentionClass.RecordClass.FINANCIAL_LEDGER, RetentionClass.RecordClass.DIAGNOSTIC);
            assertThat(response.get(RetentionClass.RecordClass.FINANCIAL_LEDGER))
                .contains("LEGAL_OBLIGATION");
        }

        @Test
        @DisplayName("anonymisation unlocks only after the statutory window permits it")
        void anonymisationGatedOnRetention() {
            var ledger = new RetentionClass(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                Duration.ofDays(365 * 7), RetentionClass.LegalBasis.LEGAL_OBLIGATION,
                Optional.of(Duration.ofDays(365 * 7)));

            assertThat(ErasureDecision.shouldAnonymise(ledger, T0, NOW))
                .as("seven years on, personal data may go while the financial facts stay")
                .isTrue();

            var early = Instant.parse("2020-01-01T00:00:00Z");
            assertThat(ErasureDecision.shouldAnonymise(ledger, T0, early))
                .as("stripping personal data before the window closes would break the record")
                .isFalse();
        }
    }
}