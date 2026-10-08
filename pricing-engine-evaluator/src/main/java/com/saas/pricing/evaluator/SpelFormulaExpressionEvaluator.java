package com.saas.pricing.evaluator;

import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sandboxed, high-performance mathematical formula evaluator using Spring Expression Language (SpEL).
 * Strictly isolated using SimpleEvaluationContext to prevent unauthorized reflection or method invocation.
 * Registers safe mathematical functions: #max, #min, #pow, #ceil, #floor, #abs, #sqrt, #clamp.
 */
public final class SpelFormulaExpressionEvaluator implements FormulaExpressionEvaluator {

    private static final Method METHOD_MAX;
    private static final Method METHOD_MIN;
    private static final Method METHOD_POW;
    private static final Method METHOD_CEIL;
    private static final Method METHOD_FLOOR;
    private static final Method METHOD_ABS;
    private static final Method METHOD_SQRT;
    private static final Method METHOD_CLAMP;

    static {
        try {
            METHOD_MAX = PricingMath.class.getMethod("max", double.class, double.class);
            METHOD_MIN = PricingMath.class.getMethod("min", double.class, double.class);
            METHOD_POW = PricingMath.class.getMethod("pow", double.class, double.class);
            METHOD_CEIL = PricingMath.class.getMethod("ceil", double.class);
            METHOD_FLOOR = PricingMath.class.getMethod("floor", double.class);
            METHOD_ABS = PricingMath.class.getMethod("abs", double.class);
            METHOD_SQRT = PricingMath.class.getMethod("sqrt", double.class);
            METHOD_CLAMP = PricingMath.class.getMethod("clamp", double.class, double.class, double.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    @Override
    public BigDecimal evaluate(String expressionStr, Map<String, BigDecimal> variables) {
        Objects.requireNonNull(expressionStr, "Expression string cannot be null");
        Objects.requireNonNull(variables, "Variables map cannot be null");

        Expression expression = expressionCache.computeIfAbsent(expressionStr, parser::parseExpression);

        // SimpleEvaluationContext prevents code injection, reflection, or arbitrary method execution
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
            .withInstanceMethods()
            .build();

        // Register safe math functions
        context.setVariable("max", METHOD_MAX);
        context.setVariable("min", METHOD_MIN);
        context.setVariable("pow", METHOD_POW);
        context.setVariable("ceil", METHOD_CEIL);
        context.setVariable("floor", METHOD_FLOOR);
        context.setVariable("abs", METHOD_ABS);
        context.setVariable("sqrt", METHOD_SQRT);
        context.setVariable("clamp", METHOD_CLAMP);

        // Populate variables into evaluation context
        for (Map.Entry<String, BigDecimal> entry : variables.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }

        Object result = expression.getValue(context);
        if (result == null) {
            return BigDecimal.ZERO;
        }

        if (result instanceof BigDecimal bd) {
            return bd;
        } else if (result instanceof Number num) {
            return new BigDecimal(num.toString());
        } else {
            return new BigDecimal(result.toString());
        }
    }
}
