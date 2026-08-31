package com.oneday.dispatch.service;

import java.time.Instant;

/**
 * A DA GPS fix plus its evaluated location-trust signals, as persisted to the append-only breadcrumb.
 * {@code recordedAt} is the device fix time; the flags/score come from {@link GpsPlausibilityService}.
 * Raw-only fixes (e.g. internal callers, tests) use {@link #trusted(double, double, Instant)}.
 */
public record GpsSample(
        double lat,
        double lon,
        Instant recordedAt,
        Double accuracyM,
        Double speedMps,
        Boolean mocked,
        boolean velocityFlag,
        boolean tsSkewFlag,
        int riskScore) {

    /** A fix with no integrity metadata, treated as trusted (server-internal / legacy callers). */
    public static GpsSample trusted(double lat, double lon, Instant recordedAt) {
        return new GpsSample(lat, lon, recordedAt, null, null, null, false, false, 0);
    }
}
