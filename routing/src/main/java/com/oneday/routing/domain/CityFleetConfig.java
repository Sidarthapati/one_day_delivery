package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Per-city fleet + cycle/dwell knobs (M6-D-005/-019). Mutable — ops edits it.
@Entity
@Table(name = "city_fleet_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityFleetConfig {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false, unique = true)
    private UUID cityId;

    @Column(name = "vans_available", nullable = false)
    private int vansAvailable;

    @Column(name = "capacity_packets", nullable = false)
    private int capacityPackets;

    @Column(name = "cycle_time_min_minutes", nullable = false)
    private int cycleTimeMinMinutes;

    @Column(name = "cycle_time_max_minutes", nullable = false)
    private int cycleTimeMaxMinutes;

    @Column(name = "shuttle_cadence_minutes", nullable = false)
    private int shuttleCadenceMinutes;

    @Column(name = "max_da_to_vertex_minutes", nullable = false)
    private int maxDaToVertexMinutes;

    @Column(name = "dwell_minutes", nullable = false)
    private int dwellMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
