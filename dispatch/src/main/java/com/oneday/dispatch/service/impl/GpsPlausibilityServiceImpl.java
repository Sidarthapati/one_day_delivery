package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.service.GpsPlausibilityService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Combines the ping's self-reported signals with server-derived checks (§ location-trust Phase 1).
 * Impossible-travel is measured against the DA's most recent stored fix; the mock flag and coordinate
 * range are hard trust-breakers; timestamp skew and poor accuracy are softer contributors to the score.
 * Risk weights are additive and capped at 100.
 */
@Service
class GpsPlausibilityServiceImpl implements GpsPlausibilityService {

    private static final int W_RANGE = 100;   // impossible coordinate — cannot be a real fix
    private static final int W_MOCK = 60;     // device admits a mock provider
    private static final int W_VELOCITY = 40; // teleport between fixes
    private static final int W_SKEW = 30;     // device clock/timestamp implausible
    private static final int W_ACCURACY = 15; // very poor fix — weak corroboration

    private final DaGpsPingRepository pingRepository;
    private final DispatchProperties props;

    GpsPlausibilityServiceImpl(DaGpsPingRepository pingRepository, DispatchProperties props) {
        this.pingRepository = pingRepository;
        this.props = props;
    }

    @Override
    public GpsPlausibility evaluate(UUID daId, double lat, double lon, Instant deviceTime,
                                    Double accuracyM, Boolean mocked, Instant serverNow) {
        DispatchProperties.Gps.Integrity cfg = props.getGps().getIntegrity();

        boolean rangeValid = lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
        boolean isMock = Boolean.TRUE.equals(mocked);
        boolean lowAccuracy = accuracyM != null && accuracyM > cfg.getMaxAccuracyMeters();

        boolean tsSkew = deviceTime != null
                && Math.abs(Duration.between(deviceTime, serverNow).getSeconds())
                        > cfg.getTimestampSkewToleranceSeconds();

        boolean velocity = false;
        if (rangeValid && deviceTime != null) {
            DaGpsPing prev = pingRepository.findTopByDaIdOrderByRecordedAtDesc(daId);
            if (prev != null) {
                long dtSeconds = Duration.between(prev.getRecordedAt(), deviceTime).getSeconds();
                if (dtSeconds > 0) {
                    double km = GeoDistance.km(prev.getLat(), prev.getLon(), lat, lon);
                    double kmph = km / (dtSeconds / 3600.0);
                    velocity = kmph > cfg.getMaxSpeedKmph();
                }
            }
        }

        int score = 0;
        if (!rangeValid) score += W_RANGE;
        if (isMock) score += W_MOCK;
        if (velocity) score += W_VELOCITY;
        if (tsSkew) score += W_SKEW;
        if (lowAccuracy) score += W_ACCURACY;
        score = Math.min(score, 100);

        return new GpsPlausibility(rangeValid, velocity, tsSkew, lowAccuracy, isMock, score);
    }
}
