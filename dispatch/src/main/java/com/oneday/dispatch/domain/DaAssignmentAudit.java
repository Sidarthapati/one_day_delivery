package com.oneday.dispatch.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only decision log — one row per assignment attempt, capturing the cheapest-insertion and
 * cron-feasibility maths. Extends {@link BaseEntity} (not Mutable): rows are never updated, so the
 * inherited {@code updated_at} simply stays equal to {@code created_at}.
 */
@Entity
@Table(name = "da_assignment_audit")
@Getter
@Setter
@NoArgsConstructor
public class DaAssignmentAudit extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, updatable = false, length = 20)
    private TaskType taskType;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "da_id_selected", updatable = false)
    private UUID daIdSelected;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 40)
    private AssignmentDecision decision;

    @Column(name = "insertion_pos", updatable = false)
    private Integer insertionPos;

    @Column(name = "cheapest_insert_extra_sec", updatable = false)
    private Integer cheapestInsertExtraSec;

    @Column(name = "cron_slack_sec", updatable = false)
    private Integer cronSlackSec;

    @Column(name = "used_osrm", nullable = false, updatable = false)
    private boolean usedOsrm;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @PrePersist
    void prePersist() {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }
}
