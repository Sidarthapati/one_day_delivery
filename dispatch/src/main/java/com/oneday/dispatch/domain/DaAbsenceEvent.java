package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One midday DA-absence reassignment: a station manager marks a set of DAs absent, the plan is
 * previewed (PENDING), then applied — by the manager, or automatically once {@code autoApproveAt}
 * passes. Backed by {@code da_absence_event}. The absent-DA set is a small comma-joined list (v1).
 */
@Entity
@Table(name = "da_absence_event")
@Getter
@Setter
@NoArgsConstructor
public class DaAbsenceEvent extends MutableBaseEntity {

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private java.time.LocalDate operatingDate;

    @Column(name = "absent_da_ids", nullable = false, updatable = false)
    private String absentDaIds;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AbsenceStatus status;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "auto_approve_at", nullable = false)
    private Instant autoApproveAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "proposal_id")
    private UUID proposalId;

    @Column(name = "orphan_count", nullable = false)
    private int orphanCount;

    public List<UUID> absentDaIdList() {
        if (absentDaIds == null || absentDaIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(absentDaIds.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(UUID::fromString).toList();
    }

    public void setAbsentDaIdList(List<UUID> ids) {
        this.absentDaIds = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
