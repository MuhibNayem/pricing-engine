package com.saas.pricing.evaluator;

/**
 * Sandboxed mathematical helper functions for SpEL pricing formulas.
 */
public final class PricingMath {

    private PricingMath() {}

    public static double max(double a, double b) {
        return Math.max(a, b);
    }

    public static double min(double a, double b) {
        return Math.min(a, b);
    }

    public static double pow(double a, double b) {
        return Math.pow(a, b);
    }

    public static double ceil(double a) {
        return Math.ceil(a);
    }

    public static double floor(double a) {
        return Math.floor(a);
    }

    public static double abs(double a) {
        return Math.abs(a);
    }

    public static double sqrt(double a) {
        return Math.sqrt(a);
    }

    public static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(val, max));
    }
}
