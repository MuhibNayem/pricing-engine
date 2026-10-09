package com.saas.pricing.core.model.retention;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * How long a class of record must be kept, and why.
 *
 * <h2>Why retention cannot be a blanket rule</h2>
 * GDPR grants a right to erasure (Art. 17(1)) but carves out exceptions in Art. 17(3), including
 * processing necessary <strong>to comply with a legal obligation</strong> (b), for
 * <strong>establishment, exercise or defence of legal claims</strong> (d), and for
 * <strong>legal claims</strong> (e). A financial ledger under statutory record-keeping duties is
 * squarely inside those exceptions.
 *
 * <p>So "delete everything for this customer" is the <em>wrong</em> default for financial records:
 * it would destroy the very evidence a tax authority or a court requires you to keep. The correct
 * behaviour is selective, by record class and by date, which is what this type exists to express.
 *
 * <p>An append-only ledger makes erasure <em>harder</em>, not easier — which is exactly why the
 * decision has to be modelled deliberately rather than emerging from a {@code DELETE}.
 *
 * @param recordClass     what kind of record this governs
 * @param minimumRetention statutory minimum time the record must be kept
 * @param legalBasis      why it is kept, for the article 30 record of processing activities
 * @param erasableAfter   when personal data may be anonymised even though the financial record is kept
 */
public record RetentionClass(
    RecordClass recordClass,
    Duration minimumRetention,
    LegalBasis legalBasis,
    Optional<Duration> erasableAfter
) implements Serializable {

    /** The class of record a retention rule governs. */
    public enum RecordClass {
        /** Invoice, credit note and ledger postings. Statutorily required. */
        FINANCIAL_LEDGER,
        /** Metered usage events and their aggregations. */
        USAGE_TELEMETRY,
        /** Entitlement grants and revocations. */
        ENTITLEMENT_EVENTS,
        /** Rating traces and evaluation results. */
        AUDIT_TRAIL,
        /** Contract and rate-card history. */
        CONTRACT_HISTORY,
        /** Operational diagnostics with no financial meaning. */
        DIAGNOSTIC
    }

    /**
     * The article 30 record-of-processing basis for keeping the data.
     *
     * <p>Referencing the specific exception rather than a generic "legitimate interest" is what
     * makes an erasure request answerable.
     */
    public enum LegalBasis {
        /** GDPR Art. 17(3)(b) — compliance with a legal obligation, e.g. a tax statute. */
        LEGAL_OBLIGATION,
        /** GDPR Art. 17(3)(d)/(e) — establishment, exercise or defence of legal claims. */
        LEGAL_CLAIMS,
        /** GDPR Art. 6(1)(f) — legitimate interests, e.g. fraud prevention. */
        LEGITIMATE_INTEREST,
        /** No statutory basis; ordinary operational retention only. */
        OPERATIONAL_ONLY
    }

    public RetentionClass {
        Objects.requireNonNull(recordClass, "recordClass cannot be null");
        Objects.requireNonNull(minimumRetention, "minimumRetention cannot be null");
        Objects.requireNonNull(legalBasis, "legalBasis cannot be null");
        Objects.requireNonNull(erasableAfter, "erasableAfter cannot be null");

        if (minimumRetention.isNegative() || minimumRetention.isZero()) {
            throw new IllegalArgumentException("minimumRetention must be positive");
        }
        erasableAfter.ifPresent(period -> {
            if (period.compareTo(minimumRetention) < 0) {
                throw new IllegalArgumentException(
                    "erasableAfter " + period + " is before minimumRetention " + minimumRetention
                        + "; a record cannot be anonymised before it must be kept");
            }
        });
    }

    /** True when the statutory retention period for a record created at {@code createdAt} has expired. */
    public boolean retentionExpired(Instant createdAt, Instant now) {
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        return !now.isBefore(createdAt.plus(minimumRetention));
    }

    /** True when personal data may be anonymised while the financial record is still kept. */
    public boolean erasable(Instant createdAt, Instant now) {
        return erasableAfter
            .map(period -> !now.isBefore(createdAt.plus(period)))
            .orElse(false);
    }

    /** True when this class may never be erased on an erasure request, only anonymised. */
    public boolean isStatutorilyRetained() {
        return legalBasis == LegalBasis.LEGAL_OBLIGATION || legalBasis == LegalBasis.LEGAL_CLAIMS;
    }
}