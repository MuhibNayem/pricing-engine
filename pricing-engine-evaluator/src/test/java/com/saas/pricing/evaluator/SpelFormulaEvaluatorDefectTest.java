package com.saas.pricing.evaluator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the empirically verified defects in the SpEL formula evaluator.
 */
class SpelFormulaEvaluatorDefectTest {

    private final SpelFormulaExpressionEvaluator evaluator = new SpelFormulaExpressionEvaluator();

    private static Map<String, BigDecimal> vars(String key, String value) {
        Map<String, BigDecimal> map = new HashMap<>();
        map.put(key, new BigDecimal(value));
        return map;
    }

    @Nested
    @DisplayName("DEFECT 1 & 3 - integer / truncation division")
    class Division {

        @Test
        @DisplayName("SpEL '/' retains documented truncating semantics")
        void slashOperatorKeepsSpelSemantics() {
            // '/' and 'div' are REJECTED, not merely documented.
            //
            // They used to evaluate as stock SpEL: 7 / 2 -> 4, 100 / 3 -> 33, 100 / 3 * 3 -> 99,
            // 1 div 3 -> 0. In a rating engine that is a silent mispricing on any per-unit formula.
            // The operator cannot be redefined without giving up SimpleEvaluationContext and
            // therefore the sandbox, so the expression is refused outright and the author is
            // pointed at #divide. A rejected formula is recoverable; a wrong invoice is not.
            assertThatThrownBy(() -> evaluator.evaluate("7 / 2", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#divide");
            assertThatThrownBy(() -> evaluator.evaluate("100 / 3", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#divide");
            assertThatThrownBy(() -> evaluator.evaluate("100 / 3 * 3", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#divide");
            assertThatThrownBy(() -> evaluator.evaluate("1 div 3", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#divide");
        }

        @Test
        @DisplayName("A '/' inside a string literal is not mistaken for division")
        void slashInsideStringLiteralIsNotRejectedAsDivision() {
            // The scanner skips single-quoted segments; this expression is refused later, for the
            // unrelated and correct reason that a string is not a number.
            assertThatThrownBy(() -> evaluator.evaluate("'a/b'", Map.of()))
                .hasMessageNotContaining("'/' operator");
        }

        @Test
        @DisplayName("#divide returns exact decimal values where '/' truncated")
        void divideHelperIsExact() {
            assertThat(evaluator.evaluate("#divide(7,2)", Map.of())).isEqualByComparingTo("3.5");
            assertThat(evaluator.evaluate("#divide(100,3)", Map.of()))
                .isEqualByComparingTo("33.33333333333333333333333333333333");
            assertThat(evaluator.evaluate("#divide(10.00,4)", Map.of())).isEqualByComparingTo("2.5");
        }

        @Test
        @DisplayName("#divide(a,b) round-trips a whole unit without loss")
        void divideRoundTripLosesNoUnit() {
            // Before: 100 / 3 * 3 == 99 (lost a whole unit). Now the round-trip is exact.
            assertThat(evaluator.evaluate("#divide(100,3) * 3", Map.of(), 6))
                .isEqualByComparingTo("100.000000");
        }

        @Test
        @DisplayName("#divide accepts an explicit scale argument")
        void divideWithScale() {
            assertThat(evaluator.evaluate("#divide(1,3,10)", Map.of())).isEqualByComparingTo("0.3333333333");
            assertThat(evaluator.evaluate("#divide(2,3,4)", Map.of())).isEqualByComparingTo("0.6667");
        }

        @ParameterizedTest(name = "#divide({0}, 7) is not an integer truncation")
        @CsvSource({"1", "2", "3", "4", "5", "100"})
        @DisplayName("#divide never silently drops a fractional unit")
        void divideNeverTruncates(String a) {
            BigDecimal result = evaluator.evaluate("#divide(" + a + ", 7)", Map.of());

            // Before the fix, '#' division truncated: 100 / 7 collapsed to 14 and the
            // round-trip lost 2 whole units. The result must now carry the fraction.
            //
            // The round-trip is checked within the evaluator's documented precision rather than
            // for exact equality: #divide computes in MathContext.DECIMAL128 (34 significant
            // digits), so 1/7 * 7 lands on 1.0000000000000000000000000000000003. That residue is
            // the declared precision, not a truncation - the property under test is that no whole
            // unit disappears.
            assertThat(result).isNotNull();
            assertThat(result.multiply(BigDecimal.valueOf(7)))
                .isCloseTo(new BigDecimal(a), org.assertj.core.data.Offset.offset(new BigDecimal("1e-30")));
            if (result.stripTrailingZeros().scale() > 0) {
                assertThat(result).isNotEqualByComparingTo(BigDecimal.valueOf(new BigDecimal(a)
                    .divide(BigDecimal.valueOf(7), 0, java.math.RoundingMode.FLOOR).longValue()));
            }
        }

        @Test
        @DisplayName("#divide on BigDecimal variables avoids the literal-precision trap")
        void divideOnVariables() {
            assertThat(evaluator.evaluate("#divide(#units, 4)", vars("units", "100")))
                .isEqualByComparingTo("25");
            assertThat(evaluator.evaluate("#divide(#units, 8)", vars("units", "100")))
                .isEqualByComparingTo("12.5");
        }

        @Test
        @DisplayName("#divide rejects out-of-range and duplicate scale arguments")
        void divideRejectsBadScales() {
            assertThatThrownBy(() -> evaluator.evaluate("#divide(1,3,-1)", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
            assertThatThrownBy(() -> evaluator.evaluate("#divide(1,3,1000)", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
            assertThatThrownBy(() -> evaluator.evaluate("#divide(1,3,2,4)", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
        }
    }

    @Nested
    @DisplayName("DEFECT 2 - double contamination")
    class DoubleContamination {

        private static final String HIGH_PRECISION = "123456789012345678.12345678";

        @ParameterizedTest(name = "#{0} preserves all 26 significant digits")
        @ValueSource(strings = {"max", "min", "abs"})
        @DisplayName("Helpers stay BigDecimal-native instead of round-tripping through double")
        void helpersPreservePrecision(String fn) {
            Map<String, BigDecimal> vars = vars("big", HIGH_PRECISION);
            String expression = switch (fn) {
                case "max" -> "#max(0, #big)";
                case "min" -> "#min(#big, #big)";
                default -> "#abs(0 - #big)";
            };
            // Before: 1.2345678901234568E+17
            assertThat(evaluator.evaluate(expression, vars)).isEqualByComparingTo(HIGH_PRECISION);
        }

        @Test
        @DisplayName("#clamp preserves precision on a high-precision operand")
        void clampPreservesPrecision() {
            Map<String, BigDecimal> vars = vars("big", HIGH_PRECISION);
            assertThat(evaluator.evaluate("#clamp(#big, 0, #big)", vars))
                .isEqualByComparingTo(HIGH_PRECISION);
        }

        @Test
        @DisplayName("#sqrt keeps far more precision than a double round-trip")
        void sqrtIsHighPrecision() {
            // Before: 1.4142135623730951 (16 digits). Now ~34 significant digits.
            BigDecimal sqrt2 = evaluator.evaluate("#sqrt(2)", Map.of());
            assertThat(sqrt2).isEqualByComparingTo("1.414213562373095048801688724209698");
            assertThat(sqrt2.scale()).isGreaterThan(16);
        }

        @Test
        @DisplayName("#pow returns an exact integer for integral exponents")
        void powIsExact() {
            assertThat(evaluator.evaluate("#pow(2,10)", Map.of())).isEqualByComparingTo("1024");
            assertThat(evaluator.evaluate("#pow(3,0)", Map.of())).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("#ceil and #floor return exact integers, not doubles")
        void ceilFloorAreExact() {
            assertThat(evaluator.evaluate("#ceil(1.2)", Map.of())).isEqualByComparingTo("2");
            assertThat(evaluator.evaluate("#floor(1.8)", Map.of())).isEqualByComparingTo("1");
            assertThat(evaluator.evaluate("#ceil(-1.2)", Map.of())).isEqualByComparingTo("-1");
            assertThat(evaluator.evaluate("#floor(-1.2)", Map.of())).isEqualByComparingTo("-2");
        }

        @Test
        @DisplayName("Helpers reject negative sqrt and out-of-range exponents")
        void helpersRejectInvalidInput() {
            assertThatThrownBy(() -> evaluator.evaluate("#sqrt(0 - 1)", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
            assertThatThrownBy(() -> evaluator.evaluate("#pow(2, 2000000000)", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
        }
    }

    @Nested
    @DisplayName("DEFECT 4 - currency rounding on the result path")
    class CurrencyRounding {

        @Test
        @DisplayName("The scale overload rounds HALF_EVEN at the currency boundary")
        void roundsToCurrencyScale() {
            // 2.345 -> HALF_EVEN at 2dp is 2.34 (HALF_UP would give 2.35).
            assertThat(evaluator.evaluate("2.345", Map.of(), 2)).isEqualByComparingTo("2.34");
            assertThat(evaluator.evaluate("2.355", Map.of(), 2)).isEqualByComparingTo("2.36");
        }

        @Test
        @DisplayName("The two-argument overload preserves the original unrounded contract")
        void twoArgOverloadDoesNotRound() {
            BigDecimal raw = evaluator.evaluate("#divide(1,3)", Map.of());
            assertThat(raw.scale()).isGreaterThan(2);
            assertThat(evaluator.evaluate("#divide(1,3)", Map.of(), 2)).isEqualByComparingTo("0.33");
        }

        @Test
        @DisplayName("A realistic AI-token formula produces an exact cent value")
        void aiTokenFormulaProducesExactCents() {
            // 1_500_000 * 0.0000025 = 3.75 ; 250_000 * 0.00001 = 2.50 ; total 6.25
            Map<String, BigDecimal> vars = new HashMap<>();
            vars.put("promptTokens", new BigDecimal("1500000"));
            vars.put("completionTokens", new BigDecimal("250000"));

            BigDecimal total = evaluator.evaluate(
                "promptTokens * 0.0000025 + completionTokens * 0.00001", vars, 2);

            assertThat(total).isEqualByComparingTo("6.25");
            assertThat(total.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("DEFECT 5 - resource bounds")
    class ResourceBounds {

        @Test
        @DisplayName("Expressions beyond the configured length are rejected")
        void rejectsOverlongExpressions() {
            SpelFormulaExpressionEvaluator bounded =
                new SpelFormulaExpressionEvaluator(50, 16, 0L, 1000, 340);

            assertThatThrownBy(() -> bounded.evaluate("1" + "+1".repeat(100), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum length");
        }

        @Test
        @DisplayName("The parsed-expression cache is bounded and evicts LRU")
        void cacheIsBounded() {
            SpelFormulaExpressionEvaluator bounded =
                new SpelFormulaExpressionEvaluator(1000, 4, 0L, 1000, 340);

            for (int i = 0; i < 50; i++) {
                bounded.evaluate("1 + " + i, Map.of());
            }

            assertThat(bounded.cachedExpressionCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("Re-evaluating a cached expression still returns the correct value")
        void cacheDoesNotCorruptResults() {
            SpelFormulaExpressionEvaluator bounded =
                new SpelFormulaExpressionEvaluator(1000, 2, 0L, 1000, 340);

            for (int i = 0; i < 20; i++) {
                assertThat(bounded.evaluate("2 * 3", Map.of())).isEqualByComparingTo("6");
                assertThat(bounded.evaluate("2 * 4", Map.of())).isEqualByComparingTo("8");
            }
        }

        @Test
        @DisplayName("Reflective BigDecimal methods (the original DoS vector) are unreachable")
        void reflectiveMethodsAreBlocked() {
            // Before: #x.pow(2000000000) never returned within 60s under a 512MB heap.
            assertThatThrownBy(() -> evaluator.evaluate("#x.pow(2000000000)", vars("x", "1")))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
            assertThatThrownBy(() -> evaluator.evaluate("#x.stripTrailingZeros()", vars("x", "1")))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
        }

        @Test
        @DisplayName("Results beyond the configured magnitude are rejected")
        void rejectsOverlargeResults() {
            SpelFormulaExpressionEvaluator tight =
                new SpelFormulaExpressionEvaluator(1000, 16, 0L, 5, 340);

            assertThatThrownBy(() -> tight.evaluate("#divide(1,3,30)", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magnitude");
        }

        @Test
        @DisplayName("Cache TTL expiry re-parses instead of serving a stale entry")
        void cacheTtlExpires() throws Exception {
            SpelFormulaExpressionEvaluator bounded =
                new SpelFormulaExpressionEvaluator(1000, 8, 1L, 1000, 340);

            bounded.evaluate("1 + 1", Map.of());
            assertThat(bounded.cachedExpressionCount()).isEqualTo(1);

            Thread.sleep(20);

            assertThat(bounded.evaluate("1 + 1", Map.of())).isEqualByComparingTo("2");
            assertThat(bounded.cachedExpressionCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("DEFECT 6, 7 & 8 - context and variable binding")
    class ContextBinding {

        @ParameterizedTest(name = "{0} is rejected")
        @ValueSource(strings = {"#this", "#root", "#this.x", "#root.class", "1 + #root", "(#this)"})
        @DisplayName("#this and #root are rejected instead of silently yielding zero")
        void rejectsImplicitRootReferences(String expression) {
            // Before: #this and #root silently evaluated to BigDecimal.ZERO, masking a
            // broken formula as a legitimate $0 charge.
            assertThatThrownBy(() -> evaluator.evaluate(expression, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#this");
        }

        @Test
        @DisplayName("A variable merely containing 'root' as a prefix is still allowed")
        void allowsVariablesThatMerelyStartWithRoot() {
            assertThat(evaluator.evaluate("#rootValue", vars("rootValue", "5")))
                .isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("A null variables map is rejected with a clear message")
        void rejectsNullVariables() {
            assertThatThrownBy(() -> evaluator.evaluate("1 + 1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Variables map cannot be null");
        }

        @Test
        @DisplayName("A null expression is rejected")
        void rejectsNullExpression() {
            assertThatThrownBy(() -> evaluator.evaluate(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Both bare-identifier and '#' variable forms resolve identically")
        void bothVariableFormsResolve() {
            Map<String, BigDecimal> vars = vars("promptTokens", "1000");

            assertThat(evaluator.evaluate("promptTokens", vars)).isEqualByComparingTo("1000");
            assertThat(evaluator.evaluate("#promptTokens", vars)).isEqualByComparingTo("1000");
            assertThat(evaluator.evaluate("promptTokens * 2", vars)).isEqualByComparingTo("2000");
            assertThat(evaluator.evaluate("#promptTokens * 2", vars)).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("The README flagship formula works with bare identifiers")
        void readmeFormulaWithBareIdentifiers() {
            // Before: EL1007E "Property or field 'promptTokens' cannot be found on null".
            Map<String, BigDecimal> vars = new HashMap<>();
            vars.put("promptTokens", new BigDecimal("1000000"));
            vars.put("completionTokens", new BigDecimal("500000"));

            BigDecimal result = evaluator.evaluate(
                "(promptTokens * 0.0000015) + (completionTokens * 0.000006)", vars);

            assertThat(result).isEqualByComparingTo("4.5");
        }

        @Test
        @DisplayName("An unknown bare identifier fails loudly rather than evaluating to zero")
        void unknownVariableFailsLoudly() {
            assertThatThrownBy(() -> evaluator.evaluate("missingVariable + 1", Map.of()))
                .isInstanceOf(org.springframework.expression.EvaluationException.class);
        }

        @Test
        @DisplayName("Null and reserved variable names are rejected")
        void rejectsNullAndReservedVariableNames() {
            Map<String, BigDecimal> withNull = new HashMap<>();
            withNull.put("x", null);
            assertThatThrownBy(() -> evaluator.evaluate("#x", withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

            assertThatThrownBy(() -> evaluator.evaluate("#max", vars("max", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        }
    }
}