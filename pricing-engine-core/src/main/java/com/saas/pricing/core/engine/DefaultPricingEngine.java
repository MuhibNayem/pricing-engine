package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.DiscountScope;
import com.saas.pricing.core.model.EvaluationTrace;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
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
            Optional.empty()
        );
        RateCard rateCard = resolvedHierarchy.effectiveRateCard();
        List<Discount> effectiveDiscounts = new ArrayList<>(request.discounts());
        effectiveDiscounts.addAll(resolvedHierarchy.customDiscounts());
        Optional<SpendCommitment> spendCommitment = resolvedHierarchy.spendCommitment();

        trace.addStep("RATE_CARD_RESOLVED", "Resolved RateCard '%s' version %d via %s (overrides=%s)".formatted(
            rateCard.rateCardId(), rateCard.version(), resolvedHierarchy.sourceHierarchyLevel(), resolvedHierarchy.overriddenItemCodes()
        ));

        CurrencyUnit targetCurrency = request.targetCurrency();
        List<RatedLineItem> lineItems = new ArrayList<>();

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

            // Apply Proration if eligible
            if (planItem.proratable() && request.prorationWindow().isPresent()) {
                BigDecimal factor = request.prorationWindow().get().calculateFactor();
                grossItemAmount = grossItemAmount.times(factor);
                itemTrace.add(TraceStep.of("PRORATION_APPLIED", "Proration factor %s applied: new gross = %s".formatted(factor, grossItemAmount)));
            }

            // FX Conversion if needed
            if (!planItem.baseCurrency().equals(targetCurrency)) {
                BigDecimal fxRate = currencyExchangeProvider.getExchangeRate(planItem.baseCurrency(), targetCurrency, evalTime);
                grossItemAmount = Money.of(grossItemAmount.amount().multiply(fxRate), targetCurrency);
                itemTrace.add(TraceStep.of("FX_CONVERSION", "Converted from %s to %s at rate %s: %s".formatted(
                    planItem.baseCurrency().code(), targetCurrency.code(), fxRate, grossItemAmount
                )));
            }

            // Filter and apply item-level discounts
            List<Discount> itemDiscounts = effectiveDiscounts.stream()
                .filter(d -> d.scope() == DiscountScope.LINE_ITEM)
                .filter(d -> d.targetItemCode().map(code -> code.equalsIgnoreCase(itemReq.itemCode())).orElse(false))
                .toList();

            var discountOutcome = discountEngine.applyDiscounts(grossItemAmount, itemDiscounts, evalTime);
            Money lineDiscount = discountOutcome.totalDiscount();
            Money lineNet = discountOutcome.netAmount();
            itemTrace.addAll(discountOutcome.traceSteps());

            // Calculate item-level taxes
            List<TaxRate> taxRates = taxProvider.resolveTaxRates(request.tenantId(), itemReq.itemCode(), combinedAttrs);
            Money lineTax = Money.zero(targetCurrency);
            for (TaxRate taxRate : taxRates) {
                BigDecimal taxFactor = taxRate.percentage().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
                Money taxForRate = lineNet.times(taxFactor);
                lineTax = lineTax.plus(taxForRate);
                itemTrace.add(TraceStep.of("TAX_APPLIED", "Tax %s (%s%% in %s): %s".formatted(
                    taxRate.taxCode(), taxRate.percentage(), taxRate.jurisdiction(), taxForRate
                )));
            }

            Money lineFinal = lineNet.plus(lineTax).roundToCurrency();

            grossAccumulator = grossAccumulator.plus(grossItemAmount);
            lineDiscountAccumulator = lineDiscountAccumulator.plus(lineDiscount);
            netAccumulator = netAccumulator.plus(lineNet);
            taxAccumulator = taxAccumulator.plus(lineTax);

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
        Money totalInvoiceDiscount = invoiceDiscountOutcome.totalDiscount();
        Money netAfterAllDiscounts = invoiceDiscountOutcome.netAmount();

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
            for (int i = 0; i < lineItems.size(); i++) {
                RatedLineItem original = lineItems.get(i);
                Money allocatedDisc = distributedDiscounts.get(i);
                Money newNet = original.netAmount().minus(allocatedDisc).roundToCurrency();
                Money newTotal = newNet.plus(original.taxAmount()).roundToCurrency();
                List<TraceStep> updatedTrace = new ArrayList<>(original.traceSteps());
                if (!allocatedDisc.isZero()) {
                    updatedTrace.add(TraceStep.of("INVOICE_DISCOUNT_ALLOCATED",
                        "Apportioned invoice discount %s: adjusted net = %s".formatted(allocatedDisc, newNet)));
                }
                updatedLineItems.add(new RatedLineItem(
                    original.itemCode(),
                    original.rawQuantity(),
                    original.billableQuantity(),
                    original.grossAmount(),
                    original.discountAmount().plus(allocatedDisc).roundToCurrency(),
                    newNet,
                    original.taxAmount(),
                    newTotal,
                    updatedTrace
                ));
            }
            lineItems = updatedLineItems;
            trace.addStep("INVOICE_DISCOUNT_DISTRIBUTED",
                "Allocated %s invoice discount across %d line items with zero penny drift".formatted(
                    totalInvoiceDiscount, lineItems.size()
                ));
        }

        Money totalDiscounts = lineDiscountAccumulator.plus(totalInvoiceDiscount).roundToCurrency();
        Money finalTotal = netAfterAllDiscounts.plus(taxAccumulator).roundToCurrency();

        // 3. Minimum Spend Commitment Evaluation & True-up
        if (spendCommitment.isPresent() && spendCommitment.get().isEffectiveAt(evalTime)) {
            SpendCommitment commitment = spendCommitment.get();
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
}
