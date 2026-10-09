package com.saas.pricing.evaluator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Sandboxed mathematical helper functions for SpEL pricing formulas.
 *
 * <p><strong>Numeric model.</strong> Every helper is BigDecimal-native: no helper returns
 * {@code double} and no helper performs an intermediate conversion through {@code double}.
 * Returning {@code double} from a helper silently truncated decimal literals (which SpEL parses
 * as {@link Double}) down to ~15 significant digits, so a helper such as {@code #max(0, #big)}
 * returned {@code 1.2345678901234568E+17} for an input of {@code 123456789012345678.12345678}.
 *
 * <p><strong>Irrational strategy (exactly one).</strong> Operations that have an exact
 * {@link BigDecimal} representation &mdash; {@code max}, {@code min}, {@code abs}, {@code ceil},
 * {@code floor}, {@code clamp}, {@code divide} and integer {@code pow} &mdash; are computed
 * exactly. The inherently irrational operations ({@code sqrt}, and {@code pow} with a
 * non-integer exponent) cannot be exact in any finite decimal and are therefore
 * <em>approximations</em>: {@link #sqrt} runs Newton-Raphson iteration and {@link #pow} uses the
 * platform {@code double} seed, both confined to {@link #MATH_CONTEXT} (IEEE 754-2008
 * {@code decimal128}: 34 significant digits). Those approximations are confined to this class;
 * the final currency rounding is applied exactly once, by the evaluator, at the currency
 * boundary. {@code sqrt} is therefore accurate to roughly 34 significant digits rather than the
 * ~16 offered by a {@code double}-only implementation, but it remains an approximation.
 */
public final class PricingMath {

    /**
     * The single {@link MathContext} used for every approximate (irrational) computation.
     * 34 significant digits, HALF_UP rounding.
     */
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

    /** Maximum scale accepted by {@link #divide(BigDecimal, BigDecimal, int)}. */
    public static final int MAX_DIVISION_SCALE = 34;

    /** Maximum exponent accepted by {@link #pow(BigDecimal, BigDecimal)}. */
    public static final int MAX_POWER_EXPONENT = 1_000;

    /** Iteration cap for the Newton-Raphson loop in {@link #sqrt}; guarantees bounded compute. */
    private static final int MAX_SQRT_ITERATIONS = 200;

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private PricingMath() {}

    /**
     * Returns the larger of two values exactly.
     *
     * @param a first operand
     * @param b second operand
     * @return the greater of {@code a} and {@code b}
     */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return require(a, "a").max(require(b, "b"));
    }

    /**
     * Returns the smaller of two values exactly.
     *
     * @param a first operand
     * @param b second operand
     * @return the lesser of {@code a} and {@code b}
     */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return require(a, "a").min(require(b, "b"));
    }

    /**
     * Raises {@code base} to {@code exponent}.
     *
     * <p>An integral exponent within {@code [-}{@value #MAX_POWER_EXPONENT}{@code , }
     * {@value #MAX_POWER_EXPONENT}{@code ]} is computed exactly and bounded by
     * {@link #MATH_CONTEXT}. A non-integral exponent has no exact decimal representation and is
     * approximated from the platform {@code double} seed under {@link #MATH_CONTEXT}.
     *
     * @param base     the base
     * @param exponent the exponent
     * @return {@code base} raised to {@code exponent}
     * @throws IllegalArgumentException if the integral exponent exceeds {@link #MAX_POWER_EXPONENT}
     */
    public static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        BigDecimal b = require(base, "base");
        BigDecimal e = require(exponent, "exponent");
        if (isIntegral(e)) {
            int n = e.intValueExact();
            if (n < -MAX_POWER_EXPONENT || n > MAX_POWER_EXPONENT) {
                throw new IllegalArgumentException(
                    "Exponent " + n + " exceeds the supported bound of +/-" + MAX_POWER_EXPONENT);
            }
            return b.pow(n, MATH_CONTEXT);
        }
        double seed = Math.pow(b.doubleValue(), e.doubleValue());
        if (Double.isNaN(seed) || Double.isInfinite(seed)) {
            throw new IllegalArgumentException("pow(" + b + ", " + e + ") is not a finite number");
        }
        return new BigDecimal(seed, MATH_CONTEXT);
    }

    /**
     * Rounds {@code value} towards positive infinity to an integer scale. Exact.
     *
     * @param value the operand
     * @return {@code value} rounded up to scale {@code 0}
     */
    public static BigDecimal ceil(BigDecimal value) {
        return require(value, "value").setScale(0, RoundingMode.CEILING);
    }

    /**
     * Rounds {@code value} towards negative infinity to an integer scale. Exact.
     *
     * @param value the operand
     * @return {@code value} rounded down to scale {@code 0}
     */
    public static BigDecimal floor(BigDecimal value) {
        return require(value, "value").setScale(0, RoundingMode.FLOOR);
    }

    /**
     * Returns the absolute value exactly.
     *
     * @param value the operand
     * @return {@code |value|}
     */
    public static BigDecimal abs(BigDecimal value) {
        return require(value, "value").abs();
    }

    /**
     * Returns the non-negative square root, approximated by Newton-Raphson iteration under
     * {@link #MATH_CONTEXT}. See the class-level note: this is an approximation, not an exact
     * value, and it is computed entirely in {@link BigDecimal} (never via {@code double}).
     *
     * @param value the operand; must be non-negative
     * @return {@code sqrt(value)} to {@value #MAX_SQRT_ITERATIONS}{@code -}bounded iteration
     * @throws IllegalArgumentException if {@code value} is negative
     * @throws ArithmeticException      if the iteration fails to converge
     */
    public static BigDecimal sqrt(BigDecimal value) {
        BigDecimal v = require(value, "value");
        if (v.signum() < 0) {
            throw new IllegalArgumentException("sqrt() requires a non-negative argument, got " + v);
        }
        if (v.signum() == 0) {
            return BigDecimal.ZERO;
        }

        // Seed near the true root: place the guess at the midpoint of the decimal exponent.
        int shift = (v.precision() - v.scale()) / 2 + 1;
        BigDecimal guess = BigDecimal.ONE.scaleByPowerOfTen(shift);
        if (guess.signum() == 0) {
            guess = BigDecimal.ONE;
        }

        for (int i = 0; i < MAX_SQRT_ITERATIONS; i++) {
            BigDecimal next = guess.add(v.divide(guess, MATH_CONTEXT), MATH_CONTEXT)
                .divide(TWO, MATH_CONTEXT);
            if (next.compareTo(guess) == 0) {
                return next;
            }
            guess = next;
        }
        throw new ArithmeticException("sqrt() failed to converge within " + MAX_SQRT_ITERATIONS + " iterations");
    }

    /**
     * Constrains {@code value} to the inclusive range {@code [min, max]}. Exact.
     *
     * @param value the value to constrain
     * @param min   inclusive lower bound
     * @param max   inclusive upper bound
     * @return the constrained value
     */
    public static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal v = require(value, "value");
        BigDecimal lo = require(min, "min");
        BigDecimal hi = require(max, "max");
        return v.max(lo).min(hi);
    }

    /**
     * Divides {@code a} by {@code b}, optionally at an explicit result scale.
     *
     * <p>This is the explicit, documented replacement for the SpEL {@code /} operator, whose
     * scale is {@code max(left.scale, right.scale)} and therefore silently truncates
     * {@code 7 / 2} to {@code 3} and {@code 100 / 3} to {@code 33}.
     *
     * <p>The trailing scale is declared {@code int...} rather than as an overload because SpEL
     * binds exactly one {@link java.lang.reflect.Method} per variable name; a varargs tail is the
     * only way to expose both arities under the single name {@code #divide}.
     *
     * @param a        dividend
     * @param b        divisor
     * @param scale    optional single result scale, within {@code [0, }{@value #MAX_DIVISION_SCALE}{@code ]}
     * @return {@code a / b} under {@link #MATH_CONTEXT}, or rounded HALF_UP to {@code scale}
     * @throws ArithmeticException      if {@code b} is zero
     * @throws IllegalArgumentException if the scale is out of range or more than one is supplied
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int... scale) {
        BigDecimal dividend = require(a, "a");
        BigDecimal divisor = require(b, "b");
        if (scale.length > 1) {
            throw new IllegalArgumentException(
                "divide() accepts at most one scale argument, got " + scale.length);
        }
        if (scale.length == 0) {
            return dividend.divide(divisor, MATH_CONTEXT);
        }
        int s = scale[0];
        if (s < 0 || s > MAX_DIVISION_SCALE) {
            throw new IllegalArgumentException(
                "Division scale must be within [0, " + MAX_DIVISION_SCALE + "], got " + s);
        }
        return dividend.divide(divisor, s, RoundingMode.HALF_UP);
    }

    private static boolean isIntegral(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal require(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Argument '" + name + "' cannot be null");
        }
        return value;
    }
}