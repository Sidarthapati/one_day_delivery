package com.oneday.airline.service.exception;

/** No ACTIVE row in the consolidator's (mocked) {@code lane_rate} table for a lane. → 422. */
public class ConsolidatorRateNotFoundException extends RuntimeException {
    public ConsolidatorRateNotFoundException(String originHub, String destHub) {
        super("No active consolidator rate for lane %s→%s".formatted(originHub, destHub));
    }
}
