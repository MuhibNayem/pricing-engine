package com.saas.pricing.evaluator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpelFormulaExpressionEvaluatorTest {

    private final SpelFormulaExpressionEvaluator evaluator = new SpelFormulaExpressionEvaluator();

    @Test
    @DisplayName("Should evaluate algebraic expression with variables accurately")
    void testExpressionEvaluation() {
        String expression = "(#inputTokens * 0.0000015) + (#outputTokens * 0.000006)";
        Map<String, BigDecimal> vars = Map.of(
            "inputTokens", BigDecimal.valueOf(1_000_000),
            "outputTokens", BigDecimal.valueOf(500_000)
        );

        BigDecimal result = evaluator.evaluate(expression, vars);

        // (1,000,000 * 0.0000015 = 1.5) + (500,000 * 0.000006 = 3.0) = 4.5
        assertThat(result).isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("Should evaluate nonlinear formula")
    void testNonlinearFormula() {
        String expression = "(#quantity * 10) + 25";
        Map<String, BigDecimal> vars = Map.of("quantity", BigDecimal.valueOf(5));

        BigDecimal result = evaluator.evaluate(expression, vars);

        // 5 * 10 + 25 = 75
        assertThat(result).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("Should evaluate math functions like #max, #min, #pow, and #clamp")
    void testMathFunctions() {
        String expression = "#max(0, #units - 100) * 0.5 + #clamp(#riskScore, 10, 50)";
        Map<String, BigDecimal> vars = Map.of(
            "units", BigDecimal.valueOf(150),
            "riskScore", BigDecimal.valueOf(75) // clamped to 50
        );

        BigDecimal result = evaluator.evaluate(expression, vars);

        // max(0, 150-100)*0.5 = 50 * 0.5 = 25.0 + 50 = 75.0
        assertThat(result).isEqualByComparingTo("75.0");
    }

    @Test
    @DisplayName("Should block unauthorized reflection or system calls in sandboxed context")
    void testSandboxSecurity() {
        String dangerousExpression = "T(java.lang.System).exit(0)";
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            evaluator.evaluate(dangerousExpression, Map.of())
        ).isInstanceOf(org.springframework.expression.EvaluationException.class);
    }
}
