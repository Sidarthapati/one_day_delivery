package com.oneday.airline.service.exception;

/** No ACTIVE {@code lane_rate_card} exists for a lane — mirrors M2's "no rate configured" gap. → 422. */
public class LaneRateCardNotFoundException extends RuntimeException {
    public LaneRateCardNotFoundException(String originHub, String destHub) {
        super("No active rate card for lane %s→%s".formatted(originHub, destHub));
    }
}
