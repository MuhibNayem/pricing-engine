package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.DrawdownTransaction;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enterprise wallet drawdown engine.
 * Implements FIFO expiration-first and priority-ordered credit burn,
 * tracking immutable transactions and returning zero-drift remaining cash invoices.
 */
public final class WalletDrawdownEngine {

    public WalletDrawdownResult applyDrawdown(
        Wallet wallet,
        String calculationId,
        Money invoiceAmount,
        Instant evalTime
    ) {
        Objects.requireNonNull(wallet, "wallet cannot be null");
        Objects.requireNonNull(calculationId, "calculationId cannot be null");
        Objects.requireNonNull(invoiceAmount, "invoiceAmount cannot be null");
        Objects.requireNonNull(evalTime, "evalTime cannot be null");

        if (invoiceAmount.isZero() || invoiceAmount.isNegative()) {
            return new WalletDrawdownResult(
                wallet.walletId(),
                invoiceAmount,
                BigDecimal.ZERO,
                Money.zero(invoiceAmount.currency()),
                invoiceAmount,
                List.of(),
                wallet
            );
        }

        // Comparator: Expiring earliest first; then priority ascending; then grantId
        Comparator<CreditGrant> burnOrder = Comparator
            .comparing((CreditGrant g) -> g.expiresAt().orElse(Instant.MAX))
            .thenComparingInt(CreditGrant::priority)
            .thenComparing(CreditGrant::grantId);

        List<CreditGrant> allGrants = new ArrayList<>(wallet.grants());
        List<CreditGrant> activeGrants = allGrants.stream()
            .filter(g -> g.isActiveAt(evalTime))
            .sorted(burnOrder)
            .toList();

        Money remainingCashDue = invoiceAmount;
        BigDecimal totalCreditsDrawn = BigDecimal.ZERO;
        Money totalMoneyDrawn = Money.zero(invoiceAmount.currency());
        List<DrawdownTransaction> transactions = new ArrayList<>();

        for (CreditGrant grant : activeGrants) {
            if (remainingCashDue.isZero()) {
                break;
            }

            BigDecimal rate = grant.creditToMoneyRate();
            Money grantMoneyValue = grant.moneyValueOf(grant.remainingCredits(), invoiceAmount.currency());

            BigDecimal creditsToDraw;
            Money moneyToDraw;

            if (grantMoneyValue.compareTo(remainingCashDue) <= 0) {
                // Exhaust entire grant
                creditsToDraw = grant.remainingCredits();
                moneyToDraw = grantMoneyValue;
            } else {
                // Partial deduction
                moneyToDraw = remainingCashDue;
                creditsToDraw = remainingCashDue.amount().divide(rate, 8, RoundingMode.HALF_EVEN);
                // Clamp to not exceed remaining credits due to rounding
                creditsToDraw = creditsToDraw.min(grant.remainingCredits());
            }

            CreditGrant updatedGrant = grant.deduct(creditsToDraw);
            // Replace in allGrants
            int idx = allGrants.indexOf(grant);
            allGrants.set(idx, updatedGrant);

            totalCreditsDrawn = totalCreditsDrawn.add(creditsToDraw);
            totalMoneyDrawn = totalMoneyDrawn.plus(moneyToDraw);
            remainingCashDue = remainingCashDue.minus(moneyToDraw);

            transactions.add(new DrawdownTransaction(
                UUID.randomUUID().toString(),
                wallet.walletId(),
                grant.grantId(),
                grant.name(),
                calculationId,
                Optional.empty(),
                creditsToDraw,
                moneyToDraw,
                updatedGrant.remainingCredits(),
                evalTime
            ));
        }

        Wallet updatedWallet = wallet.withGrants(allGrants);

        return new WalletDrawdownResult(
            wallet.walletId(),
            invoiceAmount,
            totalCreditsDrawn,
            totalMoneyDrawn,
            remainingCashDue.roundToCurrency(),
            transactions,
            updatedWallet
        );
    }
}
