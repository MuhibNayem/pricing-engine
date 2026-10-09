package com.saas.pricing.core.model.retention;

import java.util.EnumSet;
import java.util.Set;

/**
 * What may physically be done to each class of record.
 *
 * <p>This exists because a retention <em>decision</em> and a retention <em>capability</em> are
 * different things. The financial ledger and the entitlement stream are append-only: their tables
 * reject {@code DELETE} at the database level, by design, because "why did this customer lose
 * access on 3 March" is only answerable if March is still there. So an erasure instruction against
 * them is downgraded to anonymisation rather than attempted and failed.
 *
 * <p>Declaring the capability up front is what keeps that downgrade a visible, reportable outcome
 * instead of an exception swallowed somewhere in a job.
 */
public final class RetentionCapabilities {

    private RetentionCapabilities() {
        // static utility
    }

    /**
     * Classes whose rows may be deleted outright once retention expires.
     *
     * <p>Only classes with no statutory hold and no audit value. Everything else falls through to
     * anonymisation or retention.
     */
    private static final Set<RetentionClass.RecordClass> ERASABLE =
        EnumSet.of(RetentionClass.RecordClass.DIAGNOSTIC, RetentionClass.RecordClass.USAGE_TELEMETRY);

    /** Classes whose rows are protected by an append-only constraint and must never be deleted. */
    private static final Set<RetentionClass.RecordClass> APPEND_ONLY =
        EnumSet.of(RetentionClass.RecordClass.FINANCIAL_LEDGER,
                   RetentionClass.RecordClass.ENTITLEMENT_EVENTS,
                   RetentionClass.RecordClass.AUDIT_TRAIL,
                   RetentionClass.RecordClass.CONTRACT_HISTORY);

    public static boolean isErasable(RetentionClass.RecordClass recordClass) {
        return ERASABLE.contains(recordClass);
    }

    public static boolean isAppendOnly(RetentionClass.RecordClass recordClass) {
        return APPEND_ONLY.contains(recordClass);
    }

    /**
     * Resolves what will actually be done, given what the policy asked for.
     *
     * @return the outcome that will be performed, and whether it differs from the plan
     */
    public record Resolution(ErasureDecision.Outcome performed, boolean downgraded, String note) {
    }

    public static Resolution resolve(RetentionClass.RecordClass recordClass,
                                     ErasureDecision.Outcome planned) {
        if (planned != ErasureDecision.Outcome.ERASE) {
            return new Resolution(planned, false, switch (planned) {
                case RETAIN -> "Retained under its statutory basis";
                case ANONYMISE -> "Personal data stripped; financial content kept";
                case ERASE -> "Erased";
            });
        }
        if (isErasable(recordClass)) {
            return new Resolution(ErasureDecision.Outcome.ERASE, false, "Erased; no statutory hold");
        }
        if (isAppendOnly(recordClass)) {
            return new Resolution(ErasureDecision.Outcome.ANONYMISE, true,
                "Erasure downgraded to anonymisation: this store is append-only and deleting the row "
                    + "would destroy the evidence a tax authority or court requires");
        }
        return new Resolution(ErasureDecision.Outcome.ANONYMISE, true,
            "Erasure downgraded to anonymisation: the store does not permit in-place deletion");
    }
}