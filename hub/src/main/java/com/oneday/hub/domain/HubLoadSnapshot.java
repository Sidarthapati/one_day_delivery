package com.oneday.hub.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Rolling overload snapshot per (hub, wave) (§11). Populated in PR #3.
@Entity
@Table(name = "hub_load_snapshot")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubLoadSnapshot {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "hub_id", nullable = false, updatable = false)
    private UUID hubId;

    @Column(name = "wave_key", nullable = false, length = 40, updatable = false)
    private String waveKey;

    @Column(name = "inbound_count", nullable = false)
    private int inboundCount;

    @Column(name = "awaiting_sort", nullable = false)
    private int awaitingSort;

    @Column(name = "stand_occupancy_pct", nullable = false)
    private int standOccupancyPct;

    @Column(name = "projected_clear_at")
    private Instant projectedClearAt;

    @Column(name = "overloaded", nullable = false)
    private boolean overloaded;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    @PrePersist
    void prePersist() {
        if (snapshotAt == null) {
            snapshotAt = Instant.now();
        }
    }
}
