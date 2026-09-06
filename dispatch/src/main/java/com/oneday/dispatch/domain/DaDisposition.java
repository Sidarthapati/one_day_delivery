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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One DA-raised disposition (break / auxiliary company-work / day-off). A personal {@code BREAK}
 * auto-approves within the DA's deadline-aware slots + daily allowance and holds his territory;
 * {@code AUXILIARY}/{@code DAY_OFF} are manager-approved. Backed by {@code da_disposition}.
 */
@Entity
@Table(name = "da_disposition")
@Getter
@Setter
@NoArgsConstructor
public class DaDisposition extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private LocalDate operatingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20, updatable = false)
    private DispositionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, updatable = false)
    private DispositionReason reason;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DispositionStatus status;

    /** Requested minutes at approval; refined to actual elapsed on {@code end} so early return refunds. */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    /** BREAK reasons count against the 60-min/day allowance; AUXILIARY / DAY_OFF do not. */
    @Column(name = "counts_allowance", nullable = false)
    private boolean countsAllowance = true;

    /** 0 = none; bumped past {@code scheduledEnd} by the monitor to drive the in-app overstay banner. */
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;
}
