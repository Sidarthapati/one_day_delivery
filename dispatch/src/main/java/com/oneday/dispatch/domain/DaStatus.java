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
 * One row per DA — the only fully-mutable M5 table. Authoritative state lives in memory and is
 * flushed here periodically (GPS, status, queue depth). {@code updated_at} is bumped by both the
 * inherited {@code @UpdateTimestamp} and the DB trigger (covers native batch flushes).
 */
@Entity
@Table(name = "da_status")
@Getter
@Setter
@NoArgsConstructor
public class DaStatus extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, unique = true, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "shift_type", length = 20)
    private String shiftType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DaStatusEnum status;

    @Column(name = "last_gps_lat")
    private Double lastGpsLat;

    @Column(name = "last_gps_lon")
    private Double lastGpsLon;

    @Column(name = "current_tile_id")
    private UUID currentTileId;

    @Column(name = "queue_depth", nullable = false)
    private int queueDepth;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;
}
