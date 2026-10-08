package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.Tier;
import com.saas.pricing.core.model.TraceStep;
import com.saas.pricing.core.spi.FormulaExpressionEvaluator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.saas.pricing.core.model.MatrixEntry;

/**
 * Universal evaluator for all PricingModel variants using Java 25 pattern matching.
 */
public final class ModelEvaluator {

    public record EvaluationOutcome(
        Money grossAmount,
        List<TraceStep> traceSteps
    ) {}

    public EvaluationOutcome evaluate(
        PricingModel model,
        BigDecimal quantity,
        CurrencyUnit currency,
        Map<String, Object> attributes,
        FormulaExpressionEvaluator formulaEvaluator
    ) {
        Objects.requireNonNull(model, "model cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");

        List<TraceStep> trace = new ArrayList<>();

        Money gross = switch (model) {
            case PricingModel.FlatFeeModel flat -> {
                trace.add(TraceStep.of("FLAT_FEE", "Flat fee applied: " + flat.amount()));
                yield flat.amount();
            }

            case PricingModel.PerUnitModel perUnit -> {
                BigDecimal effectiveQty = perUnit.minimumUnits()
                    .map(min -> quantity.max(min))
                    .orElse(quantity);

                Money result = Money.of(perUnit.unitPrice(), currency).times(effectiveQty);
                trace.add(TraceStep.of(
                    "PER_UNIT",
                    "Units: %s, Rate: %s, Subtotal: %s".formatted(effectiveQty, perUnit.unitPrice(), result)
                ));
                yield result;
            }

            case PricingModel.GraduatedTierModel graduated -> {
                Money runningTotal = Money.zero(currency);
                for (Tier tier : graduated.tiers()) {
                    BigDecimal unitsInTier = tier.unitsInTier(quantity);
                    if (unitsInTier.compareTo(BigDecimal.ZERO) > 0) {
                        Money tierUnitCost = Money.of(tier.unitPrice(), currency).times(unitsInTier);
                        Money tierFlatCost = Money.of(tier.flatFee(), currency);
                        Money tierSubtotal = tierUnitCost.plus(tierFlatCost);
                        runningTotal = runningTotal.plus(tierSubtotal);

                        trace.add(TraceStep.of(
                            "GRADUATED_TIER_SLAB",
                            "Bracket [%s, %s]: %s units @ %s + base %s = %s".formatted(
                                tier.lowerBound(),
                                tier.upperBound().map(Object::toString).orElse("infinity"),
                                unitsInTier,
                                tier.unitPrice(),
                                tier.flatFee(),
                                tierSubtotal
                            )
                        ));
                    }
                }
                yield runningTotal;
            }

            case PricingModel.VolumeTierModel volume -> {
                Optional<Tier> matchedTier = volume.tiers().stream()
                    .filter(t -> t.contains(quantity))
                    .findFirst();

                if (matchedTier.isEmpty()) {
                    throw new IllegalStateException("Quantity " + quantity + " did not fall into any volume tier");
                }

                Tier tier = matchedTier.get();
                Money unitsCost = Money.of(tier.unitPrice(), currency).times(quantity);
                Money flatCost = Money.of(tier.flatFee(), currency);
                Money total = unitsCost.plus(flatCost);

                trace.add(TraceStep.of(
                    "VOLUME_TIER_MATCH",
                    "Matched volume tier [%s, %s]: All %s units @ %s + base %s = %s".formatted(
                        tier.lowerBound(),
                        tier.upperBound().map(Object::toString).orElse("infinity"),
                        quantity,
                        tier.unitPrice(),
                        tier.flatFee(),
                        total
                    )
                ));
                yield total;
            }

            case PricingModel.StairStepModel stairStep -> {
                var matchedStep = stairStep.steps().stream()
                    .filter(s -> s.contains(quantity))
                    .findFirst();

                if (matchedStep.isEmpty()) {
                    throw new IllegalStateException("Quantity " + quantity + " did not fall into any stair step");
                }

                Money fee = matchedStep.get().fee();
                trace.add(TraceStep.of(
                    "STAIR_STEP_MATCH",
                    "Matched stair-step bracket [%s, %s]: Fee = %s".formatted(
                        matchedStep.get().lowerBound(),
                        matchedStep.get().upperBound().map(Object::toString).orElse("infinity"),
                        fee
                    )
                ));
                yield fee;
            }

            case PricingModel.DimensionalMatrixModel matrix -> {
                var matchingEntries = matrix.entries().stream()
                    .filter(e -> e.matches(attributes))
                    .sorted(Comparator.comparingInt((MatrixEntry e) -> e.specificityScore(attributes)).reversed())
                    .toList();

                if (!matchingEntries.isEmpty()) {
                    MatrixEntry bestEntry = matchingEntries.get(0);
                    trace.add(TraceStep.of(
                        "MATRIX_BEST_MATCH",
                        "Matched matrix entry (specificity=%d) with criteria: %s".formatted(
                            bestEntry.specificityScore(attributes), bestEntry.dimensionValues()
                        )
                    ));
                    EvaluationOutcome nestedOutcome = evaluate(
                        bestEntry.model(),
                        quantity,
                        currency,
                        attributes,
                        formulaEvaluator
                    );
                    trace.addAll(nestedOutcome.traceSteps());
                    yield nestedOutcome.grossAmount();
                }

                if (matrix.fallbackModel().isPresent()) {
                    trace.add(TraceStep.of("MATRIX_FALLBACK", "No matrix entry matched. Evaluating fallback model."));
                    EvaluationOutcome fallbackOutcome = evaluate(
                        matrix.fallbackModel().get(),
                        quantity,
                        currency,
                        attributes,
                        formulaEvaluator
                    );
                    trace.addAll(fallbackOutcome.traceSteps());
                    yield fallbackOutcome.grossAmount();
                }

                throw new IllegalStateException(
                    "No matching matrix entry found for attributes: " + attributes
                );
            }

            case PricingModel.DynamicFormulaModel formula -> {
                if (formulaEvaluator == null) {
                    throw new IllegalStateException("Dynamic formula pricing requires a FormulaExpressionEvaluator");
                }
                Map<String, BigDecimal> variables = new HashMap<>();
                variables.put("quantity", quantity);
                for (String varName : formula.requiredVariables()) {
                    if (!variables.containsKey(varName)) {
                        Object val = attributes.get(varName);
                        if (val instanceof Number num) {
                            variables.put(varName, new BigDecimal(num.toString()));
                        } else if (val instanceof String str) {
                            variables.put(varName, new BigDecimal(str));
                        } else {
                            throw new IllegalArgumentException("Missing required formula variable: " + varName);
                        }
                    }
                }

                BigDecimal calculatedAmount = formulaEvaluator.evaluate(formula.expression(), variables);
                Money result = Money.of(calculatedAmount, currency);
                trace.add(TraceStep.of(
                    "DYNAMIC_FORMULA",
                    "Evaluated formula '%s' with variables %s = %s".formatted(formula.expression(), variables, result)
                ));
                yield result;
            }

            case PricingModel.CompositePricingModel composite -> {
                Money sum = Money.zero(currency);
                for (PricingModel subModel : composite.subModels()) {
                    EvaluationOutcome subOutcome = evaluate(subModel, quantity, currency, attributes, formulaEvaluator);
                    sum = sum.plus(subOutcome.grossAmount());
                    trace.addAll(subOutcome.traceSteps());
                }
                yield sum;
            }

            case PricingModel.HybridModel hybrid -> {
                trace.add(TraceStep.of("HYBRID_BASE_FEE", "Hybrid base subscription fee: " + hybrid.baseFee()));
                Money total = hybrid.baseFee();
                BigDecimal overageUnits = quantity.subtract(hybrid.includedUnits());
                if (overageUnits.compareTo(BigDecimal.ZERO) > 0) {
                    trace.add(TraceStep.of(
                        "HYBRID_OVERAGE_DETECTED",
                        "Total units %s exceeds included %s by %s units".formatted(
                            quantity, hybrid.includedUnits(), overageUnits
                        )
                    ));
                    EvaluationOutcome overageOutcome = evaluate(
                        hybrid.overageModel(),
                        overageUnits,
                        currency,
                        attributes,
                        formulaEvaluator
                    );
                    Money overageCost = overageOutcome.grossAmount();
                    if (hybrid.overageCap().isPresent() && overageCost.compareTo(hybrid.overageCap().get()) > 0) {
                        Money cap = hybrid.overageCap().get();
                        trace.add(TraceStep.of("HYBRID_OVERAGE_CAPPED", "Overage cost %s capped at %s".formatted(overageCost, cap)));
                        overageCost = cap;
                    }
                    total = total.plus(overageCost);
                    trace.addAll(overageOutcome.traceSteps());
                } else {
                    trace.add(TraceStep.of(
                        "HYBRID_WITHIN_ALLOWANCE",
                        "Quantity %s is completely within included allowance %s".formatted(quantity, hybrid.includedUnits())
                    ));
                }
                yield total;
            }
        };

        return new EvaluationOutcome(gross, trace);
    }
}
