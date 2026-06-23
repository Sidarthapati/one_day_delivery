package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// The van's mini-hub sort plan, one per (van, loop, day) (M6-D-015). Mutable — status drives the
// lifecycle BUILDING → LOADED → IN_PROGRESS → RETURNED → RECONCILED.
@Entity
@Table(name = "van_manifest")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VanManifest {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Mutable: the manifest is the physical "one van, one loop, one day" row; an intraday re-plan
    // re-points it at the new revision rather than orphaning it (Option B, §10.x reconciliation).
    @Column(name = "route_plan_id", nullable = false)
    private UUID routePlanId;

    @Column(name = "van_id", nullable = false, updatable = false)
    private UUID vanId;

    @Column(name = "loop_index", nullable = false, updatable = false)
    private int loopIndex;

    @Column(name = "valid_date", nullable = false, updatable = false)
    private LocalDate validDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ManifestStatus status;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = Instant.now();
    }
}
