package com.oneday.orders.service;

/**
 * Decides whether a measured parcel materially exceeds its declared dimensions (the "moderate"
 * tolerance: volume OR any side). Orientation-independent — dimensions are size-sorted before
 * comparison, so it doesn't matter which measured axis maps to which declared axis.
 */
public interface DiscrepancyPolicy {

    /**
     * @param declared declared L/W/H in cm (any may be null)
     * @param measured measured L/W/H in cm (any may be null)
     * @return the verdict; {@code overDeclared=false} with an explanatory detail when it can't decide
     */
    Verdict evaluate(double[] declared, Double[] measured);

    record Verdict(boolean overDeclared, String detail) {}
}
