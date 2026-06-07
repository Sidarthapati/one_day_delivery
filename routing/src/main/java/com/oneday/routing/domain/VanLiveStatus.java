package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// Latest position + lateness per van, OVERWRITTEN in place (M6-D-012). van_id is the natural PK
// (one live row per van); raw GPS pings never land in Kafka — only this row is kept.
@Entity
@Table(name = "van_live_status")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VanLiveStatus {

    @Id
    @Column(name = "van_id", updatable = false, nullable = false)
    private UUID vanId;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Column(name = "route_plan_id")
    private UUID routePlanId;

    @Column(name = "last_lat")
    private Double lastLat;

    @Column(name = "last_lon")
    private Double lastLon;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "current_stop_seq")
    private Integer currentStopSeq;

    @Column(name = "minutes_late")
    private Integer minutesLate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
