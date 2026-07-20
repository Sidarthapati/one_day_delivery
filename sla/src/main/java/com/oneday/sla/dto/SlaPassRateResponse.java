package com.oneday.sla.dto;

import java.time.Instant;

/** The measured SLA pass-rate over a window (Annexure D/L — the 99% gate). */
public record SlaPassRateResponse(
        Instant from,
        Instant to,
        String city,
        long closed,
        long breached,
        long passed,
        double passRate) {

    public static SlaPassRateResponse of(Instant from, Instant to, String city, long closed, long breached) {
        long passed = closed - breached;
        double rate = closed == 0 ? 1.0 : (double) passed / (double) closed;
        return new SlaPassRateResponse(from, to, city, closed, breached, passed, rate);
    }
}
