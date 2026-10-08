package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Result of executing wallet credit drawdown against an invoice balance.
 */
public record WalletDrawdownResult(
    String walletId,
    Money originalInvoiceAmount,
    BigDecimal totalCreditsDrawn,
    Money totalCreditMoneyValue,
    Money remainingInvoiceDue,
    List<DrawdownTransaction> transactions,
    Wallet updatedWallet
) implements Serializable {

    public WalletDrawdownResult {
        Objects.requireNonNull(walletId, "walletId cannot be null");
        Objects.requireNonNull(originalInvoiceAmount, "originalInvoiceAmount cannot be null");
        Objects.requireNonNull(totalCreditsDrawn, "totalCreditsDrawn cannot be null");
        Objects.requireNonNull(totalCreditMoneyValue, "totalCreditMoneyValue cannot be null");
        Objects.requireNonNull(remainingInvoiceDue, "remainingInvoiceDue cannot be null");
        Objects.requireNonNull(transactions, "transactions cannot be null");
        Objects.requireNonNull(updatedWallet, "updatedWallet cannot be null");
        transactions = List.copyOf(transactions);
    }

    public boolean isFullyCoveredByCredits() {
        return remainingInvoiceDue.isZero();
    }
}
