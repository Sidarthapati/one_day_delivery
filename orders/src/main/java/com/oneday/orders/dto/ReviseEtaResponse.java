package com.oneday.orders.dto;

import java.time.Instant;

/**
 * Outcome of an ETA revision. {@code delayed} is true when the new ETA slipped past the promised ETA
 * (beyond the grace window); {@code customerNotified} is true when a delay notification was sent.
 */
public record ReviseEtaResponse(
        String shipmentRef,
        Instant etaPromised,
        Instant etaUpdated,
        boolean delayed,
        boolean customerNotified) {
}
