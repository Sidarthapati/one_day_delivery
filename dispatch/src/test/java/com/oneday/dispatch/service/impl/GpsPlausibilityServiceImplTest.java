package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.service.GpsPlausibilityService.GpsPlausibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plausibility service turns one ping (plus the DA's previous fix) into a trust verdict: a real,
 * in-range, coherent fix is trusted; a mock-provider fix, a teleport, a skewed clock, or an off-planet
 * coordinate is not — and each contributes to the risk score.
 */
class GpsPlausibilityServiceImplTest {

    private final DaGpsPingRepository pingRepository = mock(DaGpsPingRepository.class);
    private final DispatchProperties props = new DispatchProperties();
    private final GpsPlausibilityServiceImpl service = new GpsPlausibilityServiceImpl(pingRepository, props);

    private final UUID da = UUID.randomUUID();
    // Bengaluru hub-ish; server "now" == device time so no skew in the happy path.
    private static final double LAT = 12.9716, LON = 77.5946;

    private DaGpsPing prevAt(double lat, double lon, Instant t) {
        DaGpsPing p = new DaGpsPing(da, lat, lon, t);
        return p;
    }

    @Test
    void cleanFix_isTrusted() {
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da)).thenReturn(null);
        Instant now = Instant.parse("2026-08-31T06:00:00Z");

        GpsPlausibility v = service.evaluate(da, LAT, LON, now, 12.0, false, now);

        assertThat(v.trusted()).isTrue();
        assertThat(v.riskScore()).isZero();
    }

    @Test
    void mockedFix_isUntrusted_andScored() {
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da)).thenReturn(null);
        Instant now = Instant.parse("2026-08-31T06:00:00Z");

        GpsPlausibility v = service.evaluate(da, LAT, LON, now, 12.0, true, now);

        assertThat(v.mocked()).isTrue();
        assertThat(v.trusted()).isFalse();
        assertThat(v.riskScore()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void teleport_flagsVelocity() {
        Instant prevT = Instant.parse("2026-08-31T06:00:00Z");
        // Previous fix in Delhi, current in Bengaluru ~1740km, 60s later → ~104000 km/h.
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da))
                .thenReturn(prevAt(28.6139, 77.2090, prevT));
        Instant now = prevT.plusSeconds(60);

        GpsPlausibility v = service.evaluate(da, LAT, LON, now, 12.0, false, now);

        assertThat(v.velocityFlag()).isTrue();
        assertThat(v.trusted()).isFalse();
    }

    @Test
    void realisticDrive_doesNotFlagVelocity() {
        Instant prevT = Instant.parse("2026-08-31T06:00:00Z");
        // ~1km apart, 120s later → 30 km/h.
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da))
                .thenReturn(prevAt(12.9626, 77.5946, prevT));
        Instant now = prevT.plusSeconds(120);

        GpsPlausibility v = service.evaluate(da, LAT, LON, now, 12.0, false, now);

        assertThat(v.velocityFlag()).isFalse();
        assertThat(v.trusted()).isTrue();
    }

    @Test
    void staleClock_flagsSkew() {
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da)).thenReturn(null);
        Instant deviceTime = Instant.parse("2026-08-31T06:00:00Z");
        Instant serverNow = deviceTime.plusSeconds(600); // 10 min skew > 120s tolerance

        GpsPlausibility v = service.evaluate(da, LAT, LON, deviceTime, 12.0, false, serverNow);

        assertThat(v.tsSkewFlag()).isTrue();
        assertThat(v.trusted()).isFalse();
    }

    @Test
    void offPlanetCoordinate_isRangeInvalid() {
        when(pingRepository.findTopByDaIdOrderByRecordedAtDesc(da)).thenReturn(null);
        Instant now = Instant.parse("2026-08-31T06:00:00Z");

        GpsPlausibility v = service.evaluate(da, 999.0, 999.0, now, 12.0, false, now);

        assertThat(v.rangeValid()).isFalse();
        assertThat(v.trusted()).isFalse();
        assertThat(v.riskScore()).isEqualTo(100);
    }
}
