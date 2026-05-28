package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Append-only. One proposal per city per date per run.
// understaffedHexIds: JSONB array of UUID strings for hexes where K_available < K_needed;
// the service layer serializes/deserializes with Jackson.
@Entity
@Table(name = "assignment_proposal")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentProposal {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "valid_for_date", nullable = false)
    private LocalDate validForDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProposalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_type", nullable = false, length = 30)
    @Builder.Default
    private ProposalType proposalType = ProposalType.NIGHTLY;

    @Enumerated(EnumType.STRING)
    @Column(name = "solver_type", nullable = false, length = 30)
    private SolverType solverType;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjacency_source", nullable = false, length = 30)
    private AdjacencySource adjacencySource;

    // Null for BFS_FALLBACK proposals (no optimality bound available)
    @Column(name = "optimality_gap_pct")
    private Double optimalityGapPct;

    @Column(name = "total_das", nullable = false)
    private int totalDas;

    @Column(name = "coverage_pct")
    private Double coveragePct;

    // JSON array of tile UUID strings for tiles where K_available < K_needed
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "understaffed_hex_ids", columnDefinition = "jsonb")
    private String understaffedHexIds;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private Instant proposedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @PrePersist
    protected void prePersist() {
        if (proposedAt == null) {
            proposedAt = Instant.now();
        }
    }
}
