package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Universal sealed pricing model interface.
 * Represents any pricing logic in a SaaS product.
 * Fully extensible and exhaustively verified via Java 25 pattern matching.
 */
public sealed interface PricingModel extends Serializable permits
    PricingModel.FlatFeeModel,
    PricingModel.PerUnitModel,
    PricingModel.GraduatedTierModel,
    PricingModel.VolumeTierModel,
    PricingModel.StairStepModel,
    PricingModel.DimensionalMatrixModel,
    PricingModel.DynamicFormulaModel,
    PricingModel.CompositePricingModel,
    PricingModel.HybridModel {

    PricingModelType type();

    enum PricingModelType {
        FLAT_FEE,
        PER_UNIT,
        GRADUATED_TIER,
        VOLUME_TIER,
        STAIR_STEP,
        DIMENSIONAL_MATRIX,
        DYNAMIC_FORMULA,
        COMPOSITE,
        HYBRID
    }

    /**
     * Flat recurring or one-time fee.
     */
    record FlatFeeModel(Money amount, BillingCadence cadence) implements PricingModel {
        public FlatFeeModel {
            Objects.requireNonNull(amount, "Flat fee amount cannot be null");
            Objects.requireNonNull(cadence, "Cadence cannot be null");
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.FLAT_FEE;
        }

        public static FlatFeeModel of(Money amount, BillingCadence cadence) {
            return new FlatFeeModel(amount, cadence);
        }
    }

    /**
     * Linear per-unit pricing ($P = Q \times R$).
     */
    record PerUnitModel(BigDecimal unitPrice, Optional<BigDecimal> minimumUnits) implements PricingModel {
        public PerUnitModel {
            Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
            Objects.requireNonNull(minimumUnits, "minimumUnits cannot be null");
            if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("unitPrice cannot be negative");
            }
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.PER_UNIT;
        }

        public static PerUnitModel of(BigDecimal unitPrice) {
            return new PerUnitModel(unitPrice, Optional.empty());
        }

        public static PerUnitModel of(BigDecimal unitPrice, BigDecimal minimumUnits) {
            return new PerUnitModel(unitPrice, Optional.of(minimumUnits));
        }
    }

    /**
     * Graduated / Bracket / Slab tiered pricing.
     * Units within each bracket are rated at that bracket's price.
     */
    record GraduatedTierModel(List<Tier> tiers) implements PricingModel {
        public GraduatedTierModel {
            Objects.requireNonNull(tiers, "tiers cannot be null");
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException("GraduatedTierModel must contain at least one tier");
            }
            tiers = List.copyOf(tiers);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.GRADUATED_TIER;
        }

        public static GraduatedTierModel of(List<Tier> tiers) {
            return new GraduatedTierModel(tiers);
        }

        public static GraduatedTierModel of(Tier... tiers) {
            return new GraduatedTierModel(List.of(tiers));
        }
    }

    /**
     * Volume tiered (Cliff) pricing.
     * All units are rated at the rate of the final bracket reached.
     */
    record VolumeTierModel(List<Tier> tiers) implements PricingModel {
        public VolumeTierModel {
            Objects.requireNonNull(tiers, "tiers cannot be null");
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException("VolumeTierModel must contain at least one tier");
            }
            tiers = List.copyOf(tiers);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.VOLUME_TIER;
        }

        public static VolumeTierModel of(List<Tier> tiers) {
            return new VolumeTierModel(tiers);
        }

        public static VolumeTierModel of(Tier... tiers) {
            return new VolumeTierModel(List.of(tiers));
        }
    }

    /**
     * Stair-step / Package pricing.
     * Fixed rate per tier threshold.
     */
    record StairStepModel(List<StairStep> steps) implements PricingModel {
        public StairStepModel {
            Objects.requireNonNull(steps, "steps cannot be null");
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("StairStepModel must contain at least one step");
            }
            steps = List.copyOf(steps);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.STAIR_STEP;
        }

        public static StairStepModel of(List<StairStep> steps) {
            return new StairStepModel(steps);
        }

        public static StairStepModel of(StairStep... steps) {
            return new StairStepModel(List.of(steps));
        }
    }

    /**
     * Dimensional matrix model.
     * Matches input attributes against dimension matrix entries,
     * then delegates evaluation to the matching sub-model.
     */
    record DimensionalMatrixModel(
        List<String> matchDimensions,
        List<MatrixEntry> entries,
        Optional<PricingModel> fallbackModel
    ) implements PricingModel {
        public DimensionalMatrixModel {
            Objects.requireNonNull(matchDimensions, "matchDimensions cannot be null");
            Objects.requireNonNull(entries, "entries cannot be null");
            Objects.requireNonNull(fallbackModel, "fallbackModel cannot be null");
            matchDimensions = List.copyOf(matchDimensions);
            entries = List.copyOf(entries);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.DIMENSIONAL_MATRIX;
        }

        public static DimensionalMatrixModel of(List<String> matchDimensions, List<MatrixEntry> entries) {
            return new DimensionalMatrixModel(matchDimensions, entries, Optional.empty());
        }

        public static DimensionalMatrixModel of(List<String> matchDimensions, List<MatrixEntry> entries, PricingModel fallback) {
            return new DimensionalMatrixModel(matchDimensions, entries, Optional.of(fallback));
        }
    }

    /**
     * Dynamic mathematical formula model.
     * Evaluates custom expressions e.g. "(promptTokens * 0.00001) + (completionTokens * 0.00003)".
     */
    record DynamicFormulaModel(
        String expression,
        List<String> requiredVariables
    ) implements PricingModel {
        public DynamicFormulaModel {
            Objects.requireNonNull(expression, "expression cannot be null");
            Objects.requireNonNull(requiredVariables, "requiredVariables cannot be null");
            requiredVariables = List.copyOf(requiredVariables);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.DYNAMIC_FORMULA;
        }

        public static DynamicFormulaModel of(String expression, List<String> requiredVariables) {
            return new DynamicFormulaModel(expression, requiredVariables);
        }

        public static DynamicFormulaModel of(String expression, String... variables) {
            return new DynamicFormulaModel(expression, List.of(variables));
        }
    }

    /**
     * Composite model.
     * Allows combining multiple pricing models into a single component
     * (e.g. Base Flat Fee + Graduated Overage above allowance).
     */
    record CompositePricingModel(List<PricingModel> subModels) implements PricingModel {
        public CompositePricingModel {
            Objects.requireNonNull(subModels, "subModels cannot be null");
            if (subModels.isEmpty()) {
                throw new IllegalArgumentException("CompositePricingModel must contain at least one sub-model");
            }
            subModels = List.copyOf(subModels);
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.COMPOSITE;
        }

        public static CompositePricingModel of(List<PricingModel> subModels) {
            return new CompositePricingModel(subModels);
        }

        public static CompositePricingModel of(PricingModel... subModels) {
            return new CompositePricingModel(List.of(subModels));
        }
    }

    /**
     * Hybrid model combining a base subscription fee, included unit allowance,
     * and an overage pricing model (e.g. tiered or linear) with optional cap.
     */
    record HybridModel(
        Money baseFee,
        BigDecimal includedUnits,
        PricingModel overageModel,
        Optional<Money> overageCap
    ) implements PricingModel {
        public HybridModel {
            Objects.requireNonNull(baseFee, "baseFee cannot be null");
            Objects.requireNonNull(includedUnits, "includedUnits cannot be null");
            Objects.requireNonNull(overageModel, "overageModel cannot be null");
            Objects.requireNonNull(overageCap, "overageCap cannot be null");
            if (includedUnits.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("includedUnits cannot be negative");
            }
        }

        @Override
        public PricingModelType type() {
            return PricingModelType.HYBRID;
        }

        public static HybridModel of(Money baseFee, BigDecimal includedUnits, PricingModel overageModel) {
            return new HybridModel(baseFee, includedUnits, overageModel, Optional.empty());
        }

        public static HybridModel of(Money baseFee, BigDecimal includedUnits, PricingModel overageModel, Money overageCap) {
            return new HybridModel(baseFee, includedUnits, overageModel, Optional.of(overageCap));
        }
    }
}
