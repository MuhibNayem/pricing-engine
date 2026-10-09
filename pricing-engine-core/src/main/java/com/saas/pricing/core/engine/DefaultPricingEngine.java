package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.BillingPeriod;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.DiscountScope;
import com.saas.pricing.core.model.DiscountType;
import com.saas.pricing.core.model.EvaluationTrace;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.RatedLineItem;
import com.saas.pricing.core.model.TaxRate;
import com.saas.pricing.core.model.TraceStep;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;

import com.saas.pricing.core.model.wallet.SpendCommitment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enterprise default implementation of the SaaS PricingEngine.
 * Integrates hierarchical rate resolution, high-precision proration,
 * zero-drift discount distribution, multi-jurisdiction taxes, and spend commitments.
 */
public final class DefaultPricingEngine implements PricingEngine {

    private final RateCardRepository rateCardRepository;
    private final HierarchicalRateCardResolver hierarchyResolver;
    private final CurrencyExchangeProvider currencyExchangeProvider;
    private final TaxProvider taxProvider;
    private final AuditSink auditSink;
    private final FormulaExpressionEvaluator formulaEvaluator;
    private final ModelEvaluator modelEvaluator;
    private final DiscountEngine discountEngine;

    public DefaultPricingEngine(
        RateCardRepository rateCardRepository,
        CurrencyExchangeProvider currencyExchangeProvider,
        TaxProvider taxProvider,
        AuditSink auditSink,
        FormulaExpressionEvaluator formulaEvaluator
    ) {
        this(rateCardRepository, new HierarchicalRateCardResolver(rateCardRepository, null),
            currencyExchangeProvider, taxProvider, auditSink, formulaEvaluator);
    }

    public DefaultPricingEngine(
        RateCardRepository rateCardRepository,
        HierarchicalRateCardResolver hierarchyResolver,
        CurrencyExchangeProvider currencyExchangeProvider,
        TaxProvider taxProvider,
        AuditSink auditSink,
        FormulaExpressionEvaluator formulaEvaluator
    ) {
        this.rateCardRepository = Objects.requireNonNull(rateCardRepository, "rateCardRepository cannot be null");
        this.hierarchyResolver = hierarchyResolver != null ? hierarchyResolver : new HierarchicalRateCardResolver(rateCardRepository, null);
        this.currencyExchangeProvider = Objects.requireNonNull(currencyExchangeProvider, "currencyExchangeProvider cannot be null");
        this.taxProvider = Objects.requireNonNull(taxProvider, "taxProvider cannot be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink cannot be null");
        this.formulaEvaluator = formulaEvaluator; // Optional
        this.modelEvaluator = new ModelEvaluator();
        this.discountEngine = new DiscountEngine();
    }

    @Override
    public PricingResult evaluate(PricingRequest request) {
        Objects.requireNonNull(request, "PricingRequest cannot be null");

        String calculationId = UUID.randomUUID().toString();
        Instant evalTime = request.evaluationTime().orElseGet(Instant::now);
        EvaluationTrace trace = new EvaluationTrace(calculationId, evalTime, new ArrayList<>());

        trace.addStep("REQUEST_RECEIVED", "Evaluating plan '%s' for tenant '%s'".formatted(
            request.planCode().value(), request.tenantId().value()
        ));

        // Hierarchical Rate Card Resolution
        var resolvedHierarchy = hierarchyResolver.resolve(
            request.tenantId(),
            request.customerId(),
            request.planCode(),
            evalTime,
            request.systemTime()
        );
        RateCard rateCard = resolvedHierarchy.effectiveRateCard();
        // Contract overrides are added first so that, at equal priority, a negotiated term outranks
        // an ad-hoc request coupon rather than the other way round.
        List<Discount> effectiveDiscounts = new ArrayList<>(resolvedHierarchy.customDiscounts());
        effectiveDiscounts.addAll(request.discounts());
        Optional<SpendCommitment> spendCommitment = resolvedHierarchy.spendCommitment();

        trace.addStep("RATE_CARD_RESOLVED", "Resolved RateCard '%s' version %d via %s (overrides=%s)".formatted(
            rateCard.rateCardId(), rateCard.version(), resolvedHierarchy.sourceHierarchyLevel(), resolvedHierarchy.overriddenItemCodes()
        ));

        CurrencyUnit targetCurrency = request.targetCurrency();
        List<RatedLineItem> lineItems = new ArrayList<>();
        // Tax rates resolved per line item, kept in step with lineItems so that tax can be
        // recomputed if an invoice-level discount is later apportioned onto that line.
        List<List<TaxRate>> lineTaxRates = new ArrayList<>();

        Money grossAccumulator = Money.zero(targetCurrency);
        Money lineDiscountAccumulator = Money.zero(targetCurrency);
        Money netAccumulator = Money.zero(targetCurrency);
        Money taxAccumulator = Money.zero(targetCurrency);

        // 1. Process Line Items
        for (BillableItemRequest itemReq : request.items()) {
            RatePlanItem planItem = rateCard.findItem(itemReq.itemCode())
                .orElseThrow(() -> new NoSuchElementException(
                    "Item code '%s' is not defined in RateCard '%s'".formatted(
                        itemReq.itemCode(), rateCard.rateCardId()
                    )
                ));

            BigDecimal billableQty = planItem.computeBillableQuantity(itemReq.quantity());

            // Merge global and item attributes
            Map<String, Object> combinedAttrs = new HashMap<>(request.globalAttributes());
            combinedAttrs.putAll(itemReq.attributes());

            // Evaluate Gross Amount using ModelEvaluator
            var outcome = modelEvaluator.evaluate(
                planItem.pricingModel(),
                billableQty,
                planItem.baseCurrency(),
                combinedAttrs,
                formulaEvaluator
            );

            Money grossItemAmount = outcome.grossAmount();
            List<TraceStep> itemTrace = new ArrayList<>(outcome.traceSteps());

            // A flat fee is an amount for ONE period. Without this check an annual plan's rate and a
            // monthly plan's rate are indistinguishable to the engine, so a $4,988/year card and a
            // $499/month card price identically on every evaluation. Declaring the cadence in the
            // request is what makes FlatFeeModel.cadence() mean anything.
            verifyFlatFeeCadence(planItem, request, itemTrace);

            BigDecimal prorationFactor = planItem.proratable() && request.prorationWindow().isPresent()
                ? request.prorationWindow().get().calculateFactor()
                : null;
            BigDecimal fxRate = planItem.baseCurrency().equals(targetCurrency)
                ? null
                : currencyExchangeProvider.getExchangeRate(planItem.baseCurrency(), targetCurrency, evalTime);

            grossItemAmount = proratedAndConverted(grossItemAmount, prorationFactor, fxRate, targetCurrency);
            if (prorationFactor != null) {
                itemTrace.add(TraceStep.of("PRORATION_APPLIED", "Proration factor %s applied: new gross = %s".formatted(prorationFactor, grossItemAmount)));
            }
            if (fxRate != null) {
                itemTrace.add(TraceStep.of("FX_CONVERSION", "Converted from %s to %s at rate %s: %s".formatted(
                    planItem.baseCurrency().code(), targetCurrency.code(), fxRate, grossItemAmount
                )));
            }

            // Filter and apply item-level discounts
            List<Discount> itemDiscounts = effectiveDiscounts.stream()
                .filter(d -> d.scope() == DiscountScope.LINE_ITEM)
                .filter(d -> d.targetItemCode().map(code -> code.equalsIgnoreCase(itemReq.itemCode())).orElse(false))
                .toList();

            if (grossItemAmount.isNegative()) {
                // A model (typically a dynamic formula) produced a credit. Letting it through would
                // later fail the remainder allocator or silently reduce the invoice.
                throw new IllegalArgumentException(
                    "Item '%s' rated to a negative amount %s; a rating cannot produce a negative charge"
                        .formatted(itemReq.itemCode(), grossItemAmount));
            }

            // FREE_UNITS removes quantity, not money: it is priced by re-evaluating the model for
            // the reduced quantity, so a tiered or formula model gives the same answer it would if
            // the customer had simply used fewer units.
            BigDecimal freeUnits = itemDiscounts.stream()
                .filter(d -> d.type() == DiscountType.FREE_UNITS)
                .map(Discount::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            Money freeUnitDiscount = Money.zero(targetCurrency);
            if (freeUnits.signum() > 0 && billableQty.signum() > 0) {
                BigDecimal chargeableQty = billableQty.subtract(freeUnits).max(BigDecimal.ZERO);
                var chargeableOutcome = modelEvaluator.evaluate(
                    planItem.pricingModel(), chargeableQty, planItem.baseCurrency(), combinedAttrs, formulaEvaluator);
                Money chargeableGross = proratedAndConverted(
                    chargeableOutcome.grossAmount(), prorationFactor, fxRate, targetCurrency);
                freeUnitDiscount = grossItemAmount.minus(chargeableGross)
                    .max(Money.zero(targetCurrency))
                    .min(grossItemAmount);
                itemTrace.add(TraceStep.of("FREE_UNITS_APPLIED",
                    "Applied %s free units: charged for %s of %s, saving %s".formatted(
                        freeUnits.stripTrailingZeros().toPlainString(), chargeableQty, billableQty, freeUnitDiscount)));
            }

            List<Discount> monetaryDiscounts = itemDiscounts.stream()
                .filter(d -> d.type() != DiscountType.FREE_UNITS)
                .toList();
            var discountOutcome = discountEngine.applyDiscounts(grossItemAmount, monetaryDiscounts, evalTime);
            // Net is derived from gross minus the total discount (including free units) so the
            // three figures always agree, rather than being taken from two separate code paths.
            Money lineDiscount = discountOutcome.totalDiscount().plus(freeUnitDiscount).roundToCurrency();
            Money lineNet = grossItemAmount.minus(lineDiscount).roundToCurrency();
            itemTrace.addAll(discountOutcome.traceSteps());

            // Calculate item-level taxes on the net of line-level discounts
            List<TaxRate> taxRates = taxProvider.resolveTaxRates(request.tenantId(), itemReq.itemCode(), combinedAttrs);
            Money lineTax = taxForNet(lineNet, taxRates, targetCurrency);
            for (TaxRate taxRate : taxRates) {
                BigDecimal taxFactor = taxRate.percentage().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
                Money taxForRate = lineNet.times(taxFactor);
                itemTrace.add(TraceStep.of("TAX_APPLIED", "Tax %s (%s%% in %s): %s".formatted(
                    taxRate.taxCode(), taxRate.percentage(), taxRate.jurisdiction(), taxForRate
                )));
            }

            Money lineFinal = lineNet.plus(lineTax).roundToCurrency();

            grossAccumulator = grossAccumulator.plus(grossItemAmount);
            lineDiscountAccumulator = lineDiscountAccumulator.plus(lineDiscount);
            netAccumulator = netAccumulator.plus(lineNet);
            taxAccumulator = taxAccumulator.plus(lineTax);
            lineTaxRates.add(List.copyOf(taxRates));

            lineItems.add(new RatedLineItem(
                itemReq.itemCode(),
                itemReq.quantity(),
                billableQty,
                grossItemAmount.roundToCurrency(),
                lineDiscount.roundToCurrency(),
                lineNet.roundToCurrency(),
                lineTax.roundToCurrency(),
                lineFinal,
                itemTrace
            ));
        }

        // 2. Process Invoice-Level Discounts
        List<Discount> invoiceDiscounts = effectiveDiscounts.stream()
            .filter(d -> d.scope() == DiscountScope.INVOICE_TOTAL)
            .toList();

        var invoiceDiscountOutcome = discountEngine.applyDiscounts(netAccumulator, invoiceDiscounts, evalTime);
        // Discounts are money: a percentage of an amount can land on a fractional cent, and that
        // fraction cannot be apportioned across lines (RemainderAllocator refuses to round silently).
        // Rounding the aggregate to the currency's minor unit first is the only conserving choice;
        // clamping to the balance keeps a rounded-up discount from driving net negative.
        Money totalInvoiceDiscount = invoiceDiscountOutcome.totalDiscount()
            .roundToCurrency()
            .min(netAccumulator);
        Money netAfterAllDiscounts = netAccumulator.minus(totalInvoiceDiscount);

        for (TraceStep s : invoiceDiscountOutcome.traceSteps()) {
            trace.addStep(s);
        }

        // Zero-drift distribution of invoice discounts down to line items using RemainderAllocator
        if (!totalInvoiceDiscount.isZero() && !lineItems.isEmpty()) {
            List<BigDecimal> weights = lineItems.stream()
                .map(item -> item.netAmount().amount())
                .toList();
            List<Money> distributedDiscounts = RemainderAllocator.allocate(totalInvoiceDiscount, weights);
            List<RatedLineItem> updatedLineItems = new ArrayList<>(lineItems.size());
            Money recomputedTax = Money.zero(targetCurrency);
            for (int i = 0; i < lineItems.size(); i++) {
                RatedLineItem original = lineItems.get(i);
                Money allocatedDisc = distributedDiscounts.get(i);
                Money newNet = original.netAmount().minus(allocatedDisc).roundToCurrency();

                // Tax is due on the amount actually charged. Reducing the net by an invoice-level
                // discount must reduce the tax by the same proportion, otherwise every
                // invoice-level discount over-charges VAT/GST.
                Money newTax = taxForNet(newNet, lineTaxRates.get(i), targetCurrency);
                Money newTotal = newNet.plus(newTax).roundToCurrency();
                recomputedTax = recomputedTax.plus(newTax);

                List<TraceStep> updatedTrace = new ArrayList<>(original.traceSteps());
                if (!allocatedDisc.isZero()) {
                    updatedTrace.add(TraceStep.of("INVOICE_DISCOUNT_ALLOCATED",
                        "Apportioned invoice discount %s: adjusted net = %s, tax restated %s -> %s"
                            .formatted(allocatedDisc, newNet, original.taxAmount(), newTax)));
                }
                updatedLineItems.add(new RatedLineItem(
                    original.itemCode(),
                    original.rawQuantity(),
                    original.billableQuantity(),
                    original.grossAmount(),
                    original.discountAmount().plus(allocatedDisc).roundToCurrency(),
                    newNet,
                    newTax.roundToCurrency(),
                    newTotal,
                    updatedTrace
                ));
            }
            lineItems = updatedLineItems;
            taxAccumulator = recomputedTax;
            trace.addStep("INVOICE_DISCOUNT_DISTRIBUTED",
                "Allocated %s invoice discount across %d line items with zero penny drift; "
                    .formatted(totalInvoiceDiscount, lineItems.size())
                    + "tax restated to %s on the discounted base".formatted(taxAccumulator));
        }

        Money totalDiscounts = lineDiscountAccumulator.plus(totalInvoiceDiscount).roundToCurrency();
        Money finalTotal = netAfterAllDiscounts.plus(taxAccumulator).roundToCurrency();

        // 3. Minimum Spend Commitment Evaluation & True-up
        if (spendCommitment.isPresent() && spendCommitment.get().isEffectiveAt(evalTime)) {
            SpendCommitment commitment = spendCommitment.get();
            // The minimum is per commitment period. Applying an annual minimum to a monthly invoice
            // compares incomparable figures: a $1,000/month minimum on a $5,000 annual invoice would
            // demand no true-up at all ($5,000 > $1,000), when the year actually needs $12,000.
            // The same rule as flat-fee cadence: once the request declares its period, a mismatch is
            // an error rather than a silent undercharge.
            if (request.billingCadence().isPresent()
                && commitment.cadence() != request.billingCadence().get()) {
                throw new IllegalArgumentException(
                    "Spend commitment '%s' is a %s minimum but the request is charging a %s period; "
                        .formatted(commitment.commitmentId(), commitment.cadence(), request.billingCadence().get())
                        + "declare the matching cadence so the minimum is compared against the period it covers");
            }
            Money trueUp = commitment.calculateTrueUp(netAfterAllDiscounts);
            if (trueUp.isPositive()) {
                trace.addStep("COMMITMENT_SHORTFALL",
                    "Net spend %s fell below minimum commitment %s. Adding true-up charge of %s"
                        .formatted(netAfterAllDiscounts, commitment.minimumAmount(), trueUp));

                RatedLineItem trueUpItem = new RatedLineItem(
                    "COMMITMENT_TRUE_UP",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    trueUp.roundToCurrency(),
                    Money.zero(targetCurrency),
                    trueUp.roundToCurrency(),
                    Money.zero(targetCurrency),
                    trueUp.roundToCurrency(),
                    List.of(TraceStep.of("COMMITMENT_TRUE_UP", "Minimum spend commitment shortfall assessed"))
                );
                lineItems.add(trueUpItem);
                grossAccumulator = grossAccumulator.plus(trueUp);
                netAfterAllDiscounts = netAfterAllDiscounts.plus(trueUp);
                finalTotal = finalTotal.plus(trueUp).roundToCurrency();
            }
        }

        trace.addStep("EVALUATION_COMPLETED", "Final Total: %s (Gross: %s, Discounts: %s, Tax: %s)".formatted(
            finalTotal, grossAccumulator.roundToCurrency(), totalDiscounts, taxAccumulator.roundToCurrency()
        ));

        PricingResult result = new PricingResult(
            calculationId,
            request.tenantId(),
            request.customerId(),
            request.planCode(),
            evalTime,
            targetCurrency,
            grossAccumulator.roundToCurrency(),
            totalDiscounts,
            netAfterAllDiscounts.roundToCurrency(),
            taxAccumulator.roundToCurrency(),
            finalTotal,
            lineItems,
            trace
        );

        auditSink.record(result);
        return result;
    }

    /**
     * Rejects a flat fee whose cadence does not match the period being charged, and records the
     * resolved billing period on the trace.
     *
     * <p>Only enforced when the caller declares {@link PricingRequest#billingCadence()}. A
     * one-off rating that does not know its cycle is still accepted, because inventing an anchor
     * would be a guess; but as soon as the cadence is declared, a mismatch is an error rather than
     * a ten-times overcharge.
     */
    private static void verifyFlatFeeCadence(RatePlanItem planItem, PricingRequest request,
                                             List<TraceStep> itemTrace) {
        if (!(planItem.pricingModel() instanceof PricingModel.FlatFeeModel flatFee)) {
            return;
        }
        if (request.billingCadence().isPresent() && request.billingCycleAnchor().isPresent()) {
            BillingPeriod period = request.billingCycleAnchor().get()
                .periodContaining(request.evaluationTime().orElse(Instant.now()), request.billingCadence().get());
            itemTrace.add(TraceStep.of("BILLING_PERIOD_RESOLVED",
                "Charging cadence %s, period [%s .. %s)".formatted(
                    request.billingCadence().get(), period.start(), period.end())));
        }
        if (request.billingCadence().isEmpty()) {
            return;
        }
        BillingCadence declared = request.billingCadence().get();
        BillingCadence feeCadence = flatFee.cadence();
        if (feeCadence.isRecurring() && feeCadence != declared) {
            throw new IllegalArgumentException(
                "Flat fee for item '%s' is priced at %s cadence but the request is charging a %s period. "
                    .formatted(planItem.itemCode(), feeCadence, declared)
                    + "A flat fee covers exactly one period; declare the matching cadence or use a model "
                    + "that scales with period length.");
        }
    }

    /**
     * Applies the proration factor and FX rate to an amount, in that order.
     *
     * <p>Extracted so the full-quantity and free-units-reduced evaluations go through exactly the
     * same adjustments; computing only one of them would leave the free-unit saving in the model's
     * base currency on a converted invoice.
     */
    private static Money proratedAndConverted(Money amount, BigDecimal prorationFactor,
                                              BigDecimal fxRate, CurrencyUnit targetCurrency) {
        Money result = amount;
        if (prorationFactor != null) {
            result = result.times(prorationFactor);
        }
        if (fxRate != null) {
            result = Money.of(result.amount().multiply(fxRate), targetCurrency);
        }
        return result;
    }

    /**
     * Computes the tax due on a net amount for a given set of rates.
     *
     * <p>Single definition of "tax on this base", used both when a line is first rated and when an
     * invoice-level discount is later apportioned onto it. Keeping one implementation is what
     * guarantees the invoice total equals the sum of its lines.
     */
    private static Money taxForNet(Money net, List<TaxRate> taxRates, CurrencyUnit currency) {
        Money tax = Money.zero(currency);
        for (TaxRate taxRate : taxRates) {
            BigDecimal taxFactor = taxRate.percentage().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
            tax = tax.plus(net.times(taxFactor));
        }
        return tax.roundToCurrency();
    }
}
