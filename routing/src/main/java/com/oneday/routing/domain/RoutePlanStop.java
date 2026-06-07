package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

// Append-only — one ordered stop per (van, loop) of a plan. Immutable once written.
// hexVertexId is M3's h3_hex_vertex.id (plain UUID, no cross-module FK).
@Entity
@Table(name = "route_plan_stop")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlanStop {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "route_plan_id", nullable = false, updatable = false)
    private UUID routePlanId;

    @Column(name = "van_id", nullable = false, updatable = false)
    private UUID vanId;

    @Column(name = "loop_index", nullable = false, updatable = false)
    private int loopIndex;

    @Column(name = "stop_seq", nullable = false, updatable = false)
    private int stopSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_kind", nullable = false, length = 20, updatable = false)
    private StopNodeKind nodeKind;

    @Column(name = "hex_vertex_id", updatable = false)
    private UUID hexVertexId;

    @Column(name = "planned_arrival", updatable = false)
    private LocalTime plannedArrival;

    @Column(name = "planned_departure", updatable = false)
    private LocalTime plannedDeparture;

    @Column(name = "deliver_qty", nullable = false, updatable = false)
    @Builder.Default
    private int deliverQty = 0;

    @Column(name = "collect_qty", nullable = false, updatable = false)
    @Builder.Default
    private int collectQty = 0;

    @Column(name = "load_after", nullable = false, updatable = false)
    @Builder.Default
    private int loadAfter = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
