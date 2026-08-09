package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single task in a DA's priority queue (a pickup or a delivery). Backed by {@code dispatch_queue}.
 *
 * <p>Effectively append-only: a row is inserted at assignment time and only its lifecycle fields
 * ({@code status}, {@code startedAt}, {@code completedAt}, {@code expectedEta}, {@code queuePosition})
 * change thereafter. The partial unique index on
 * {@code (da_id, shipment_id, task_type, operating_date) WHERE status NOT IN ('FAILED','CANCELLED')}
 * permits re-assignment after a failed attempt.</p>
 */
@Entity
@Table(name = "dispatch_queue")
@Getter
@Setter
@NoArgsConstructor
public class DispatchQueue extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, updatable = false, length = 20)
    private TaskType taskType;

    @Column(name = "task_lat", nullable = false, updatable = false)
    private double taskLat;

    @Column(name = "task_lon", nullable = false, updatable = false)
    private double taskLon;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "queue_position", nullable = false)
    private int queuePosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "payment_mode", length = 20, updatable = false)
    private String paymentMode;

    @Column(name = "cross_territory", nullable = false, updatable = false)
    private boolean crossTerritory;

    @Column(name = "home_tile_id", updatable = false)
    private UUID homeTileId;

    @Column(name = "cron_safe", nullable = false, updatable = false)
    private boolean cronSafe;

    // Cron-aware reorder: true when this task can't be reached before the cron/van-meeting cutoff, so it
    // is parked at the queue tail ("after van meeting") and excluded from pre-cron feasibility. Mutable —
    // it flips as the DA moves, tasks arrive/complete, and the cutoff nears; auto-clears once reachable.
    @Column(name = "beyond_cron", nullable = false)
    private boolean beyondCron;

    // True once the sender OTP is verified (shipment PICKED_UP) but the task hasn't yet been handed off
    // (still IN_PROGRESS). Lets the DA app resume on the hand-off step instead of "en route".
    @Column(name = "picked_up", nullable = false)
    private boolean pickedUp;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "expected_eta")
    private Instant expectedEta;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private LocalDate operatingDate;

    @PrePersist
    void prePersist() {
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }
}
