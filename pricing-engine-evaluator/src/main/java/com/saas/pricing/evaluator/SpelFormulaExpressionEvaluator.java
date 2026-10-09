package com.saas.pricing.evaluator;

import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Sandboxed, high-performance mathematical formula evaluator using Spring Expression Language (SpEL).
 *
 * <h2>Numeric contract</h2>
 * <ul>
 *   <li><strong>Division and power.</strong> {@code /}, {@code div} and {@code ^} are rejected at
 *       validation. Stock SpEL division truncates whole-number operands ({@code 7 / 2} is
 *       {@code 3}, {@code 100 / 3} is {@code 33}), and {@code ^} evaluates an exact
 *       {@link BigDecimal#pow(int)} with no exponent bound, which is a CPU and heap bomb. Formula
 *       authors use {@code #divide(a, b)} / {@code #divide(a, b, scale)} and
 *       {@code #pow(base, exponent)} instead; the helpers are BigDecimal-native and bounded.</li>
 *   <li><strong>Precision.</strong> All helpers are BigDecimal-native; see {@link PricingMath}.
 *       Note that SpEL parses decimal <em>literals</em> as {@link Double}, so a literal wider than
 *       ~15 significant digits is already lossy before any helper runs.</li>
 *   <li><strong>Rounding.</strong> {@link #evaluate(String, Map, int)} rounds the result once, at
 *       the currency boundary, using {@link RoundingMode#HALF_EVEN}. The two-argument
 *       {@link #evaluate(String, Map)} overload applies no rounding, preserving the original
 *       contract for existing callers.</li>
 * </ul>
 *
 * <h2>Sandbox</h2>
 * Evaluation uses {@link SimpleEvaluationContext}, which blocks {@code T(...)}, {@code new},
 * {@code getClass()}, {@code .class}, bean references and assignment. Its default method-resolver
 * list is empty, so instance-method resolution is denied as well (only the registered
 * {@code #helpers}, bound as {@link Method} variables, are callable) and
 * {@code #x.pow(2000000000)} cannot be reached. Bare identifiers resolve through
 * an immutable {@link FormulaVariables} holder exposing only the declared {@link BigDecimal}
 * values; a raw {@code Map} root object would instead expose {@code keySet()}, {@code entrySet()}
 * and friends.
 *
 * <h2>Resource bounds and residual risk</h2>
 * Expression length, parsed-expression cache size and result magnitude are all bounded. A hard
 * evaluation timeout is <strong>not</strong> enforced: SpEL has no interruptible evaluation hook,
 * and cancelling a worker thread cannot preempt a {@link BigDecimal} operation already running on
 * it. The documented mitigations are therefore the magnitude bounds, the exponent/scale caps in
 * {@link PricingMath}, and the denied method resolution; a hostile expression built entirely from
 * the surviving operators could still consume disproportionate CPU within those bounds. Callers
 * running untrusted rate-card authors should additionally bound evaluation at the transport layer.
 */
public final class SpelFormulaExpressionEvaluator implements FormulaExpressionEvaluator {

    /** Default cap on expression source length, in characters. */
    public static final int DEFAULT_MAX_EXPRESSION_LENGTH = 1_000;

    /** Default number of parsed expressions retained in the cache. */
    public static final int DEFAULT_CACHE_CAPACITY = 256;

    /** Default maximum significant digits allowed in an evaluation result. */
    public static final int DEFAULT_MAX_RESULT_PRECISION = 1_000;

    /** Default maximum scale allowed in an evaluation result. */
    public static final int DEFAULT_MAX_RESULT_SCALE = 340;

    /** Default time-to-live for a cache entry; {@code 0} disables expiry. */
    public static final long DEFAULT_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** Helper function names reserved by this evaluator. */
    private static final Set<String> RESERVED_NAMES = Set.of(
        "max", "min", "pow", "ceil", "floor", "abs", "sqrt", "clamp", "divide");

    /** Rejects the implicit-context references that would otherwise resolve to the root holder. */
    private static final Pattern IMPLICIT_ROOT = Pattern.compile("#\\s*(this|root)\\b");

    private static final Method METHOD_MAX;
    private static final Method METHOD_MIN;
    private static final Method METHOD_POW;
    private static final Method METHOD_CEIL;
    private static final Method METHOD_FLOOR;
    private static final Method METHOD_ABS;
    private static final Method METHOD_SQRT;
    private static final Method METHOD_CLAMP;
    private static final Method METHOD_DIVIDE;

    static {
        try {
            METHOD_MAX = PricingMath.class.getMethod("max", BigDecimal.class, BigDecimal.class);
            METHOD_MIN = PricingMath.class.getMethod("min", BigDecimal.class, BigDecimal.class);
            METHOD_POW = PricingMath.class.getMethod("pow", BigDecimal.class, BigDecimal.class);
            METHOD_CEIL = PricingMath.class.getMethod("ceil", BigDecimal.class);
            METHOD_FLOOR = PricingMath.class.getMethod("floor", BigDecimal.class);
            METHOD_ABS = PricingMath.class.getMethod("abs", BigDecimal.class);
            METHOD_SQRT = PricingMath.class.getMethod("sqrt", BigDecimal.class);
            METHOD_CLAMP = PricingMath.class.getMethod(
                "clamp", BigDecimal.class, BigDecimal.class, BigDecimal.class);
            METHOD_DIVIDE = PricingMath.class.getMethod("divide", BigDecimal.class, BigDecimal.class, int[].class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int maxExpressionLength;
    private final int maxResultPrecision;
    private final int maxResultScale;

    /** Bounded LRU of parsed expressions. See {@link ParsedExpressionCache} for thread-safety. */
    private final ParsedExpressionCache expressionCache;
    private final RoundingMode roundingMode;

    /** Creates an evaluator with the default limits and banker's rounding. */
    public SpelFormulaExpressionEvaluator() {
        this(DEFAULT_MAX_EXPRESSION_LENGTH, DEFAULT_CACHE_CAPACITY, DEFAULT_CACHE_TTL_MILLIS,
            DEFAULT_MAX_RESULT_PRECISION, DEFAULT_MAX_RESULT_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Creates an evaluator with the specified rounding mode. */
    public SpelFormulaExpressionEvaluator(RoundingMode roundingMode) {
        this(DEFAULT_MAX_EXPRESSION_LENGTH, DEFAULT_CACHE_CAPACITY, DEFAULT_CACHE_TTL_MILLIS,
            DEFAULT_MAX_RESULT_PRECISION, DEFAULT_MAX_RESULT_SCALE, roundingMode);
    }

    /**
     * @param maxExpressionLength  maximum accepted expression length in characters
     * @param cacheCapacity        maximum number of parsed expressions retained
     * @param cacheTtlMillis       how long an unused parsed expression is retained
     * @param maxResultPrecision   maximum significant digits allowed in a result
     * @param maxResultScale       maximum scale allowed in a result
     */
    public SpelFormulaExpressionEvaluator(int maxExpressionLength, int cacheCapacity, long cacheTtlMillis,
                                          int maxResultPrecision, int maxResultScale) {
        this(maxExpressionLength, cacheCapacity, cacheTtlMillis, maxResultPrecision, maxResultScale, RoundingMode.HALF_EVEN);
    }

    /**
     * @param maxExpressionLength  maximum accepted expression length in characters
     * @param cacheCapacity        maximum number of parsed expressions retained
     * @param cacheTtlMillis       how long an unused parsed expression is retained
     * @param maxResultPrecision   maximum significant digits allowed in a result
     * @param maxResultScale       maximum scale allowed in a result
     * @param roundingMode         rounding mode for financial currency rounding
     */
    public SpelFormulaExpressionEvaluator(int maxExpressionLength, int cacheCapacity, long cacheTtlMillis,
                                          int maxResultPrecision, int maxResultScale, RoundingMode roundingMode) {
        if (maxExpressionLength <= 0) {
            throw new IllegalArgumentException("maxExpressionLength must be positive");
        }
        if (cacheCapacity <= 0) {
            throw new IllegalArgumentException("cacheCapacity must be positive");
        }
        if (cacheTtlMillis < 0) {
            throw new IllegalArgumentException("cacheTtlMillis must not be negative");
        }
        if (maxResultPrecision <= 0 || maxResultScale < 0) {
            throw new IllegalArgumentException("Result magnitude bounds must be non-negative");
        }
        this.maxExpressionLength = maxExpressionLength;
        this.maxResultPrecision = maxResultPrecision;
        this.maxResultScale = maxResultScale;
        this.roundingMode = roundingMode != null ? roundingMode : RoundingMode.HALF_EVEN;
        this.expressionCache = new ParsedExpressionCache(
            new SpelExpressionParser(new SpelParserConfiguration(
                SpelCompilerMode.OFF, null, false, false, maxExpressionLength)),
            cacheCapacity, cacheTtlMillis);
    }

    /**
     * Evaluates {@code expression} against {@code variables} without rounding the result.
     *
     * <p>Variable values may be referenced either as a bare identifier ({@code quantity}) or as a
     * {@code #}-prefixed variable ({@code #quantity}); both forms resolve to the same value.
     */
    @Override
    public BigDecimal evaluate(String expression, Map<String, BigDecimal> variables) {
        return evaluate(expression, variables, -1);
    }

    /**
     * Evaluates {@code expression} and rounds the result to {@code currencyScale} decimal places
     * using {@link RoundingMode#HALF_EVEN}.
     *
     * @param currencyScale the currency scale to round to; a negative value disables rounding
     */
    public BigDecimal evaluate(String expression, Map<String, BigDecimal> variables, int currencyScale) {
        Objects.requireNonNull(expression, "Expression string cannot be null");
        if (variables == null) {
            throw new IllegalArgumentException("Variables map cannot be null");
        }
        validate(expression, variables);

        Expression parsed = expressionCache.get(expression);
        SimpleEvaluationContext context = buildContext(variables);
        BigDecimal value = toBigDecimal(parsed.getValue(context));
        guardMagnitude(value);
        return currencyScale < 0 ? value : value.setScale(currencyScale, this.roundingMode);
    }

    /**
     * Rejects expressions that cannot be rated safely.
     *
     * <p>The important case is {@code /} and {@code div}. SpEL divides BigDecimals at the maximum
     * scale of the two operands, so {@code 7 / 2} is {@code 4} and {@code 100 / 3} is {@code 33} -
     * a silent mispricing in which the customer's per-unit rate loses real money, and a round trip
     * such as {@code 100 / 3 * 3} returns 99 instead of 100.
     *
     * <p>The operator cannot be redefined on a {@link SimpleEvaluationContext} (Spring exposes no
     * setter), and overriding it would require {@code StandardEvaluationContext}, which re-opens
     * the type-reference, constructor and bean-resolution surface the sandbox closes. Security wins
     * over convenience here, so the operator is rejected outright and the author is pointed at
     * {@code #divide(a, b)}. A rejected formula is recoverable; a silently mispriced invoice is not.
     */
    private void validate(String expression, Map<String, BigDecimal> variables) {
        if (expression.length() > maxExpressionLength) {
            throw new IllegalArgumentException(
                "Expression exceeds the maximum length of " + maxExpressionLength
                    + " characters (actual: " + expression.length() + ")");
        }
        // Iterated rather than tested with containsValue(null): Map.of() rejects a null probe,
        // which would surface as a NullPointerException instead of a clear validation error.
        for (Map.Entry<String, BigDecimal> entry : variables.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Variable names must not be blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Variable '" + name + "' must not be null");
            }
            if (RESERVED_NAMES.contains(name)) {
                throw new IllegalArgumentException("Variable name '" + name + "' is reserved");
            }
        }
        if (IMPLICIT_ROOT.matcher(expression).find()) {
            throw new IllegalArgumentException(
                "Implicit context references (#this / #root) are not supported; "
                    + "reference declared variables by name or with a leading '#'");
        }
        rejectUnsafeOperators(expression);
    }

    /**
     * Fails fast on operators the sandbox cannot execute safely, skipping string literals so that a
     * literal such as {@code 'a/b'} is not misread.
     *
     * <p>{@code /} and {@code div} silently round BigDecimal division to the operands' scale, and
     * {@code ^} (power) bypasses the exponent cap that {@code #pow} enforces: Spring evaluates a
     * BigDecimal base with exact {@code BigDecimal.pow}, so {@code #max(2,1) ^ 999999998} is a CPU
     * and heap bomb that only fails after the fact, if at all. Both are refused and the author is
     * pointed at the bounded helper instead.
     */
    private static void rejectUnsafeOperators(String expression) {
        boolean inLiteral = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
                continue;
            }
            if (inLiteral) {
                continue;
            }
            if (c == '/') {
                throw new IllegalArgumentException(
                    "The '/' operator silently rounds BigDecimal division to the operands' scale "
                        + "(7 / 2 evaluates to 4). Use #divide(a, b) instead, or #divide(a, b, scale) "
                        + "for an explicit scale.");
            }
            if (c == '^') {
                throw new IllegalArgumentException(
                    "The '^' operator bypasses the exponent limit (an exact BigDecimal.pow has no "
                        + "bound). Use #pow(base, exponent) instead; it accepts exponents up to "
                        + "PricingMath.MAX_POWER_EXPONENT.");
            }
            if ((c == 'd' || c == 'D') && matchesWord(expression, i, "div")) {
                throw new IllegalArgumentException(
                    "The 'div' operator silently rounds BigDecimal division to the operands' scale "
                        + "(7 div 2 evaluates to 4). Use #divide(a, b) instead.");
            }
        }
    }

    private static boolean matchesWord(String expression, int index, String word) {
        if (index + word.length() > expression.length()) {
            return false;
        }
        if (!expression.regionMatches(index, word, 0, word.length())) {
            return false;
        }
        boolean boundaryBefore = index == 0 || !Character.isJavaIdentifierPart(expression.charAt(index - 1));
        int after = index + word.length();
        boolean boundaryAfter = after >= expression.length()
            || !Character.isJavaIdentifierPart(expression.charAt(after));
        return boundaryBefore && boundaryAfter;
    }

    private static BigDecimal toBigDecimal(Object result) {
        if (result == null) {
            throw new IllegalArgumentException(
                "Expression evaluated to null; a formula must produce a number");
        }
        if (result instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(result.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expression produced a non-numeric result: " + result, e);
        }
    }

    private void guardMagnitude(BigDecimal value) {
        int precision = value.precision();
        int scale = value.scale();
        if (precision > maxResultPrecision || scale > maxResultScale) {
            throw new IllegalArgumentException(
                "Result magnitude exceeds the configured bound (precision " + precision + " > "
                    + maxResultPrecision + " or scale " + scale + " > " + maxResultScale + ")");
        }
    }

    /**
     * Builds the sandboxed evaluation context.
     *
     * <p>Security posture, unchanged from the version verified by audit:
     * <ul>
     *   <li>{@link SimpleEvaluationContext} for read-only data binding - no type references
     *       ({@code T(...)}), no constructors ({@code new ...}), no bean resolution ({@code @bean}),
     *       no {@code getClass()} and therefore no {@code classLoader} reach.</li>
     *   <li>Reflective method calls are denied by {@link SimpleEvaluationContext} itself: its
     *       default method-resolver list is empty, so {@code #x.pow(...)} and friends are
     *       unreachable. The supported helper functions are bound as variables holding a
     *       {@link Method}, which keeps them callable through that single narrow channel.</li>
     *   <li>The root object is {@link FormulaVariables}, not a {@code Map}, so navigation cannot
     *       reach {@code keySet()} or {@code entrySet()}.</li>
     * </ul>
     *
     * <p>Variables are bound twice on purpose: as SpEL variables (so {@code #quantity} works) and as
     * root-object properties (so the bare form {@code quantity} works). Both resolve to the same
     * immutable {@link BigDecimal}.
     */
    private SimpleEvaluationContext buildContext(Map<String, BigDecimal> variables) {
        SimpleEvaluationContext context = SimpleEvaluationContext
            .forPropertyAccessors(new FormulaVariableAccessor())
            .withRootObject(new FormulaVariables(Map.copyOf(variables)))
            .build();

        for (Map.Entry<String, BigDecimal> entry : variables.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }

        context.setVariable("max", METHOD_MAX);
        context.setVariable("min", METHOD_MIN);
        context.setVariable("pow", METHOD_POW);
        context.setVariable("ceil", METHOD_CEIL);
        context.setVariable("floor", METHOD_FLOOR);
        context.setVariable("abs", METHOD_ABS);
        context.setVariable("sqrt", METHOD_SQRT);
        context.setVariable("clamp", METHOD_CLAMP);
        context.setVariable("divide", METHOD_DIVIDE);

        return context;
    }

    /**
     * Bounded LRU cache of parsed expressions, replacing the previous unbounded
     * {@code ConcurrentHashMap} that a rate-card author could exhaust the heap with.
     *
     * <p><strong>Thread-safety.</strong> All state lives in a single {@link LinkedHashMap} with
     * access-order enabled and every access is guarded by the instance monitor, so the LRU recency
     * bookkeeping stays consistent. Lookups run under the lock, which serialises concurrent
     * evaluations that hit the same cache; parsed expressions are immutable and the lock is held
     * only for a hash lookup, so no SpEL evaluation happens inside it.
     */
    private static final class ParsedExpressionCache {

        private final ExpressionParser parser;
        private final long ttlMillis;
        private final LinkedHashMap<String, CacheEntry> entries;

        ParsedExpressionCache(ExpressionParser parser, int capacity, long ttlMillis) {
            this.parser = parser;
            this.ttlMillis = ttlMillis;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > capacity;
                }
            };
        }

        Expression get(String expression) {
            long now = System.nanoTime();
            synchronized (entries) {
                CacheEntry entry = entries.get(expression);
                if (entry != null && isExpired(entry, now)) {
                    entries.remove(expression);
                    entry = null;
                }
                if (entry == null) {
                    Expression parsed = parser.parseExpression(expression);
                    entries.put(expression, new CacheEntry(parsed, now));
                    return parsed;
                }
                return entry.expression();
            }
        }

        private boolean isExpired(CacheEntry entry, long now) {
            return ttlMillis > 0 && now - entry.createdNanos() >= TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        }

        /** Visible for testing. */
        synchronized int size() {
            return entries.size();
        }

        /** Named CacheEntry, not Entry: inside a LinkedHashMap subclass an unqualified
         * {@code Entry} resolves to the inherited {@link java.util.Map.Entry}, which makes the
         * {@code removeEldestEntry} override fail to override. */
        private record CacheEntry(Expression expression, long createdNanos) {}
    }

    /**
     * Immutable holder backing the SpEL root object. It deliberately exposes no getters and no
     * collection views: the only way to read a value is through {@link FormulaVariableAccessor},
     * which is keyed on declared variable names and returns the {@link BigDecimal} directly.
     */
    private record FormulaVariables(Map<String, BigDecimal> values) {}

    /**
     * Resolves a bare identifier against the {@link FormulaVariables} root object.
     *
     * <p>A {@link Map} root object would have widened navigation to {@code keySet()},
     * {@code entrySet()} and {@code get(...)}; this accessor permits reads of declared variable
     * names only and refuses every other property, including {@code class}.
     */
    private static final class FormulaVariableAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return null;
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof FormulaVariables vars && vars.values().containsKey(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            BigDecimal value = ((FormulaVariables) target).values().get(name);
            if (value == null) {
                throw new AccessException("Unknown formula variable: " + name);
            }
            return new TypedValue(value);
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue)
            throws AccessException {
            throw new AccessException("Formula variables are read-only: " + name);
        }
    }

    /**
     * Exposes the configured expression cache size for diagnostics and tests.
     *
     * @return the number of currently cached parsed expressions
     */
    int cachedExpressionCount() {
        return expressionCache.size();
    }
}