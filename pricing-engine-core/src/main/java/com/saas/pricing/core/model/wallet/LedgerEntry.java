package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * One immutable, append-only movement on a wallet ledger.
 *
 * <h2>Why a ledger rather than a mutable balance</h2>
 * The wallet's stored balance is a cache. This entry stream is the record of truth: a balance can be
 * reconstructed by replaying it, and a disputed figure can be defended by showing the entries that
 * produced it. An in-place balance update destroys that evidence — it cannot show what a balance
 * was at a past instant, nor why it changed.
 *
 * <h2>Sign convention</h2>
 * The value is stored as a <em>signed</em> amount, so the balance is a plain sum:
 * <pre>
 *   GRANT_ISSUED  +100.00
 *   DRAWDOWN       -10.00
 *   REVERSAL       +10.00   (reverses the DRAWDOWN above)
 *   ------------------------------------------------
 *   derived balance 100.00
 * </pre>
 * A reversal always carries the exact negation of the entry it reverses, which
 * {@link #reverses} enforces at construction time.
 *
 * @param entryId               unique identifier for this entry
 * @param walletId              wallet this entry belongs to
 * @param type                  what kind of movement this is
 * @param credits               signed credit movement, in the wallet's credit unit
 * @param moneyValue            signed money equivalent, in the wallet's currency
 * @param currency              the wallet currency; every entry for a wallet must agree
 * @param calculationId         the rating that caused this movement, for traceability
 * @param reversesEntryId       the entry this one negates; present only for {@link LedgerEntryType#REVERSAL}
 * @param reason                mandatory for {@link LedgerEntryType#ADJUSTMENT}
 * @param createdAt             when the entry was recorded (system time, UTC)
 * @param metadata              free-form context, never interpreted by the engine
 */
public record LedgerEntry(
    String entryId,
    String walletId,
    LedgerEntryType type,
    BigDecimal credits,
    Money moneyValue,
    String calculationId,
    Optional<String> reversesEntryId,
    Optional<String> reason,
    Instant createdAt,
    Map<String, String> metadata
) implements Serializable {

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId cannot be null");
        Objects.requireNonNull(walletId, "walletId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(credits, "credits cannot be null");
        Objects.requireNonNull(moneyValue, "moneyValue cannot be null");
        Objects.requireNonNull(calculationId, "calculationId cannot be null");
        Objects.requireNonNull(reversesEntryId, "reversesEntryId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);

        if (reversesEntryId.isPresent() && type != LedgerEntryType.REVERSAL) {
            throw new IllegalArgumentException(
                    "Only a REVERSAL may reference the entry it reverses, got " + type);
        }
        if (type == LedgerEntryType.REVERSAL && reversesEntryId.isEmpty()) {
            throw new IllegalArgumentException("A REVERSAL must reference the entryId it reverses");
        }
        if (reversesEntryId.filter(id -> id.equals(entryId)).isPresent()) {
            throw new IllegalArgumentException("A REVERSAL cannot reference itself");
        }
        if (type == LedgerEntryType.ADJUSTMENT && reason.filter(r -> !r.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("An ADJUSTMENT must carry a reason");
        }
        // The two signed columns must agree in sign, mirroring ck_ledger_sign_agreement in V4.
        // A DRAWDOWN with negative credits and zero money is the shape that used to be produced
        // by the factory and gets rejected by the database; rejecting it here too turns a database
        // error into a model error with a useful message.
        if (credits.signum() != moneyValue.amount().signum()) {
            throw new IllegalArgumentException(
                "Credits and their money equivalent must agree in sign: "
                    + credits.toPlainString() + " credits vs " + moneyValue);
        }
    }

    /** Signed credit movement. */
    public BigDecimal signedCredits() {
        return credits;
    }

    /** Signed money movement. */
    public Money signedMoney() {
        return Money.of(moneyValue.amount(), moneyValue.currency());
    }

    /** True when this entry returns credit to the wallet. */
    public boolean isCredit() {
        return credits.signum() > 0;
    }

    public static LedgerEntry of(
        String entryId,
        String walletId,
        LedgerEntryType type,
        BigDecimal credits,
        Money moneyValue,
        String calculationId,
        Instant createdAt
    ) {
        return new LedgerEntry(entryId, walletId, type, credits,
            moneyValue, calculationId, Optional.empty(), Optional.empty(), createdAt, Map.of());
    }

    /**
     * Builds a reversal that exactly negates {@code original}.
     *
     * <p>The negation is not optional arithmetic left to the caller: it is computed here so a
     * reversal cannot be recorded for the wrong amount.
     */
    public static LedgerEntry reversalOf(LedgerEntry original, String reason, Instant createdAt) {
        Objects.requireNonNull(original, "original cannot be null");
        return new LedgerEntry(
                "rev-" + original.entryId() + "-" + createdAt.toEpochMilli(),
                original.walletId(),
                LedgerEntryType.REVERSAL,
                original.signedCredits().negate(),
                original.signedMoney().negate(),
                original.calculationId(),
                Optional.of(original.entryId()),
                Optional.of(reason == null || reason.isBlank() ? "Reversal" : reason),
                createdAt,
                Map.of());
    }

    /** Sum of the signed credit movements; the authoritative wallet balance. */
    public static BigDecimal balanceOf(java.util.List<LedgerEntry> entries) {
        return entries.stream()
                .map(LedgerEntry::signedCredits)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Net of every entry that is not cancelled by a reversal.
     *
     * <p>Unlike {@link #balanceOf}, this ignores both an entry and its reversal, which is what a
     * "gross movement" report needs.
     */
    public static BigDecimal netOfReversible(java.util.List<LedgerEntry> entries) {
        var reversedIds = entries.stream()
                .map(LedgerEntry::reversesEntryId)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(HashSet::new, Set::add, Set::addAll);
        return entries.stream()
                .filter(e -> e.type() != LedgerEntryType.REVERSAL)
                .filter(e -> !reversedIds.contains(e.entryId()))
                .map(LedgerEntry::signedCredits)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}