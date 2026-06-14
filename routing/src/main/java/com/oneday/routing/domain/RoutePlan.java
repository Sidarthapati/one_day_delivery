package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Append-only (C17). Overrides/fallbacks create a NEW revision that supersedes; the plan body never
// mutates. Only the approval stamp and the status flip to SUPERSEDED change after creation.
@Entity
@Table(name = "route_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlan {

    // Application-assigned: the planning/override/fallback paths set the id up front so the assembled
    // route_plan_stop / da_cron_schedule children can reference it before persist. A DB-side generator
    // here would overwrite that id and orphan the children (FK violation), so the id is never generated.
    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "valid_for_date", nullable = false, updatable = false)
    private LocalDate validForDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoutePlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private RoutePlanSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "solver_type", nullable = false, length = 20, updatable = false)
    private RoutingSolverType solverType;

    @Column(name = "revision", nullable = false, updatable = false)
    @Builder.Default
    private int revision = 1;

    @Column(name = "supersedes_plan_id", updatable = false)
    private UUID supersedesPlanId;

    @Column(name = "vans_used", updatable = false)
    private Integer vansUsed;

    @Column(name = "recommended_van_count", updatable = false)
    private Integer recommendedVanCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_flag", length = 20, updatable = false)
    private ProvisioningFlag provisioningFlag;

    @Column(name = "n_loops", updatable = false)
    private Integer nLoops;

    @Column(name = "realised_cycle_minutes", updatable = false)
    private Integer realisedCycleMinutes;

    @Column(name = "notes", columnDefinition = "text", updatable = false)
    private String notes;

    // JSON array of meeting-vertex UUIDs deferred by the drop-and-flag solve (M6-INFEASIBLE-VERTICES).
    @Column(name = "deferred_vertex_ids", columnDefinition = "text", updatable = false)
    private String deferredVertexIds;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
