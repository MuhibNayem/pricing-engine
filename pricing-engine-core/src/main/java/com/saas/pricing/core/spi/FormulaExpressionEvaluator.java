package com.saas.pricing.core.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SPI for evaluating dynamic formula expressions.
 */
public interface FormulaExpressionEvaluator {

    BigDecimal evaluate(String expression, Map<String, BigDecimal> variables);
}
