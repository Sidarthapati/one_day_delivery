package com.oneday.sla.domain;

/**
 * The station-manager triage band a parcel sits in — the coarse, stable bucket the control tower
 * groups by (round-2 model decision 4). Ordered most-urgent first; {@link #rank()} feeds the
 * priority score so the band always dominates finer signals (act-by, overshoot).
 *
 * <p>Bands are intentionally few so a manager works the top bucket first and builds muscle memory;
 * the fine ordering happens <em>within</em> a band via act-by then overshoot.</p>
 */
public enum PriorityBand {

    /** Breached, act-by imminent (≤30m), or the customer promise is already blown — act now. */
    CRITICAL(3),

    /** RED, or an act-by window closing soon (≤2h) — queue up next. */
    HIGH(2),

    /** AMBER / weather-exposed / plenty of headroom — keep an eye, no fire. */
    WATCH(1);

    private final int rank;

    PriorityBand(int rank) {
        this.rank = rank;
    }

    /** Higher = more urgent. Used as the dominant term of the priority score. */
    public int rank() {
        return rank;
    }
}
