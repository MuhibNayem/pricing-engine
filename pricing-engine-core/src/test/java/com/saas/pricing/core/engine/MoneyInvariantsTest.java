package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.TaxRate;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money-integrity invariants.
 *
 * <p>Each test here corresponds to a defect that produced wrong money in production: penny
 * rounding drift on credit notes, VAT charged on pre-discount amounts, and cross-currency wallet
 * settlement at 1:1. They are written as invariants rather than single examples so that future
 * changes to the pricing pipeline cannot silently reintroduce the class of bug.
 */
class MoneyInvariantsTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;

    private static BigDecimal sumOf(List<Money> amounts) {
        return amounts.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    @DisplayName("RemainderAllocator conservation")
    class AllocationConservation {

        private void assertConserves(String total, List<BigDecimal> weights) {
            Money totalMoney = new Money(new BigDecimal(total), USD);
            List<Money> shares = RemainderAllocator.allocate(totalMoney, weights);
            assertThat(shares).hasSameSizeAs(weights);
            assertThat(sumOf(shares).setScale(2))
                    .as("allocations of %s must sum to exactly %s", total, total)
                    .isEqualByComparingTo(new BigDecimal(total).setScale(2));
        }

        @Test
        @DisplayName("proportional split of a positive total conserves the total")
        void positiveTotalConserved() {
            assertConserves("10.00", List.of(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300")));
        }

        @Test
        @DisplayName("credit notes and refunds (negative totals) lose no money")
        void negativeTotalConserved() {
            // Regression: truncation used RoundingMode.DOWN (toward zero), which made remainders
            // negative and left the distribution loop with nothing to hand out.
            assertConserves("-10.00", List.of(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300")));
            assertConserves("-1.00", List.of(BigDecimal.ONE, BigDecimal.ONE));
            assertConserves("-1000.01", Collections.nCopies(7, BigDecimal.ONE));
            assertConserves("-3.17", Collections.nCopies(200, BigDecimal.ONE));
        }

        @Test
        @DisplayName("conserves when the recipient count exceeds the minor-unit precision")
        void moreRecipientsThanMinorUnits() {
            assertConserves("1.00", Collections.nCopies(200, BigDecimal.ONE));
            assertConserves("0.01", Collections.nCopies(200, BigDecimal.ONE));
        }

        @Test
        @DisplayName("a zero-weight vector splits evenly instead of discarding the amount")
        void allZeroWeightsSplitEvenly() {
            // Regression: the amount used to be silently zeroed out.
            assertConserves("10.00", List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        @Test
        @DisplayName("zero-decimal currencies such as JPY conserve exactly")
        void zeroDecimalCurrencyConserved() {
            var shares = RemainderAllocator.allocate(
                    new Money(new BigDecimal("100"), CurrencyUnit.JPY),
                    List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
            assertThat(sumOf(shares)).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("allocation is deterministic for identical inputs")
        void allocationIsDeterministic() {
            var weights = List.of(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"));
            var first = RemainderAllocator.allocate(new Money(new BigDecimal("10.00"), USD), weights);
            var second = RemainderAllocator.allocate(new Money(new BigDecimal("10.00"), USD), weights);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("conserves across 20,000 randomized totals, weights and recipient counts")
        void randomizedConservation() {
            Random random = new Random(20261008L);
            for (int trial = 0; trial < 20_000; trial++) {
                int recipients = 1 + random.nextInt(60);
                List<BigDecimal> weights = new ArrayList<>(recipients);
                for (int i = 0; i < recipients; i++) {
                    weights.add(BigDecimal.valueOf(random.nextInt(5_000)));
                }
                long cents = random.nextInt(4_000_001) - 2_000_000L;
                String total = BigDecimal.valueOf(cents, 2).toPlainString();

                List<Money> shares =
                        RemainderAllocator.allocate(new Money(new BigDecimal(total), USD), weights);
                assertThat(sumOf(shares).setScale(2))
                        .as("randomized trial %d: total=%s recipients=%d", trial, total, recipients)
                        .isEqualByComparingTo(new BigDecimal(total).setScale(2));
            }
        }

        @Test
        @DisplayName("rejects inputs that would silently lose money")
        void rejectsUnrepresentableInput() {
            assertThatThrownBy(() -> RemainderAllocator.allocate(
                    new Money(new BigDecimal("10.005"), USD), List.of(BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more precision");

            assertThatThrownBy(() -> RemainderAllocator.allocate(
                    new Money(new BigDecimal("10.00"), USD), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> RemainderAllocator.allocate(
                    new Money(new BigDecimal("10.00"), USD), List.of(new BigDecimal("-5"), BigDecimal.ONE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    @Nested
    @DisplayName("Tax follows the amount actually charged")
    class TaxOnDiscountedBase {

        private DefaultPricingEngine engineWithFlatTenPercentVat(String itemCode, String lineAmount) {
            var now = Instant.parse("2026-10-08T12:00:00Z");
            var item = RatePlanItem.of(itemCode, "m",
                    PricingModel.FlatFeeModel.of(new Money(new BigDecimal(lineAmount), USD), BillingCadence.MONTHLY),
                    USD);
            var repo = new InMemoryRateCardRepository();
            repo.save(RateCard.of("rc", TenantId.of("t1"), PlanCode.of("PRO"), 1, now, List.of(item)));
            TaxProvider vat = (tenant, code, at) -> List.of(TaxRate.of("VAT10", new BigDecimal("10"), "CA"));
            return new DefaultPricingEngine(repo, (f, t, ts) -> BigDecimal.ONE, vat, r -> { }, null);
        }

        @Test
        @DisplayName("an invoice-level discount reduces the tax proportionally")
        void invoiceDiscountReducesTax() {
            // Regression: tax was computed on the pre-discount net, over-charging VAT by the full
            // rate on the discounted portion ($10.00 instead of the correct $8.00).
            var engine = engineWithFlatTenPercentVat("BASE_SUB", "100.00");
            var request = PricingRequest.builder()
                    .tenantId("t1").planCode("PRO")
                    .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
                    .targetCurrency(USD)
                    .item("BASE_SUB", 1)
                    .discount(Discount.fixedAmount("COUPON", new Money(new BigDecimal("20.00"), USD)))
                    .build();

            var result = engine.evaluate(request);

            assertThat(result.totalNet().amount()).isEqualByComparingTo("80.00");
            assertThat(result.totalTax().amount()).isEqualByComparingTo("8.00");
            assertThat(result.finalTotal().amount()).isEqualByComparingTo("88.00");
        }

        @Test
        @DisplayName("invoice total always equals net plus tax")
        void finalTotalEqualsNetPlusTax() {
            var engine = engineWithFlatTenPercentVat("BASE_SUB", "49.00");
            for (String coupon : new String[] { "0.00", "1.00", "7.77", "49.00" }) {
                var request = PricingRequest.builder()
                        .tenantId("t1").planCode("PRO")
                        .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
                        .targetCurrency(USD)
                        .item("BASE_SUB", 1)
                        .discount(Discount.fixedAmount("C", new Money(new BigDecimal(coupon), USD)))
                        .build();
                var result = engine.evaluate(request);
                assertThat(result.finalTotal().amount())
                        .as("coupon %s", coupon)
                        .isEqualByComparingTo(result.totalNet().amount().add(result.totalTax().amount()));
            }
        }

        @Test
        @DisplayName("line taxes sum to the invoice tax")
        void lineTaxesSumToInvoiceTax() {
            var engine = engineWithFlatTenPercentVat("BASE_SUB", "49.00");
            var request = PricingRequest.builder()
                    .tenantId("t1").planCode("PRO")
                    .evaluationTime(Instant.parse("2026-10-08T12:00:00Z"))
                    .targetCurrency(USD)
                    .item("BASE_SUB", 1)
                    .discount(Discount.fixedAmount("C", new Money(new BigDecimal("7.00"), USD)))
                    .build();
            var result = engine.evaluate(request);

            BigDecimal lineTaxSum = result.lineItems().stream()
                    .map(li -> li.taxAmount().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(lineTaxSum).isEqualByComparingTo(result.totalTax().amount());
        }
    }

    @Nested
    @DisplayName("Wallet settlement")
    class WalletSettlement {

        private Wallet usdWallet(Instant now) {
            var grant = CreditGrant.prepaid("g1", "w1", "Prepaid", new BigDecimal("100.00"), BigDecimal.ONE, now);
            return Wallet.of("w1", TenantId.of("t1"), CustomerId.of("c1"), USD, List.of(grant));
        }

        @Test
        @DisplayName("refuses to settle a foreign-currency invoice")
        void refusesCrossCurrencySettlement() {
            // Regression: a USD wallet settled a EUR 50.00 invoice by spending 50 USD of credit,
            // valuing it 1:1 and reporting the invoice fully covered.
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            var engine = new WalletDrawdownEngine();

            assertThatThrownBy(() -> engine.applyDrawdown(
                    usdWallet(now), "calc", new Money(new BigDecimal("50.00"), CurrencyUnit.EUR), now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USD").hasMessageContaining("EUR");
        }

        @Test
        @DisplayName("same-currency drawdown still settles and reports zero remaining")
        void sameCurrencySettles() {
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            var engine = new WalletDrawdownEngine();

            var result = engine.applyDrawdown(
                    usdWallet(now), "calc", new Money(new BigDecimal("50.00"), USD), now);

            assertThat(result.remainingInvoiceDue().isZero()).isTrue();
            assertThat(result.totalCreditMoneyValue().amount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("credit value equals credits drawn times the grant's credit-to-money rate")
        void creditValueMatchesRate() {
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            // 2 credits = 1 USD
            var grant = CreditGrant.prepaid("g1", "w1", "Credits", new BigDecimal("200.00"),
                    new BigDecimal("0.5"), now);
            var wallet = Wallet.of("w1", TenantId.of("t1"), CustomerId.of("c1"), USD, List.of(grant));
            var engine = new WalletDrawdownEngine();

            var result = engine.applyDrawdown(
                    wallet, "calc", new Money(new BigDecimal("10.00"), USD), now);

            assertThat(result.totalCreditsDrawn().multiply(new BigDecimal("0.5")))
                    .isEqualByComparingTo(result.totalCreditMoneyValue().amount());
        }

        @Test
        @DisplayName("credits stay at the currency's scale and reconcile with cash")
        void creditsReconcileAtCurrencyScale() {
            // Regression: credits were divided at a fixed scale of 8 while the money they were
            // worth sat at scale 2, so a grant balance no longer round-tripped through
            // credits x rate and reconciliation against a cash ledger drifted.
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            var grant = CreditGrant.prepaid("g1", "w1", "Prepaid", new BigDecimal("100.00"), BigDecimal.ONE, now);
            var wallet = Wallet.of("w1", TenantId.of("t1"), CustomerId.of("c1"), USD, List.of(grant));

            var result = new WalletDrawdownEngine()
                .applyDrawdown(wallet, "calc", new Money(new BigDecimal("10.00"), USD), now);

            assertThat(result.totalCreditsDrawn().scale()).isEqualTo(2);
            assertThat(result.updatedWallet().grants().getFirst().remainingCredits().scale()).isEqualTo(2);
            assertThat(result.totalCreditsDrawn()
                .add(result.updatedWallet().grants().getFirst().remainingCredits()))
                .as("drawn plus remaining must equal the original grant")
                .isEqualByComparingTo("100.00");
            assertThat(result.totalCreditsDrawn().multiply(BigDecimal.ONE))
                .as("credits x rate must equal the money drawn")
                .isEqualByComparingTo(result.totalCreditMoneyValue().amount());
        }

        @Test
        @DisplayName("six-decimal token currencies keep their own scale")
        void tokenCurrencyKeepsSixDecimals() {
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            var grant = CreditGrant.prepaid("g1", "w1", "Tokens", new BigDecimal("1000"), BigDecimal.ONE, now);
            var wallet = Wallet.of("w1", TenantId.of("t1"), CustomerId.of("c1"), CurrencyUnit.TOKENS, List.of(grant));

            var result = new WalletDrawdownEngine()
                .applyDrawdown(wallet, "calc", new Money(new BigDecimal("10.123456"), CurrencyUnit.TOKENS), now);

            assertThat(result.totalCreditsDrawn().scale()).isEqualTo(6);
            assertThat(result.remainingInvoiceDue().isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("Bi-temporal reproducibility")
    class BiTemporalResolution {

        private com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel level() {
            return com.saas.pricing.core.model.hierarchy.CatalogHierarchyLevel.ACCOUNT_DEFAULT;
        }

        private RateCard backdatedCard(String id, int version, Instant effectiveFrom, Instant recordedAt,
                                       java.util.Optional<Instant> supersededAt, String unitPrice) {
            var item = RatePlanItem.of("SEATS", "seats",
                    PricingModel.PerUnitModel.of(new BigDecimal(unitPrice)), USD);
            return new RateCard(id, TenantId.of("t1"), PlanCode.of("PRO"), version, effectiveFrom,
                    Optional.empty(), recordedAt, supersededAt, level(), List.of(item), Map.of());
        }

        @Test
        @DisplayName("as-of resolution returns the price in effect at issuance, not today's price")
        void asOfReproducesHistoricalRating() {
            // Regression: DefaultPricingEngine hardcoded Optional.empty() for system time, so a
            // historical rating could never be reproduced and the advertised ASC 606 / SOX
            // auditability was unreachable by any caller.
            var jan = Instant.parse("2026-01-01T00:00:00Z");
            var mar = Instant.parse("2026-03-01T00:00:00Z");
            var jun = Instant.parse("2026-06-01T00:00:00Z");
            var usageInApril = Instant.parse("2026-04-15T00:00:00Z");
            var knownInMay = Instant.parse("2026-05-15T00:00:00Z");

            var repo = new com.saas.pricing.core.spi.impl.InMemoryRateCardRepository();
            repo.save(backdatedCard("rc-v1", 1, jan, jan, Optional.of(jun), "1.00"));
            // v2 is backdated: valid from March, but only recorded in June.
            repo.save(backdatedCard("rc-v2", 2, mar, jun, Optional.empty(), "2.00"));

            var engine = new DefaultPricingEngine(repo, (f, t, ts) -> BigDecimal.ONE,
                (t, c, at) -> List.of(), r -> { }, null);

            var asKnownNow = engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("PRO")
                .evaluationTime(usageInApril).targetCurrency(USD).item("SEATS", 10)
                .build());
            var asKnownThen = engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("PRO")
                .evaluationTime(usageInApril).targetCurrency(USD).item("SEATS", 10)
                .systemTime(knownInMay)
                .build());

            assertThat(asKnownNow.finalTotal().amount())
                .as("priced with today's knowledge, v2 @ $2.00")
                .isEqualByComparingTo("20.00");
            assertThat(asKnownThen.finalTotal().amount())
                .as("priced as known in May, before v2 was recorded, so v1 @ $1.00")
                .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("omitting as-of preserves the previous default behaviour")
        void asOfDefaultsToNow() {
            var jan = Instant.parse("2026-01-01T00:00:00Z");
            var usage = Instant.parse("2026-04-15T00:00:00Z");
            var repo = new com.saas.pricing.core.spi.impl.InMemoryRateCardRepository();
            repo.save(backdatedCard("rc-v1", 1, jan, jan, Optional.empty(), "3.00"));

            var engine = new DefaultPricingEngine(repo, (f, t, ts) -> BigDecimal.ONE,
                (t, c, at) -> List.of(), r -> { }, null);

            var result = engine.evaluate(PricingRequest.builder()
                .tenantId("t1").planCode("PRO")
                .evaluationTime(usage).targetCurrency(USD).item("SEATS", 10)
                .build());

            assertThat(result.finalTotal().amount()).isEqualByComparingTo("30.00");
        }
    }

    @Nested
    @DisplayName("Wallet drawdown under concurrency")
    class WalletConcurrency {

        @Test
        @DisplayName("concurrent atomic drawdowns never lose an update")
        void concurrentAtomicDrawdownsConserveCredit() throws Exception {
            // Regression: findWallet + save is a blind last-write-wins cycle. 50 concurrent $10
            // drawdowns against a $100 wallet each read $100, each deducted, and only the last
            // write survived - $500 of consumption recorded against a wallet still showing $90.
            Instant now = Instant.parse("2026-10-08T00:00:00Z");
            var tenantId = TenantId.of("t1");
            var customerId = CustomerId.of("c1");
            var engine = new WalletDrawdownEngine();

            var grant = CreditGrant.prepaid("g1", "w1", "Prepaid", new BigDecimal("100.00"), BigDecimal.ONE, now);
            var repository = new com.saas.pricing.core.spi.impl.InMemoryWalletRepository();
            repository.save(Wallet.of("w1", tenantId, customerId, USD, List.of(grant)));

            int threads = 50;
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        repository.updateAtomically(tenantId, customerId, wallet -> {
                            var result = engine.applyDrawdown(
                                    wallet, "calc", new Money(new BigDecimal("10.00"), USD), now);
                            return result.updatedWallet();
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
            pool.shutdown();

            BigDecimal remaining = repository.findWallet(tenantId, customerId)
                    .orElseThrow().totalRemainingCredits(now);
            assertThat(remaining)
                    .as("a $100 wallet must be fully debitable by concurrent $10 drawdowns")
                    .isLessThanOrEqualTo(BigDecimal.ZERO);
        }
    }
}