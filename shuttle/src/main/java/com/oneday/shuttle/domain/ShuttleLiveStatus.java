package com.oneday.shuttle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Latest GPS per shuttle agent, OVERWRITTEN in place (one live row per agent) — mirror of
 * {@code van_live_status}. Raw pings never land on the bus; only this row is kept, and the tracking
 * read path reads it through {@code LiveShuttlePositionPort}.
 */
@Entity
@Table(name = "shuttle_live_status")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShuttleLiveStatus {

    @Id
    @Column(name = "agent_id", updatable = false, nullable = false)
    private UUID agentId;

    @Column(name = "city_id")
    private String cityId;

    @Column(name = "last_lat")
    private Double lastLat;

    @Column(name = "last_lon")
    private Double lastLon;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
