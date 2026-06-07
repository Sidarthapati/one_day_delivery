package com.oneday.routing.domain;

/** How a route plan was produced (§10). */
public enum RoutePlanSource {
    NIGHTLY,
    MANUAL_OVERRIDE,
    FALLBACK
}
