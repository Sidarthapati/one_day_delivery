package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Not append-only — deleted and replaced wholesale on each OSRM matrix refresh.
@Entity
@Table(name = "h3_hex_travel_time")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HexTravelTime {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "h3_grid_id", nullable = false, updatable = false)
    private UUID h3GridId;

    @Column(name = "from_hex_id", nullable = false, updatable = false)
    private UUID fromHexId;

    @Column(name = "to_hex_id", nullable = false, updatable = false)
    private UUID toHexId;

    @Column(name = "travel_time_seconds", nullable = false)
    private int travelTimeSeconds;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    @PrePersist
    protected void prePersist() {
        if (computedAt == null) {
            computedAt = Instant.now();
        }
    }
}
