package com.oneday.dispatch.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only GPS breadcrumb — one row per received DA ping. Extends {@link BaseEntity} (not Mutable):
 * rows are never updated, so the inherited {@code updated_at} simply stays equal to {@code created_at}.
 * This is the route-replay/history store that {@code da_status} (latest-only, overwrite) deliberately isn't.
 */
@Entity
@Table(name = "da_gps_ping")
@Getter
@Setter
@NoArgsConstructor
public class DaGpsPing extends BaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "lat", nullable = false, updatable = false)
    private double lat;

    @Column(name = "lon", nullable = false, updatable = false)
    private double lon;

    /** Device fix time carried on the ping (may lag server receive time on a delayed/queued send). */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    // ── location-trust signals (Phase 1) — all set once at insert, never mutated ──

    /** Device reported the fix came from a mock-location provider (Android isMock). Null on old builds. */
    @Column(name = "mocked", updatable = false)
    private Boolean mocked;

    /** Device-reported horizontal accuracy in metres, if sent. */
    @Column(name = "accuracy_m", updatable = false)
    private Double accuracyM;

    /** Device-reported speed in m/s, if sent. */
    @Column(name = "speed_mps", updatable = false)
    private Double speedMps;

    /** Server found an impossible jump (teleport) from the previous fix. */
    @Column(name = "velocity_flag", nullable = false, updatable = false)
    private boolean velocityFlag;

    /** Device fix time is implausibly far from server receive time. */
    @Column(name = "ts_skew_flag", nullable = false, updatable = false)
    private boolean tsSkewFlag;

    /** 0-100 rollup of the trust signals (higher = less trustworthy). */
    @Column(name = "risk_score", nullable = false, updatable = false)
    private int riskScore;

    public DaGpsPing(UUID daId, double lat, double lon, Instant recordedAt) {
        this.daId = daId;
        this.lat = lat;
        this.lon = lon;
        this.recordedAt = recordedAt;
    }
}
