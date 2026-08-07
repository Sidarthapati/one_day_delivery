package com.oneday.airline.consolidator;

/**
 * One row of the consolidator's (mocked) {@code lane_rate} table — the active GCR-style per-kg
 * rate for a lane. Same shape as the old (now-dropped) {@code lane_rate_card}, just sourced from
 * the consolidator's own system instead of our own negotiated table.
 */
public record ConsolidatorLaneRate(
        String originHub,
        String destHub,
        long minChargePaise,
        long terminalHandlingPaise,
        long rateBelow45kgPaisePerKg,
        long rateQ45PaisePerKg,
        long rateQ100PaisePerKg,
        long rateQ300PaisePerKg,
        long rateQ500PaisePerKg,
        long rateQ1000PaisePerKg) {
}
