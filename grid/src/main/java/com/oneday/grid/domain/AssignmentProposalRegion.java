package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

// One row per DA per proposal. Tile IDs for the region are read from
// da_tile_assignment filtered by (proposal_id, da_id). Append-only.
@Entity
@Table(name = "assignment_proposal_region")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentProposalRegion {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    // > 1 for high-demand tiles that require multiple DAs (Component C pre-processing)
    @Column(name = "n_das_required", nullable = false)
    @Builder.Default
    private int nDasRequired = 1;

    // Total demand_minutes for all tiles in this DA's territory
    @Column(name = "estimated_demand_min", nullable = false)
    private double estimatedDemandMin;

    // estimatedDemandMin / (DA_target_load * nDasRequired)
    @Column(name = "estimated_util_pct", nullable = false)
    private double estimatedUtilPct;

    @Column(name = "has_bootstrapped_tiles", nullable = false)
    private boolean hasBootstrappedTiles;
}
