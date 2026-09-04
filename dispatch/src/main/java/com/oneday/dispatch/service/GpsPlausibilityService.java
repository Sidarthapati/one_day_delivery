package com.oneday.dispatch.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side plausibility check for an incoming DA GPS ping. The device's own signals (mock-provider
 * flag, accuracy) are combined with server-derived checks (coordinate range, timestamp skew vs the
 * server clock, and impossible-travel speed vs the DA's previous fix) into a trust verdict. Nothing
 * here trusts the client's word alone — the mock flag is one signal among several.
 */
public interface GpsPlausibilityService {

    /**
     * Evaluate a fix. {@code deviceTime} is the client-supplied fix time; {@code serverNow} is the
     * server receive time. {@code accuracyM}/{@code mocked} may be null on older app builds.
     */
    GpsPlausibility evaluate(UUID daId, double lat, double lon, Instant deviceTime,
                             Double accuracyM, Boolean mocked, Instant serverNow);

    /** The verdict for a single ping. {@link #trusted()} fixes may establish attendance / drive tracking. */
    record GpsPlausibility(
            boolean rangeValid,
            boolean velocityFlag,
            boolean tsSkewFlag,
            boolean lowAccuracy,
            boolean mocked,
            int riskScore) {

        /** Trustworthy enough to establish presence or move the live map: in-range, real, coherent. */
        public boolean trusted() {
            return rangeValid && !mocked && !velocityFlag && !tsSkewFlag;
        }
    }
}
