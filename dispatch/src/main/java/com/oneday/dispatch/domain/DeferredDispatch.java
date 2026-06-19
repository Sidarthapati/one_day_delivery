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
 * A shipment that could not be assigned yet (no DA / cron-infeasible / cron-locked / DA absent /
 * shift ended). The retry job re-attempts PENDING rows; {@code status} and {@code retryAfter} mutate.
 */
@Entity
@Table(name = "deferred_dispatch")
@Getter
@Setter
@NoArgsConstructor
public class DeferredDispatch extends MutableBaseEntity {

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, updatable = false, length = 20)
    private TaskType taskType;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "task_lat", nullable = false, updatable = false)
    private double taskLat;

    @Column(name = "task_lon", nullable = false, updatable = false)
    private double taskLon;

    @Enumerated(EnumType.STRING)
    @Column(name = "defer_reason", nullable = false, length = 50)
    private DeferReason deferReason;

    @Column(name = "deferred_at", nullable = false, updatable = false)
    private Instant deferredAt;

    @Column(name = "retry_after")
    private Instant retryAfter;

    @Column(name = "status", nullable = false, length = 20)
    private String status;   // PENDING | ASSIGNED | ESCALATED | EXPIRED

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private LocalDate operatingDate;

    @PrePersist
    void prePersist() {
        if (deferredAt == null) {
            deferredAt = Instant.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
