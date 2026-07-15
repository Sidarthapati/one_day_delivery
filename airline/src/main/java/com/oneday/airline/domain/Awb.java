package com.oneday.airline.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The record of a finished booking (§6): a hub's sealed flight bag, booked onto one flight as one
 * confirmed reservation. At most one {@code BOOKED} row per {@code bagId} at a time — booking is
 * idempotent per sealed bag, since the triggering BAG_SEALED notification can redeliver (§11).
 * {@code supersededBy} links an old booking to its replacement when a flight changes (§7) — the old
 * row is never deleted, just flipped to {@code SUPERSEDED}. {@code handedOverAt}/{@code loadedAt} are
 * the two ground-crew confirmations (§9).
 */
@Entity
@Table(name = "awb")
@Getter
@Setter
@NoArgsConstructor
public class Awb extends MutableBaseEntity {

    @Column(name = "awb_no", length = 64, nullable = false)
    private String awbNo;

    @Column(name = "flight_no", length = 20, nullable = false)
    private String flightNo;

    @Column(name = "flight_date", nullable = false)
    private LocalDate flightDate;

    @Column(name = "origin_hub", length = 10, nullable = false)
    private String originHub;

    @Column(name = "dest_hub", length = 10, nullable = false)
    private String destHub;

    @Column(name = "bag_id", nullable = false)
    private UUID bagId;

    @Column(name = "total_weight_grams", nullable = false)
    private int totalWeightGrams;

    @Column(name = "parcel_count", nullable = false)
    private int parcelCount;

    @Column(name = "cost_paise", nullable = false)
    private long costPaise;

    @Column(name = "provider_ref", length = 50, nullable = false)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AwbStatus status;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "handed_over_at")
    private Instant handedOverAt;

    @Column(name = "loaded_at")
    private Instant loadedAt;
}
