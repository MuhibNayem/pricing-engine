package com.saas.pricing.core.model;

/**
 * How a discount combines with the other discounts that are simultaneously applicable.
 *
 * <ul>
 *   <li>{@link #ADDITIVE} - each discount is computed against the ORIGINAL gross and the savings
 *       are summed (percentages effectively add: 10% + 5% = 15% off).</li>
 *   <li>{@link #WATERFALL} - discounts apply sequentially in priority order, each computed against
 *       the REMAINING balance, so later discounts compound on earlier ones.</li>
 *   <li>{@link #COMPOUND} - a historical synonym for {@link #WATERFALL}. The two constants share
 *       one implementation; neither is deprecated because both appear in stored configuration.</li>
 *   <li>{@link #EXCLUSIVE} - the discount cannot be combined with anything else. The engine picks
 *       the single best saving among all applicable discounts (exclusive or not).</li>
 * </ul>
 */
public enum DiscountStackingRule {
    WATERFALL,
    COMPOUND,
    ADDITIVE,
    EXCLUSIVE
}
