package com.oneday.hub.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// A stand-full move: old + new stand + relabel reason (§7.2, M7-D-008). Append-only.
@Entity
@Table(name = "stand_reassignment_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandReassignmentAudit {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bag_id", nullable = false, updatable = false)
    private UUID bagId;

    @Column(name = "old_stand_id", nullable = false, updatable = false)
    private UUID oldStandId;

    @Column(name = "new_stand_id", nullable = false, updatable = false)
    private UUID newStandId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "reason", length = 120, updatable = false)
    private String reason;

    @Column(name = "new_label", length = 64, updatable = false)
    private String newLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
