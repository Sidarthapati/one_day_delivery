package com.oneday.grid.domain;

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

// Append-only. Old rows are never deleted or mutated.
// proposal_id links each row to its generating proposal — allows multiple
// proposals for the same date (e.g. after a rejection + re-run) without ambiguity.
@Entity
@Table(name = "da_tile_assignment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DaTileAssignment {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "valid_date", nullable = false, updatable = false)
    private LocalDate validDate;

    // > 1 when this tile is covered by multiple DAs (Component C)
    @Column(name = "n_das_on_tile", nullable = false)
    @Builder.Default
    private int nDasOnTile = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssignmentStatus status;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private Instant proposedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @PrePersist
    protected void prePersist() {
        if (proposedAt == null) {
            proposedAt = Instant.now();
        }
    }
}
