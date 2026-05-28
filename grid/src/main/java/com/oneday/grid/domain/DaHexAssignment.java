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
// proposal_id links each row to its generating proposal.
@Entity
@Table(name = "da_hex_assignment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DaHexAssignment {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "hex_id", nullable = false, updatable = false)
    private UUID hexId;

    @Column(name = "valid_date", nullable = false, updatable = false)
    private LocalDate validDate;

    // > 1 when this hex is covered by multiple DAs
    @Column(name = "n_das_on_hex", nullable = false)
    @Builder.Default
    private int nDasOnHex = 1;

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
