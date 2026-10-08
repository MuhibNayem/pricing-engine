package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable audit record of a credit deduction from a specific grant.
 */
public record DrawdownTransaction(
    String transactionId,
    String walletId,
    String grantId,
    String grantName,
    String calculationId,
    Optional<String> lineItemCode,
    BigDecimal creditsDrawn,
    Money moneyAmountDrawn,
    BigDecimal remainingGrantCredits,
    Instant timestamp
) implements Serializable {

    public DrawdownTransaction {
        Objects.requireNonNull(transactionId, "transactionId cannot be null");
        Objects.requireNonNull(walletId, "walletId cannot be null");
        Objects.requireNonNull(grantId, "grantId cannot be null");
        Objects.requireNonNull(grantName, "grantName cannot be null");
        Objects.requireNonNull(calculationId, "calculationId cannot be null");
        Objects.requireNonNull(lineItemCode, "lineItemCode cannot be null");
        Objects.requireNonNull(creditsDrawn, "creditsDrawn cannot be null");
        Objects.requireNonNull(moneyAmountDrawn, "moneyAmountDrawn cannot be null");
        Objects.requireNonNull(remainingGrantCredits, "remainingGrantCredits cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }
}
