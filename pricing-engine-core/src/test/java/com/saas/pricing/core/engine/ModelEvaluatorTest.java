package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.MatrixEntry;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.StairStep;
import com.saas.pricing.core.model.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelEvaluatorTest {

    private final ModelEvaluator evaluator = new ModelEvaluator();

    @Test
    @DisplayName("Graduated Tiered (Slabs) should calculate bracket by bracket")
    void testGraduatedTierCalculation() {
        // 0 to 100 @ $10.00
        // 100 to 500 @ $8.00
        // 500+ @ $5.00
        var model = PricingModel.GraduatedTierModel.of(
            Tier.of(BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(10)),
            Tier.of(BigDecimal.valueOf(100), BigDecimal.valueOf(500), BigDecimal.valueOf(8)),
            Tier.unbounded(BigDecimal.valueOf(500), BigDecimal.valueOf(5))
        );

        // Test with 150 units: (100 * 10) + (50 * 8) = 1000 + 400 = 1400
        var outcome = evaluator.evaluate(
            model,
            BigDecimal.valueOf(150),
            CurrencyUnit.USD,
            Map.of(),
            null
        );

        assertThat(outcome.grossAmount()).isEqualTo(Money.of("1400.00", CurrencyUnit.USD));
        assertThat(outcome.traceSteps()).hasSize(2);
    }

    @Test
    @DisplayName("Volume Tiered (Cliffs) should rate all units at the matched tier rate")
    void testVolumeTierCalculation() {
        // 0 to 100 @ $10.00
        // 100+ @ $8.00
        var model = PricingModel.VolumeTierModel.of(
            Tier.of(BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(10)),
            Tier.unbounded(BigDecimal.valueOf(100), BigDecimal.valueOf(8))
        );

        // Test with 150 units: 150 * 8 = 1200
        var outcome = evaluator.evaluate(
            model,
            BigDecimal.valueOf(150),
            CurrencyUnit.USD,
            Map.of(),
            null
        );

        assertThat(outcome.grossAmount()).isEqualTo(Money.of("1200.00", CurrencyUnit.USD));
    }

    @Test
    @DisplayName("Stair-step should return fixed package fee for matched threshold")
    void testStairStepCalculation() {
        var model = PricingModel.StairStepModel.of(
            StairStep.of(BigDecimal.ZERO, BigDecimal.valueOf(10), Money.of("50.00", CurrencyUnit.USD)),
            StairStep.of(BigDecimal.valueOf(10), BigDecimal.valueOf(50), Money.of("120.00", CurrencyUnit.USD)),
            StairStep.unbounded(BigDecimal.valueOf(50), Money.of("250.00", CurrencyUnit.USD))
        );

        // Test with 25 units -> should be $120.00
        var outcome = evaluator.evaluate(
            model,
            BigDecimal.valueOf(25),
            CurrencyUnit.USD,
            Map.of(),
            null
        );

        assertThat(outcome.grossAmount()).isEqualTo(Money.of("120.00", CurrencyUnit.USD));
    }

    @Test
    @DisplayName("Dimensional Matrix should match based on request attributes")
    void testDimensionalMatrix() {
        var usModel = PricingModel.PerUnitModel.of(BigDecimal.valueOf(0.05));
        var euModel = PricingModel.PerUnitModel.of(BigDecimal.valueOf(0.08));

        var matrix = PricingModel.DimensionalMatrixModel.of(
            List.of("region"),
            List.of(
                MatrixEntry.of(Map.of("region", "us-east"), usModel),
                MatrixEntry.of(Map.of("region", "eu-central"), euModel)
            )
        );

        var outcomeUS = evaluator.evaluate(
            matrix,
            BigDecimal.valueOf(1000),
            CurrencyUnit.USD,
            Map.of("region", "us-east"),
            null
        );
        assertThat(outcomeUS.grossAmount().amount()).isEqualByComparingTo("50.00");

        var outcomeEU = evaluator.evaluate(
            matrix,
            BigDecimal.valueOf(1000),
            CurrencyUnit.USD,
            Map.of("region", "eu-central"),
            null
        );
        assertThat(outcomeEU.grossAmount().amount()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Composite Model should evaluate and sum multiple sub-models")
    void testCompositeModel() {
        var flatBase = PricingModel.FlatFeeModel.of(Money.of("50.00", CurrencyUnit.USD), BillingCadence.MONTHLY);
        var perUnit = PricingModel.PerUnitModel.of(BigDecimal.valueOf(2.00));

        var composite = PricingModel.CompositePricingModel.of(flatBase, perUnit);

        // 10 units: 50 base + (10 * 2) = 70.00
        var outcome = evaluator.evaluate(
            composite,
            BigDecimal.valueOf(10),
            CurrencyUnit.USD,
            Map.of(),
            null
        );

        assertThat(outcome.grossAmount()).isEqualTo(Money.of("70.00", CurrencyUnit.USD));
    }
}
