package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Append-only ledger semantics.
 *
 * <p>The stored wallet balance is a cache; this entry stream is the record of truth. A disputed
 * figure can only be defended if the history that produced it survives correction, which is what
 * reversal entries provide and in-place updates destroy.
 */
class LedgerEntryTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-09T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-10T00:00:00Z");

    private InMemoryWalletRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWalletRepository();
    }

    private LedgerEntry grant() {
        return LedgerEntry.of("e-grant", "w1", LedgerEntryType.GRANT_ISSUED,
            new BigDecimal("100.00"), Money.of(new BigDecimal("100.00"), USD), "calc-0", T0);
    }

    private LedgerEntry drawdown(String id, String credits, Instant at) {
        BigDecimal signed = new BigDecimal(credits);
        return LedgerEntry.of(id, "w1", LedgerEntryType.DRAWDOWN, signed,
            Money.of(signed, USD), "calc-1", at);
    }

    @Nested
    @DisplayName("Construction invariants")
    class Construction {

        @Test
        @DisplayName("a reversal must reference the entry it negates")
        void reversalMustReferenceAnEntry() {
            assertThatThrownBy(() -> new LedgerEntry("r1", "w1", LedgerEntryType.REVERSAL,
                new BigDecimal("10.00"), Money.of(new BigDecimal("10.00"), USD), "c",
                Optional.empty(), Optional.of("x"), T1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must reference");
        }

        @Test
        @DisplayName("only a REVERSAL may reference another entry")
        void onlyReversalMayReference() {
            assertThatThrownBy(() -> new LedgerEntry("d1", "w1", LedgerEntryType.DRAWDOWN,
                new BigDecimal("-10.00"), Money.of(new BigDecimal("-10.00"), USD), "c",
                Optional.of("e-grant"), Optional.empty(), T1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVERSAL");
        }

        @Test
        @DisplayName("a reversal cannot reference itself")
        void reversalCannotBeSelfReferential() {
            assertThatThrownBy(() -> new LedgerEntry("r1", "w1", LedgerEntryType.REVERSAL,
                new BigDecimal("10.00"), Money.of(new BigDecimal("10.00"), USD), "c",
                Optional.of("r1"), Optional.of("x"), T1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
        }

        @Test
        @DisplayName("a manual adjustment must state a reason")
        void adjustmentRequiresReason() {
            assertThatThrownBy(() -> new LedgerEntry("a1", "w1", LedgerEntryType.ADJUSTMENT,
                new BigDecimal("0.01"), Money.of(new BigDecimal("0.01"), USD), "c",
                Optional.empty(), Optional.empty(), T1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("credits and their money equivalent must agree in sign")
        void signAgreementEnforced() {
            // The shape the removed factory used to produce: a negative drawdown with zero money.
            // V4 rejects it in the database; the model rejects it before it gets there.
            assertThatThrownBy(() -> new LedgerEntry("d-bad", "w1", LedgerEntryType.DRAWDOWN,
                new BigDecimal("-10.00"), Money.zero(USD), "c",
                Optional.empty(), Optional.empty(), T1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agree in sign");
        }
    }

    @Nested
    @DisplayName("Reversal")
    class Reversal {

        @Test
        @DisplayName("a reversal carries the exact negation of the original")
        void reversalNegatesExactly() {
            var original = drawdown("e-d1", "-10.00", T1);

            var reversal = LedgerEntry.reversalOf(original, "billing error", T2);

            assertThat(reversal.signedCredits()).isEqualByComparingTo("10.00");
            assertThat(reversal.reversesEntryId()).contains("e-d1");
            assertThat(reversal.isCredit()).isTrue();
            assertThat(LedgerEntry.balanceOf(List.of(original, reversal)))
                .as("a reversal restores the balance to its pre-drawdown value")
                .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("reversal restores a full grant and drawdown sequence")
        void reversalRestoresBalance() {
            repository.appendLedgerEntries(List.of(
                grant(), drawdown("e-d1", "-10.00", T1), drawdown("e-d2", "-25.00", T1)));

            assertThat(LedgerEntry.balanceOf(repository.findLedgerEntries("w1"))).isEqualByComparingTo("65.00");

            repository.reverseLedgerEntry("e-d2", "duplicate rating", T2);

            var entries = repository.findLedgerEntries("w1");
            assertThat(LedgerEntry.balanceOf(entries)).isEqualByComparingTo("90.00");
            assertThat(entries).as("the original entry is still present, untouched").hasSize(4);
        }

        @Test
        @DisplayName("an entry cannot be reversed twice")
        void doubleReversalRejected() {
            repository.appendLedgerEntries(List.of(grant(), drawdown("e-d1", "-10.00", T1)));
            repository.reverseLedgerEntry("e-d1", "first", T2);

            assertThatThrownBy(() -> repository.reverseLedgerEntry("e-d1", "second", T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been reversed");
        }

        @Test
        @DisplayName("reversing an unknown entry is refused")
        void unknownEntryRejected() {
            assertThatThrownBy(() -> repository.reverseLedgerEntry("nope", "x", T1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Append-only")
    class AppendOnly {

        @Test
        @DisplayName("re-appending an identical entry is a no-op so a retry is safe")
        void identicalReAppendIsIdempotent() {
            // A retry after a serialization failure re-appends the same entry. Treating that as an
            // error would turn a recoverable conflict into a failed customer charge.
            repository.appendLedgerEntries(List.of(grant()));
            repository.appendLedgerEntries(List.of(grant()));

            assertThat(repository.findLedgerEntries("w1")).hasSize(1);
            assertThat(LedgerEntry.balanceOf(repository.findLedgerEntries("w1")))
                .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("re-using an entry id for different content is rejected")
        void conflictingEntryIdRejected() {
            // Same id, different amount: a genuine conflict, because silently accepting it would
            // let one id mean two different movements.
            repository.appendLedgerEntries(List.of(grant()));

            assertThatThrownBy(() -> repository.appendLedgerEntries(List.of(
                LedgerEntry.of("e-grant", "w1", LedgerEntryType.GRANT_ISSUED,
                    new BigDecimal("999.00"), Money.of(new BigDecimal("999.00"), USD), "calc-0", T0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("append-only");

            assertThat(repository.findLedgerEntries("w1"))
                .as("the original entry is untouched")
                .hasSize(1);
        }

        @Test
        @DisplayName("replaying the ledger reconstructs the balance")
        void replayReconstructsBalance() {
            repository.appendLedgerEntries(List.of(
                grant(), drawdown("e-d1", "-10.00", T1), drawdown("e-d2", "-33.33", T1)));

            assertThat(LedgerEntry.balanceOf(repository.findLedgerEntries("w1")))
                .isEqualByComparingTo("56.67");
        }

        @Test
        @DisplayName("gross movement ignores both an entry and its reversal")
        void grossMovementIgnoresReversals() {
            repository.appendLedgerEntries(List.of(grant(), drawdown("e-d1", "-10.00", T1)));
            repository.reverseLedgerEntry("e-d1", "cancelled", T2);

            assertThat(LedgerEntry.balanceOf(repository.findLedgerEntries("w1")))
                .isEqualByComparingTo("100.00");
            assertThat(LedgerEntry.netOfReversible(repository.findLedgerEntries("w1")))
                .as("the drawdown and its reversal cancel out of the gross figure")
                .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("reconciliation detects a balance that diverged from the ledger")
        void reconciliationDetectsDivergence() {
            var grant = CreditGrant.prepaid("g1", "w1", "Prepaid",
                new BigDecimal("100.00"), BigDecimal.ONE, T0);
            var wallet = Wallet.of("w1", TenantId.of("t1"), CustomerId.of("c1"), USD, List.of(grant));
            repository.appendLedgerEntries(List.of(
                LedgerEntry.of("e-grant", "w1", LedgerEntryType.GRANT_ISSUED,
                    new BigDecimal("100.00"), Money.of(new BigDecimal("100.00"), USD), "c", T0)));

            assertThat(repository.reconcile(wallet, T1))
                .as("stored balance matches the ledger")
                .isTrue();

            var drifted = new Wallet("w1", TenantId.of("t1"), CustomerId.of("c1"), USD,
                List.of(new CreditGrant("g1", "w1", "Prepaid", GrantType.PREPAID,
                    new BigDecimal("100.00"), new BigDecimal("70.00"), BigDecimal.ONE, 0, T0,
                    Optional.empty())));

            assertThat(repository.reconcile(drifted, T1))
                .as("a balance that does not replay from the ledger is a real defect")
                .isFalse();
        }
    }
}